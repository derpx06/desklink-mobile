/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.helpers.TrustedNetworkHelper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * F7 on the phone: the rules, and running them. Events come from [AutomationTriggers]; actions run
 * on one worker thread, never the main thread, and never through accessibility.
 */
object Automations {
    private const val TAG = "DeskLink/Automations"
    private const val FILE = "automations.json"

    /** A rule runs at most this often a minute: a flapping Wi-Fi or a chatty app cannot loop it. */
    const val MAX_RUNS_PER_RULE_PER_MINUTE = 6

    /** The same event twice within this is one (several desks' telephony plugins report one call). */
    const val DUPLICATE_EVENT_MS = 3_000L

    /** Distinct events remembered for de-duplication: a burst of apps posting cannot grow it. */
    const val MAX_RECENT_EVENTS = 256

    private val worker = Executors.newSingleThreadExecutor()
    private val runs = RateLimiter(MAX_RUNS_PER_RULE_PER_MINUTE, 60_000, TAG)
    private var loaded = false
    private var rules: List<Rule> = emptyList()
    private var history: Map<String, List<Run>> = emptyMap()
    private lateinit var file: AtomicFile
    private val lastEvents = LinkedHashMap<Event, Long>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE))
        runCatching {
            if (file.baseFile.exists()) Rules.fromStored(JSONObject(String(file.readFully(), Charsets.UTF_8))).let { (r, h) ->
                rules = r
                history = h
            }
        }.onFailure {
            // Unreadable or newer: kept aside, never overwritten.
            Log.w(TAG, "Could not read the automations (${it.javaClass.simpleName}); starting empty")
            runCatching { file.baseFile.renameTo(File(file.baseFile.path + ".unreadable")) }
        }
        loaded = true
    }

    private fun saveLocked() {
        val plain = Rules.toStored(rules, history).toString().toByteArray(Charsets.UTF_8)
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
    }

    @Synchronized
    fun rules(context: Context): List<Rule> {
        ensure(context)
        return rules
    }

    @Synchronized
    fun history(context: Context, id: String): List<Run> {
        ensure(context)
        return history[id].orEmpty()
    }

    /** Adds or replaces [rule]; false at [Rules.MAX_RULES]. */
    @Synchronized
    fun save(context: Context, rule: Rule): Boolean {
        ensure(context)
        val clean = Rules.ruleFrom(Rules.toJson(rule)) ?: return false
        val existing = rules.indexOfFirst { it.id == clean.id }
        if (existing < 0 && rules.size >= Rules.MAX_RULES) return false
        rules = if (existing < 0) rules + clean else rules.toMutableList().also { it[existing] = clean }
        saveLocked()
        return true
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        ensure(context)
        rules = rules.filter { it.id != id }
        history = history - id
        saveLocked()
    }

    /** Something happened: the enabled rules it triggers run, if their conditions hold. */
    fun fire(context: Context, event: Event) {
        val app = context.applicationContext
        val due = synchronized(this) {
            ensure(app)
            if (rules.none { it.enabled && it.trigger.kind == event.kind }) return
            val now = SystemClock.elapsedRealtime()
            lastEvents.entries.removeAll { now - it.value > DUPLICATE_EVENT_MS }
            if (lastEvents.size >= MAX_RECENT_EVENTS) lastEvents.clear()
            if (lastEvents.containsKey(event)) return
            lastEvents[event] = now
            rules
        }
        worker.execute {
            val state = phoneState(app)
            Rules.due(due, event, state).forEach { rule ->
                if (runs.allow(rule.id)) run(app, rule, event.kind)
            }
        }
    }

    /** "Test run": the actions now, conditions ignored. */
    fun testRun(context: Context, rule: Rule) {
        val app = context.applicationContext
        worker.execute { run(app, rule, "test") }
    }

    private fun run(context: Context, rule: Rule, why: String) {
        val failures = rule.actions.mapNotNull { action ->
            runCatching { AutomationActions.run(context, action) }.getOrElse { e ->
                Log.w(TAG, "Action ${action.kind} failed", e)
                AutomationActions.Result.Failed("error")
            }.let { (it as? AutomationActions.Result.Failed)?.reason?.let { reason -> "${action.kind}: $reason" } }
        }
        Log.i(TAG, "Rule ran ($why): ${rule.actions.size} actions, ${failures.size} failed")
        synchronized(this) {
            val entry = Run(System.currentTimeMillis(), failures.isEmpty(), (listOf(why) + failures).joinToString(" · ").take(Rules.MAX_TEXT_CHARS))
            history = history + (rule.id to (history[rule.id].orEmpty() + entry).takeLast(Rules.MAX_HISTORY))
            saveLocked()
        }
    }

    fun phoneState(context: Context): PhoneState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val ssid = runCatching { TrustedNetworkHelper(context).takeIf { it.hasPermissions }?.currentSSID }.getOrNull()
        return PhoneState(plugged, ssid, Rules.minuteOfDay(System.currentTimeMillis()))
    }
}
