/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.helpers.TrustedNetworkHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * F7 on the phone (contract "DeskLink 1.2 — F7 automations"): the rules both apps show, synced
 * last-writer-wins with each desk; the phone runs the rules whose `runOn` is "phone", sends the
 * desk every trigger it produces (A3 `event`), and runs the phone actions a desk's rules ask for
 * (A3 `do`). All work is on one worker thread; actions never use accessibility.
 */
object Automations {
    private const val TAG = "DeskLink/Automations"
    private const val FILE = "automations.json"

    /** `do`s waiting for their `done`: bounded, the oldest dropped. */
    const val MAX_PENDING_DO = 64

    /** The same phone event twice within this is one (several desks' telephony plugins see one call). */
    const val DUPLICATE_EVENT_MS = 3_000L

    private val worker = Executors.newSingleThreadExecutor()
    private var stored: Rules.Stored? = null
    private lateinit var file: AtomicFile
    private val lastFired = HashMap<String, Long>()
    private val lastEvents = LinkedHashMap<Event, Long>()
    private val pending = LinkedHashMap<String, Pair<String, String>>() // runId → (ruleId, action kind)
    /** Per desk: the last `desk-locked`/`desk-unlocked` event. */
    private val deskLocked = HashMap<String, Boolean>()
    private val events = RateLimiter(Rules.MAX_EVENTS_PER_MINUTE, 60_000, TAG)
    private val dos = RateLimiter(Rules.MAX_DO_PER_MINUTE, 60_000, TAG)

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    // ---- store -------------------------------------------------------------------------------------------

    @Synchronized
    private fun state(context: Context): Rules.Stored {
        stored?.let { return it }
        file = AtomicFile(File(context.applicationContext.filesDir, FILE))
        val loaded = runCatching {
            if (file.baseFile.exists()) Rules.fromStored(JSONObject(String(file.readFully(), Charsets.UTF_8))) else null
        }.getOrElse {
            Log.w(TAG, "Could not read the automations (${it.javaClass.simpleName}); starting empty")
            runCatching { file.baseFile.renameTo(File(file.baseFile.path + ".unreadable")) }
            null
        } ?: Rules.Stored(emptyMap(), emptyMap(), emptyMap())
        return loaded.copy(rules = Rules.prune(loaded.rules, System.currentTimeMillis())).also { stored = it }
    }

