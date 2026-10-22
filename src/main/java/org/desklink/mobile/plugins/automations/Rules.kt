/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A trigger, condition or action: a kind and a few string parameters. */
data class Step(val kind: String, val params: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String? = params[key]?.takeIf(String::isNotEmpty)
}

/**
 * F7: "When [trigger], if [conditions], do [actions]". The plan's shared shape, so the desk can
 * show phone rules once a wire contract exists; `runOn` is always "phone" here.
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Step,
    val conditions: List<Step> = emptyList(),
    val actions: List<Step> = emptyList(),
    val runOn: String = "phone",
)

/** Something that happened on the phone. */
data class Event(val kind: String, val params: Map<String, String> = emptyMap())

/** One run, for the per-rule history. */
data class Run(val at: Long, val ok: Boolean, val detail: String)

/** What conditions are read against. */
data class PhoneState(val charging: Boolean, val ssid: String?, val minuteOfDay: Int)

object Rules {
    const val TRIGGER_WIFI_CONNECTED = "wifi_connected"
    const val TRIGGER_WIFI_DISCONNECTED = "wifi_disconnected"
    const val TRIGGER_CHARGING_STARTED = "charging_started"
    const val TRIGGER_CHARGING_STOPPED = "charging_stopped"
    const val TRIGGER_BATTERY_LOW = "battery_low"
    const val TRIGGER_CALL_INCOMING = "call_incoming"
    const val TRIGGER_CALL_MISSED = "call_missed"
    const val TRIGGER_NOTIFICATION = "notification"
    const val TRIGGER_NFC_TAG = "nfc_tag"
    val TRIGGERS = listOf(
        TRIGGER_WIFI_CONNECTED, TRIGGER_WIFI_DISCONNECTED, TRIGGER_CHARGING_STARTED, TRIGGER_CHARGING_STOPPED,
        TRIGGER_BATTERY_LOW, TRIGGER_CALL_INCOMING, TRIGGER_CALL_MISSED, TRIGGER_NOTIFICATION, TRIGGER_NFC_TAG,
    )

    const val CONDITION_CHARGING = "charging"
    const val CONDITION_NOT_CHARGING = "not_charging"
    const val CONDITION_WIFI = "wifi"
    const val CONDITION_TIME = "time_between"
    val CONDITIONS = listOf(CONDITION_CHARGING, CONDITION_NOT_CHARGING, CONDITION_WIFI, CONDITION_TIME)

    const val ACTION_RING = "ring"
    const val ACTION_RINGER = "ringer"
    const val ACTION_VOLUME = "volume"
    const val ACTION_DND = "dnd"
    const val ACTION_OPEN_TERMINALS = "open_terminals"
    const val ACTION_OPEN_DESK_SCREEN = "open_desk_screen"
    const val ACTION_NOTIFY = "notify"
    const val ACTION_RUN_DESK_COMMAND = "run_desk_command"
    val ACTIONS = listOf(
        ACTION_RING, ACTION_RINGER, ACTION_VOLUME, ACTION_DND, ACTION_OPEN_TERMINALS, ACTION_OPEN_DESK_SCREEN,
        ACTION_NOTIFY, ACTION_RUN_DESK_COMMAND,
    )

    /** Rules kept: a person's automations, not a database. */
    const val MAX_RULES = 100
    const val MAX_CONDITIONS = 5
    const val MAX_ACTIONS = 10
    /** A name, an SSID (32 bytes), a package name, a tag id, a notification text. */
    const val MAX_TEXT_CHARS = 200
    const val MAX_PARAMS = 8
    /** Runs remembered per rule (the plan's "last 20"). */
    const val MAX_HISTORY = 20

    /** A trigger matches an event of its kind whose named parameters agree (blank = any). */
    fun matches(trigger: Step, event: Event): Boolean {
        if (trigger.kind != event.kind) return false
        return when (trigger.kind) {
            TRIGGER_WIFI_CONNECTED, TRIGGER_WIFI_DISCONNECTED -> trigger["ssid"]?.let { ssidEquals(it, event.params["ssid"]) } ?: true
            TRIGGER_NOTIFICATION -> trigger["package"]?.let { it == event.params["package"] } ?: true
            TRIGGER_NFC_TAG -> trigger["tag"]?.let { it == event.params["tag"] } ?: true
            // The event says it crossed below this rule's threshold.
            TRIGGER_BATTERY_LOW -> {
                val threshold = trigger["percent"]?.toIntOrNull() ?: return false
                val level = event.params["level"]?.toIntOrNull() ?: return false
                val previous = event.params["previous"]?.toIntOrNull() ?: return false
                level <= threshold && previous > threshold
            }
            else -> true
        }
    }

