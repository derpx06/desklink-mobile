/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A trigger, condition or action (contract F7 A1): a kind and up to 8 flat parameters. */
data class Step(val kind: String, val params: Map<String, Any> = emptyMap()) {
    /** A parameter as text ("" and absent are the same). */
    operator fun get(key: String): String? = params[key]?.toString()?.takeIf(String::isNotEmpty)
}

/** F7 A1. `runOn` names the side that watches the trigger and runs the rule. */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Step,
    val conditions: List<Step> = emptyList(),
    val actions: List<Step> = emptyList(),
    val runOn: String = Rules.PHONE,
    val modified: Long = 0,
    val deleted: Boolean = false,
) {
    val builtin get() = id.startsWith(Rules.BUILTIN_PREFIX)
}

/** A trigger that happened (A3 `event`, or one of this phone's own). */
data class Event(val kind: String, val params: Map<String, Any> = emptyMap())

/** One run, for the per-rule history (A8, local only). */
data class Run(val at: Long, val ok: Boolean, val detail: String)

/** What a phone-run rule's conditions are read against. */
data class PhoneState(
    val ssid: String?,
    val minuteOfDay: Int,
    /** The desk this phone is linked to now (feature-ready). */
    val deskConnected: Boolean,
    /** From the desk's last `desk-locked`/`desk-unlocked` event; null when unknown. */
    val deskLocked: Boolean?,
)

object Rules {
    const val PHONE = "phone"
    const val DESK = "desk"
    const val BUILTIN_PREFIX = "builtin:"

    // A5 triggers the phone produces.
    const val T_WIFI = "wifi"
    const val T_CHARGING = "charging"
    const val T_BATTERY = "battery"
    const val T_CALL = "call"
    const val T_NOTIFICATION = "notification"
    const val T_NFC = "nfc"
    val PHONE_TRIGGERS = listOf(T_WIFI, T_CHARGING, T_BATTERY, T_CALL, T_NOTIFICATION, T_NFC)

    // A5 triggers the desk produces, and `time` (evaluated where the rule runs).
    const val T_TIME = "time"
    val DESK_TRIGGERS = listOf("phone-connected", "phone-left", "desk-locked", "desk-unlocked", "desk-sleep", "desk-resume", "command-finished")
    val TRIGGERS = PHONE_TRIGGERS + T_TIME + DESK_TRIGGERS

    // A6 conditions.
    const val C_PHONE_CONNECTED = "phone-connected"
    const val C_WIFI = "wifi"
    const val C_TIME_BETWEEN = "time-between"
    const val C_DESK_LOCKED = "desk-locked"
    val CONDITIONS = listOf(C_PHONE_CONNECTED, C_WIFI, C_TIME_BETWEEN, C_DESK_LOCKED)

    // A4 actions. `phone-volume` and `phone-dnd` are this phone's additions (flagged in the contract notes).
    const val A_RINGER = "phone-ringer"
    const val A_RING = "phone-ring"
    const val A_NOTIFY = "phone-notify"
    const val A_OPEN = "phone-open"
    const val A_VOLUME = "phone-volume"
    const val A_DND = "phone-dnd"
    val PHONE_ACTIONS = listOf(A_RINGER, A_RING, A_NOTIFY, A_OPEN, A_VOLUME, A_DND)
    val DESK_ACTIONS = listOf("run-command", "lock-desk", "keep-awake", "send-clipboard", "open-url", "notify", "pause-media")
    val ACTIONS = PHONE_ACTIONS + DESK_ACTIONS

    /** A1: rules per side, live and tombstones. */
    const val MAX_RULES = 200
    const val MAX_ID_CHARS = 128
    const val MAX_NAME_CHARS = 80
    /** A1: conditions and actions per rule. */
    const val MAX_STEPS = 8
    const val MAX_PARAMS = 8
    const val MAX_VALUE_CHARS = 512
    /** A2: ids per `fetch`. */
    const val FETCH_IDS = 100
    /** A2: one `items` packet (under the WebRTC payload bound). */
    const val PACKET_BUDGET = org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET
    const val TOMBSTONE_DAYS = 30L
    /** A8. */
    const val MAX_HISTORY = 20
    /** A6: a rule fires at most this often. */
    const val MIN_FIRE_INTERVAL_MS = 5_000L
    /** A3: events and `do`s a minute per sender. */
    const val MAX_EVENTS_PER_MINUTE = 60
    const val MAX_DO_PER_MINUTE = 30
    /** Index entries read from one packet. */
    const val MAX_INDEX_ENTRIES = 2 * MAX_RULES

    fun isDeskTrigger(kind: String) = kind in DESK_TRIGGERS

    /** Every kind is one this build runs (A1: otherwise kept, synced and shown, never run). */
    fun runnable(rule: Rule) = rule.trigger.kind in TRIGGERS && rule.conditions.all { it.kind in CONDITIONS } && rule.actions.all { it.kind in ACTIONS }