    @Synchronized
    private fun commit(next: Rules.Stored) {
        stored = next
        val plain = Rules.toStored(next).toString().toByteArray(Charsets.UTF_8)
        worker.execute {
            runCatching {
                val out = file.startWrite()
                try {
                    out.write(plain)
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            }.onFailure { Log.e(TAG, "Could not save the automations", it) }
        }
        _revision.value++
        AutomationAlarms.reschedule(DeskLinkApplication.getInstance())
    }

    /** Live rules, for the screen (tombstones hidden). */
    @Synchronized
    fun rules(context: Context): List<Rule> = state(context).rules.values.filter { !it.deleted }.sortedByDescending { it.modified }

    @Synchronized
    fun history(context: Context, id: String): List<Run> = state(context).history[id].orEmpty()

    /** Adds or replaces [rule] (a local edit); false when invalid or at [Rules.MAX_RULES]. */
    fun save(context: Context, rule: Rule): Boolean {
        val saved = synchronized(this) {
            val s = state(context)
            val old = s.rules[rule.id]
            if (old == null && s.rules.size >= Rules.MAX_RULES) return false
            // A built-in only changes its switch.
            val edited = if (old?.builtin == true) old.copy(enabled = rule.enabled) else rule
            val next = edited.copy(name = edited.name.take(Rules.MAX_NAME_CHARS), modified = later(old?.modified))
            if (Rules.ruleFrom(Rules.toJson(next)) == null) return false
            commit(s.copy(rules = s.rules + (next.id to next)))
            next
        }
        worker.execute { push(listOf(saved)) }
        return true
    }

    /** A tombstone (A1), pushed like an edit. Built-ins cannot be deleted. */
    fun delete(context: Context, id: String) {
        val gone = synchronized(this) {
            val s = state(context)
            val old = s.rules[id]?.takeIf { !it.deleted && !it.builtin } ?: return
            val tomb = old.copy(deleted = true, trigger = Step(""), conditions = emptyList(), actions = emptyList(), modified = later(old.modified))
            commit(s.copy(rules = s.rules + (id to tomb), history = s.history - id))
            tomb
        }
        worker.execute { push(listOf(gone)) }
    }

    private fun later(previous: Long?) = maxOf(System.currentTimeMillis(), (previous ?: 0) + 1)

    // ---- sync with desks ---------------------------------------------------------------------------------

    private fun readyDesks(): List<Device> = DeskLinkApplication.getInstance().devices.values
        .filter { it.isPaired && it.hasActiveWebRtcFeatureTransport && it.getPlugin(AutomationsPlugin::class.java) != null }

    /** The rules [deviceId] shares: the phone's own, and the desk rules that came from it. */
    private fun sharedWith(s: Rules.Stored, deviceId: String) = s.rules.values.filter { it.runOn == Rules.PHONE || s.origin[it.id] == deviceId || s.origin[it.id] == null }

    private fun push(rules: List<Rule>) {
        val s = synchronized(this) { stored } ?: return
        for (desk in readyDesks()) {
            val mine = rules.filter { it.runOn == Rules.PHONE || s.origin[it.id] == null || s.origin[it.id] == desk.deviceId }
            Rules.itemsPackets(mine).forEach(desk::sendPacket)
        }
    }

    /** A2: on every feature-ready. */
    fun index(context: Context, device: Device) {
        val packet = synchronized(this) { Rules.indexPacket(sharedWith(state(context), device.deviceId)) }
        device.sendPacket(packet)
    }

    /** A desk's `desklink.automations` packet (on a plugin thread). */
    fun onPacket(context: Context, device: Device, packet: NetworkPacket) {
        val app = context.applicationContext
        when (packet.getStringOrNull("op")) {
            "index" -> {
                val ids = synchronized(this) { Rules.wanted(state(app).rules, packet.getJSONArray("items") ?: JSONArray()) }
                Rules.fetchPackets(ids).forEach(device::sendPacket)
            }
            "fetch" -> {
                val raw = packet.getJSONArray("ids") ?: JSONArray()
                val ids = (0 until minOf(raw.length(), Rules.FETCH_IDS)).mapNotNull { raw.opt(it) as? String }.toSet()
                val rules = synchronized(this) { sharedWith(state(app), device.deviceId).filter { it.id in ids } }
                Rules.itemsPackets(rules).forEach(device::sendPacket)
            }
            "items" -> {
                val raw = packet.getJSONArray("items") ?: JSONArray()
                val back = mutableListOf<Rule>()
                synchronized(this) {
                    var s = state(app)
                    var changed = false
                    for (i in 0 until minOf(raw.length(), Rules.MAX_RULES)) {
                        val rule = Rules.ruleFrom(raw.optJSONObject(i)) ?: continue
                        val ours = s.rules[rule.id]
                        if (ours == null && s.rules.size >= Rules.MAX_RULES) continue
                        if (Rules.newer(rule, ours)) {
                            val origin = if (rule.runOn == Rules.DESK) s.origin + (rule.id to device.deviceId) else s.origin - rule.id
                            s = s.copy(rules = s.rules + (rule.id to rule), origin = origin, history = if (rule.deleted) s.history - rule.id else s.history)
                            changed = true
                        } else if (ours != null && ours.modified > rule.modified) {
                            back.add(ours)
                        }
                    }
                    if (changed) commit(s)
                }
                Rules.itemsPackets(back).forEach(device::sendPacket)
            }
            "event" -> {
                if (!events.allow(device.deviceId)) return
                val trigger = Rules.stepFrom(packet.getJSONObject("trigger")) ?: return
                if (trigger.kind == "desk-locked") synchronized(this) { deskLocked[device.deviceId] = true }
                if (trigger.kind == "desk-unlocked") synchronized(this) { deskLocked[device.deviceId] = false }
                runMatching(app, Event(trigger.kind, trigger.params), from = device.deviceId)
            }
            "do" -> {
                val runId = packet.getStringOrNull("runId")?.takeIf { it.length in 1..Rules.MAX_ID_CHARS } ?: return
                if (!dos.allow(device.deviceId)) return device.sendPacket(Rules.donePacket(runId, false, "busy"))
                val ruleId = packet.getStringOrNull("ruleId").orEmpty()
                val action = Rules.stepFrom(packet.getJSONObject("action"))
                // A3: only an action of a live, enabled desk rule from that desk, identical in our copy.
                val rule = synchronized(this) { state(app).let { s -> s.rules[ruleId]?.takeIf { s.origin[ruleId] == device.deviceId } } }
                val allowed = action != null && rule != null && !rule.deleted && rule.enabled && rule.runOn == Rules.DESK && action in rule.actions
                val (ok, error) = when {
                    !allowed -> false to "refused"
                    action!!.kind !in Rules.PHONE_ACTIONS -> false to "unsupported"
                    else -> when (val result = AutomationActions.run(app, action)) {
                        AutomationActions.Result.Done -> true to null
                        is AutomationActions.Result.Failed -> false to if (result.reason == AutomationActions.GATE) "gate" else "failed"
                    }
                }
                device.sendPacket(Rules.donePacket(runId, ok, error))
                if (rule != null) record(app, rule.id, Run(System.currentTimeMillis(), ok, "${device.name}: ${action?.kind}${error?.let { " ($it)" }.orEmpty()}"))
            }
            "done" -> {
                val runId = packet.getStringOrNull("runId") ?: return
                val (ruleId, kind) = synchronized(this) { pending.remove(runId) } ?: return
                val ok = packet.getBoolean("ok", false)
                val error = packet.getStringOrNull("error")?.take(32)
                record(app, ruleId, Run(System.currentTimeMillis(), ok, "$kind on ${device.name}${error?.let { " ($it)" }.orEmpty()}"))
            }
        }
    }

    // ---- running -----------------------------------------------------------------------------------------

    /** One of this phone's triggers (A5): every ready desk hears it, and the phone's own rules run. */
    fun fire(context: Context, event: Event) {
        val app = context.applicationContext
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            lastEvents.entries.removeAll { now - it.value > DUPLICATE_EVENT_MS }
            if (lastEvents.containsKey(event)) return
            if (lastEvents.size >= Rules.MAX_RULES) lastEvents.clear()
            lastEvents[event] = now
        }
        worker.execute {
            if (event.kind != Rules.T_TIME) {
                val packet = Rules.eventPacket(event)
                readyDesks().forEach { it.sendPacket(packet) }
            }
            runMatching(app, event, from = null)
        }
    }