    fun holds(condition: Step, state: PhoneState): Boolean = when (condition.kind) {
        CONDITION_CHARGING -> state.charging
        CONDITION_NOT_CHARGING -> !state.charging
        CONDITION_WIFI -> condition["ssid"]?.let { ssidEquals(it, state.ssid) } ?: (state.ssid != null)
        CONDITION_TIME -> {
            val from = minutes(condition["from"]) ?: return false
            val to = minutes(condition["to"]) ?: return false
            if (from <= to) state.minuteOfDay in from until to else state.minuteOfDay >= from || state.minuteOfDay < to
        }
        else -> false // unknown: never runs by accident
    }

    /** Android reports SSIDs quoted. */
    fun ssidEquals(a: String, b: String?) = b != null && a.trim('"') == b.trim('"')

    /** "HH:MM" → minute of the day. */
    fun minutes(text: String?): Int? {
        val (h, m) = text?.split(':')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun minuteOfDay(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.let { it[Calendar.HOUR_OF_DAY] * 60 + it[Calendar.MINUTE] }

    /** The rules to run for [event] given [state]. */
    fun due(rules: List<Rule>, event: Event, state: PhoneState): List<Rule> =
        rules.filter { it.enabled && matches(it.trigger, event) && it.conditions.all { c -> holds(c, state) } }

    // ---- stored form (versioned), bounded on read ----------------------------------------------------------

    const val STORED_VERSION = 1

    fun toJson(step: Step): JSONObject = JSONObject().put("kind", step.kind).put("params", JSONObject(step.params))

    fun toJson(rule: Rule): JSONObject = JSONObject()
        .put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled).put("runOn", rule.runOn)
        .put("trigger", toJson(rule.trigger))
        .put("conditions", JSONArray().apply { rule.conditions.forEach { put(toJson(it)) } })
        .put("actions", JSONArray().apply { rule.actions.forEach { put(toJson(it)) } })

    fun stepFrom(o: JSONObject?): Step? {
        val kind = o?.opt("kind") as? String ?: return null
        val params = o.optJSONObject("params")
        val map = LinkedHashMap<String, String>()
        params?.keys()?.asSequence()?.take(MAX_PARAMS)?.forEach { key ->
            (params.opt(key) as? String)?.let { map[key.take(MAX_TEXT_CHARS)] = it.take(MAX_TEXT_CHARS) }
        }
        return Step(kind.take(MAX_TEXT_CHARS), map)
    }

    fun ruleFrom(o: JSONObject?): Rule? {
        val id = o?.opt("id") as? String ?: return null
        val trigger = stepFrom(o.optJSONObject("trigger"))?.takeIf { it.kind in TRIGGERS } ?: return null
        fun steps(key: String, allowed: List<String>, max: Int) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { stepFrom(a.optJSONObject(it)) }.filter { it.kind in allowed }.take(max)
        }.orEmpty()
        return Rule(
            id = id.take(MAX_TEXT_CHARS),
            name = (o.opt("name") as? String).orEmpty().take(MAX_TEXT_CHARS),
            enabled = o.opt("enabled") != false,
            trigger = trigger,
            conditions = steps("conditions", CONDITIONS, MAX_CONDITIONS),
            actions = steps("actions", ACTIONS, MAX_ACTIONS),
        )
    }

    fun toStored(rules: List<Rule>, history: Map<String, List<Run>>): JSONObject = JSONObject()
        .put("version", STORED_VERSION)
        .put("rules", JSONArray().apply { rules.forEach { put(toJson(it)) } })
        .put("history", JSONObject().apply {
            history.forEach { (id, runs) ->
                put(id, JSONArray().apply { runs.forEach { put(JSONObject().put("at", it.at).put("ok", it.ok).put("detail", it.detail)) } })
            }
        })

    fun fromStored(json: JSONObject): Pair<List<Rule>, Map<String, List<Run>>> {
        require(json.optInt("version", 1) <= STORED_VERSION) { "written by a newer DeskLink" }
        val rules = json.optJSONArray("rules")?.let { a -> (0 until a.length()).mapNotNull { ruleFrom(a.optJSONObject(it)) } }.orEmpty()
            .distinctBy { it.id }.take(MAX_RULES)
        val ids = rules.map { it.id }.toSet()
        val history = HashMap<String, List<Run>>()
        json.optJSONObject("history")?.let { h ->
            h.keys().forEach { id ->
                if (id !in ids) return@forEach
                val a = h.optJSONArray(id) ?: return@forEach
                history[id] = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .map { Run(it.optLong("at"), it.optBoolean("ok"), it.optString("detail").take(MAX_TEXT_CHARS)) }
                    .takeLast(MAX_HISTORY)
            }
        }
        return rules to history
    }
}