    /** A6: kinds equal and every parameter the rule sets equals the event's. */
    fun matches(trigger: Step, event: Event): Boolean {
        if (trigger.kind != event.kind) return false
        return trigger.params.all { (key, want) ->
            val got = event.params[key] ?: return@all false
            when {
                trigger.kind == T_BATTERY && key == "below" -> {
                    val w = want.toString().toIntOrNull() ?: return@all false
                    val g = got.toString().toIntOrNull() ?: return@all false
                    g <= w
                }
                trigger.kind == T_TIME && key == "days" -> got.toString() in want.toString()
                (trigger.kind == T_NOTIFICATION && key == "app") || (trigger.kind == "command-finished" && key == "job") -> want.toString() == got.toString()
                want is String && got is String -> want.equals(got, ignoreCase = true)
                else -> want.toString() == got.toString()
            }
        }
    }

    /** A6 conditions, evaluated on the phone for a phone-run rule. An unknown condition never holds. */
    fun holds(condition: Step, state: PhoneState): Boolean = when (condition.kind) {
        C_PHONE_CONNECTED -> (condition["connected"] ?: "true").toBoolean() == state.deskConnected
        C_WIFI -> condition["ssid"]?.let { ssidEquals(it, state.ssid) } ?: (state.ssid != null)
        C_TIME_BETWEEN -> {
            val from = minutes(condition["from"]) ?: return false
            val to = minutes(condition["to"]) ?: return false
            if (from <= to) state.minuteOfDay in from until to else state.minuteOfDay >= from || state.minuteOfDay < to
        }
        C_DESK_LOCKED -> state.deskLocked != null && (condition["locked"] ?: "true").toBoolean() == state.deskLocked
        else -> false
    }

    /** Android reports SSIDs quoted. */
    fun ssidEquals(a: String, b: String?) = b != null && a.trim('"') == b.trim('"')