    private fun phoneState(context: Context, from: String?): PhoneState {
        val ssid = runCatching { TrustedNetworkHelper(context).takeIf { it.hasPermissions }?.currentSSID }.getOrNull()
        val desks = readyDesks()
        val locked = synchronized(this) { (from ?: desks.firstOrNull()?.deviceId)?.let(deskLocked::get) }
        return PhoneState(ssid, Rules.minuteOfDay(System.currentTimeMillis()), desks.isNotEmpty(), locked)
    }

    private fun runMatching(context: Context, event: Event, from: String?) {
        val rules = synchronized(this) { state(context).rules.values.toList() }
        val due = Rules.due(rules, event, phoneState(context, from))
        if (due.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        due.forEach { rule ->
            val ready = synchronized(this) {
                val last = lastFired[rule.id]
                (last == null || now - last >= Rules.MIN_FIRE_INTERVAL_MS).also { if (it) lastFired[rule.id] = now }
            }
            if (ready) worker.execute { run(context, rule, event.kind) }
        }
    }

    /** "Test run": the actions now, conditions ignored. */
    fun testRun(context: Context, rule: Rule) {
        val app = context.applicationContext
        worker.execute { run(app, rule, "test") }
    }

    private fun run(context: Context, rule: Rule, why: String) {
        val results = rule.actions.map { action ->
            if (action.kind in Rules.PHONE_ACTIONS) {
                val result = runCatching { AutomationActions.run(context, action) }.getOrElse {
                    Log.w(TAG, "Action ${action.kind} failed", it)
                    AutomationActions.Result.Failed("error")
                }
                action.kind to (result as? AutomationActions.Result.Failed)?.reason
            } else {
                action.kind to sendDo(rule, action)
            }
        }
        val failures = results.filter { it.second != null }
        Log.i(TAG, "Rule ran ($why): ${results.size} actions, ${failures.size} failed")
        record(context, rule.id, Run(System.currentTimeMillis(), failures.isEmpty(), (listOf(why) + failures.map { "${it.first}: ${it.second}" }).joinToString(" · ")))
    }

    /** A desk action of a phone rule (A3 `do`): null when sent, else why not. */
    private fun sendDo(rule: Rule, action: Step): String? {
        val desks = readyDesks()
        if (desks.isEmpty()) return "desk not connected"
        desks.forEach { desk ->
            val runId = UUID.randomUUID().toString()
            synchronized(this) {
                pending[runId] = rule.id to action.kind
                while (pending.size > MAX_PENDING_DO) pending.remove(pending.keys.first())
            }
            desk.sendPacket(Rules.packet("do").apply {
                this["ruleId"] = rule.id
                this["runId"] = runId
                this["action"] = Rules.toJson(action)
            })
        }
        return null
    }

    private fun record(context: Context, ruleId: String, run: Run) = synchronized(this) {
        val s = state(context)
        if (ruleId !in s.rules) return
        val entry = run.copy(detail = run.detail.take(Rules.MAX_VALUE_CHARS))
        commit(s.copy(history = s.history + (ruleId to (s.history[ruleId].orEmpty() + entry).takeLast(Rules.MAX_HISTORY))))
    }

    /** Phone-run rules with a `time` trigger, for [AutomationAlarms]. */
    @Synchronized
    fun timeRules(context: Context): List<Rule> = state(context).rules.values.filter {
        !it.deleted && it.enabled && it.runOn == Rules.PHONE && it.trigger.kind == Rules.T_TIME
    }
}