    /** "HH:MM" → minute of the day. */
    fun minutes(text: String?): Int? {
        val parts = text?.split(':')?.takeIf { it.size == 2 } ?: return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun minuteOfDay(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.let { it[Calendar.HOUR_OF_DAY] * 60 + it[Calendar.MINUTE] }

    /** Monday = 1 … Sunday = 7 (A5 `days`). */
    fun dayOfWeek(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.let { (it[Calendar.DAY_OF_WEEK] + 5) % 7 + 1 }

    /** The phone's rules due for [event]: live, enabled, run here, runnable, matching, conditions holding. */
    fun due(rules: Collection<Rule>, event: Event, state: PhoneState): List<Rule> = rules.filter {
        !it.deleted && it.enabled && it.runOn == PHONE && runnable(it) && matches(it.trigger, event) && it.conditions.all { c -> holds(c, state) }
    }

    // ---- wire and stored form, bounded on receipt ---------------------------------------------------------

    fun toJson(step: Step): JSONObject = JSONObject().put("kind", step.kind).put("params", JSONObject().apply { step.params.forEach { (k, v) -> put(k, v) } })

    fun toJson(rule: Rule): JSONObject = JSONObject()
        .put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled).put("runOn", rule.runOn).put("modified", rule.modified)
        .put("trigger", toJson(rule.trigger))
        .put("conditions", JSONArray().apply { rule.conditions.forEach { put(toJson(it)) } })
        .put("actions", JSONArray().apply { rule.actions.forEach { put(toJson(it)) } })
        .apply { if (rule.deleted) put("deleted", true) }

    /** A step, or null when over a bound or nested (the whole rule is then dropped, never truncated). */
    fun stepFrom(o: JSONObject?): Step? {
        val kind = o?.opt("kind") as? String ?: return null
        if (kind.isEmpty() || kind.length > MAX_VALUE_CHARS) return null
        val params = o.optJSONObject("params") ?: JSONObject()
        if (params.length() > MAX_PARAMS) return null
        val map = LinkedHashMap<String, Any>()
        for (key in params.keys()) {
            if (key.length > MAX_VALUE_CHARS) return null
            map[key] = when (val v = params.opt(key)) {
                is String -> v.takeIf { it.length <= MAX_VALUE_CHARS } ?: return null
                is Boolean -> v
                is Number -> v
                else -> return null
            }
        }
        return Step(kind, map)
    }

    fun ruleFrom(o: JSONObject?): Rule? {
        val id = o?.opt("id") as? String ?: return null
        if (id.isEmpty() || id.length > MAX_ID_CHARS || id.any(Char::isISOControl)) return null
        val modified = (o.opt("modified") as? Number)?.toLong() ?: return null
        val deleted = o.opt("deleted") == true
        val name = o.opt("name") as? String ?: ""
        if (name.length > MAX_NAME_CHARS) return null
        val runOn = (o.opt("runOn") as? String)?.takeIf { it == PHONE || it == DESK } ?: return null
        fun steps(key: String): List<Step>? {
            val a = o.optJSONArray(key) ?: return emptyList()
            if (a.length() > MAX_STEPS) return null
            return (0 until a.length()).map { stepFrom(a.optJSONObject(it)) ?: return null }
        }
        val trigger = stepFrom(o.optJSONObject("trigger")) ?: if (deleted) Step("") else return null
        val conditions = steps("conditions") ?: return null
        val actions = steps("actions") ?: return null
        if (!deleted && actions.isEmpty()) return null
        return Rule(id, name, o.opt("enabled") != false, trigger, conditions, actions, runOn, modified, deleted)
    }

    // ---- A2 sync: last writer wins on `modified` ----------------------------------------------------------

    fun indexPacket(rules: Collection<Rule>) = packet("index").apply {
        this["items"] = JSONArray().apply {
            rules.forEach { put(JSONObject().put("id", it.id).put("modified", it.modified).apply { if (it.deleted) put("deleted", true) }) }
        }
    }

    /** The ids in a peer's index to fetch: ones we lack, or newer there. */
    fun wanted(ours: Map<String, Rule>, index: JSONArray): List<String> = (0 until minOf(index.length(), MAX_INDEX_ENTRIES)).mapNotNull { i ->
        val entry = index.optJSONObject(i) ?: return@mapNotNull null
        val id = entry.opt("id") as? String ?: return@mapNotNull null
        val modified = (entry.opt("modified") as? Number)?.toLong() ?: return@mapNotNull null
        id.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS && (ours[it]?.modified ?: Long.MIN_VALUE) < modified }
    }

    fun fetchPackets(ids: List<String>) = ids.chunked(FETCH_IDS).map { chunk -> packet("fetch").apply { this["ids"] = chunk } }

    /** `items` packets for [rules], each under [budget] serialized bytes. */
    fun itemsPackets(rules: Collection<Rule>, budget: Int = PACKET_BUDGET): List<NetworkPacket> {
        val packets = mutableListOf<NetworkPacket>()
        var batch = JSONArray()
        var size = 0
        fun flush() {
            if (batch.length() == 0) return
            val items = batch
            packets += packet("items").apply { this["items"] = items }
            batch = JSONArray()
            size = 0
        }
        for (rule in rules) {
            val json = toJson(rule)
            val length = json.toString().toByteArray().size + 1
            if (length > budget - 256) continue
            if (size + length > budget - 256) flush()
            batch.put(json)
            size += length
        }
        flush()
        return packets
    }

    /** A2: an incoming rule replaces ours only when newer (equal keeps ours). */
    fun newer(incoming: Rule, ours: Rule?) = ours == null || incoming.modified > ours.modified

    fun packet(op: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_AUTOMATIONS).apply { this["op"] = op }

    fun eventPacket(event: Event) = packet("event").apply { this["trigger"] = toJson(Step(event.kind, event.params)) }

    fun donePacket(runId: String, ok: Boolean, error: String?) = packet("done").apply {
        this["runId"] = runId
        this["ok"] = ok
        error?.let { this["error"] = it }
    }

    /** Tombstones older than [TOMBSTONE_DAYS] go. */
    fun prune(rules: Map<String, Rule>, now: Long): Map<String, Rule> {
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        return rules.filterValues { !it.deleted || it.modified > cutoff }
    }

    // ---- stored form -------------------------------------------------------------------------------------

    const val STORED_VERSION = 2

    /** Rules, which desk each desk rule came from (local), and the history. */
    data class Stored(val rules: Map<String, Rule>, val origin: Map<String, String>, val history: Map<String, List<Run>>)

    fun toStored(stored: Stored): JSONObject = JSONObject()
        .put("version", STORED_VERSION)
        .put("rules", JSONArray().apply { stored.rules.values.forEach { put(toJson(it)) } })
        .put("origin", JSONObject(stored.origin))
        .put("history", JSONObject().apply {
            stored.history.forEach { (id, runs) ->
                put(id, JSONArray().apply { runs.forEach { put(JSONObject().put("at", it.at).put("ok", it.ok).put("detail", it.detail)) } })
            }
        })

    fun fromStored(json: JSONObject): Stored {
        require(json.optInt("version", 1) <= STORED_VERSION) { "written by a newer DeskLink" }
        val rules = LinkedHashMap<String, Rule>()
        json.optJSONArray("rules")?.let { a ->
            for (i in 0 until a.length()) {
                if (rules.size >= MAX_RULES) break
                // Version 1 had no `modified`: read as just written.
                val o = a.optJSONObject(i)?.let { if (it.has("modified")) it else JSONObject(it.toString()).put("modified", 1L) }
                ruleFrom(o)?.let { rules[it.id] = it }
            }
        }
        val origin = HashMap<String, String>()
        json.optJSONObject("origin")?.let { o -> o.keys().forEach { id -> if (id in rules) origin[id] = o.optString(id) } }
        val history = HashMap<String, List<Run>>()
        json.optJSONObject("history")?.let { h ->
            h.keys().forEach { id ->
                if (id !in rules) return@forEach
                val a = h.optJSONArray(id) ?: return@forEach
                history[id] = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .map { Run(it.optLong("at"), it.optBoolean("ok"), it.optString("detail").take(MAX_VALUE_CHARS)) }
                    .takeLast(MAX_HISTORY)
            }
        }
        return Stored(rules, origin, history)
    }
}
