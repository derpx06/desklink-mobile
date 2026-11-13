/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.activity

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.notifications.NotificationPrivacy
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.SyncDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live activities (W13): the ongoing items of this phone, as `desklink.activity` carries them. Pure: what Android hands over is
 * reduced to [Candidate]s by the plugin, and everything that is decided about them is here.
 */
object ActivityItems {
    const val MAX_ITEMS = 5
    const val MAX_ID = 240
    const val MAX_LABEL = 120
    const val MAX_TITLE = 240
    const val MAX_TEXT = 500
    val KINDS = listOf("timer", "progress", "navigation", "call", "other")

    /** What the plugin read of one ongoing notification. [hideContents]: title is the app and the text empty. */
    data class Candidate(
        val id: String, val appLabel: String, val title: String, val text: String,
        val progress: Int, val chronometerBase: Long, val countdown: Boolean, val kind: String,
    )

    enum class Show { SKIP, HIDDEN, FULL }

    /**
     * The notification rules of `desklink.notification` applied to an ongoing item: media sessions have their own packet, an app
     * switched off in the Notification filter, a secret notification, a call without "Show caller" and the app's own are never sent;
     * an app set to hide contents, or a private one on a secured lock screen, is sent as the app name alone.
     */
    fun decide(
        ongoing: Boolean, groupSummary: Boolean, media: Boolean, ownApp: Boolean, appEnabled: Boolean,
        privacy: NotificationPrivacy.Mirror, hiddenCall: Boolean, blockContents: Boolean,
    ): Show = when {
        !ongoing || groupSummary || media || ownApp || !appEnabled || hiddenCall -> Show.SKIP
        privacy == NotificationPrivacy.Mirror.SKIP -> Show.SKIP
        privacy == NotificationPrivacy.Mirror.HIDDEN || blockContents -> Show.HIDDEN
        else -> Show.FULL
    }

    /** The kind of an ongoing item, or null when it has nothing live to show (a plain "service is running" notification). */
    fun kindOf(isCall: Boolean, isNavigation: Boolean, hasChronometer: Boolean, hasProgress: Boolean): String? = when {
        isCall -> "call"
        isNavigation -> "navigation"
        hasChronometer -> "timer"
        hasProgress -> "progress"
        else -> null
    }

    /** The percent of [progress] in [max] without overflowing (byte counts of a download are in the hundreds of millions); -1 for none. */
    fun percent(progress: Int, max: Int): Int =
        if (max <= 0) -1 else (progress.toLong().coerceAtLeast(0) * 100 / max).toInt().coerceIn(0, 100)

    /** The same reading of "a call" as `desklink.notification`: the call categories and the default dialer's own notifications. */
    fun isCall(category: String?, packageName: String, dialerPackage: String?): Boolean =
        category == "call" || category == "missed_call" || (dialerPackage != null && dialerPackage == packageName)

    /** A stable id that does not carry the notification's tag (often a contact or conversation key). */
    fun id(notificationId: Int, packageName: String, tag: String?): String =
        "$notificationId|$packageName|" + (tag?.let { Integer.toHexString(it.hashCode()) } ?: "")

    /** At most one snapshot a second: the first change goes at once, the rest wait for one trailing send, which is never pushed back. */
    class Throttle(private val intervalMs: Long = 1_000L) {
        private var last = Long.MIN_VALUE / 2
        private var pending = false

        /** null: a trailing send already waits; 0: send now; more: send after that many ms ([interval] between sends). */
        @Synchronized
        fun onChange(now: Long, interval: Long = intervalMs): Long? {
            if (pending) return null
            val wait = (last + interval - now).coerceAtLeast(0)
            if (wait == 0L) { last = now; return 0L }
            pending = true
            return wait
        }

        @Synchronized
        fun onFired(now: Long) { pending = false; last = now }
    }

    /**
     * Between snapshots with the screen off: the first change (a call ringing) still goes at once, but a progress bar ticking
     * in another app no longer costs a full notification fetch and a packet every second while the phone is in a pocket.
     */
    const val SCREEN_OFF_INTERVAL_MS = 5_000L

    private val IMPORTANCE = listOf("call", "navigation", "timer", "progress", "other")

    /** Calls first, then navigation, timers and progress; the newest first inside a kind. */
    fun importance(kind: String) = IMPORTANCE.indexOf(kind).let { if (it < 0) IMPORTANCE.size else it }

    fun json(c: Candidate, hide: Boolean): JSONObject = JSONObject()
        .put("id", c.id).put("appLabel", c.appLabel)
        .put("title", if (hide) c.appLabel else c.title).put("text", if (hide) "" else c.text)
        .put("progress", c.progress).put("chronometerBase", c.chronometerBase).put("countdown", c.countdown).put("kind", c.kind)

    /**
     * The bounds the receiver keeps, applied before sending (the shared fixture `activity_bodies.json` pins them on both sides):
     * 5 items; an item with no id, or an id seen before, is dropped; lengths cut; progress 0..100 or -1; a chronometer base above 0
     * or -1; a kind outside the five reads as `other`.
     */
    fun sanitize(items: JSONArray): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            if (out.length() >= MAX_ITEMS) break
            val item = items.optJSONObject(i) ?: continue
            val id = (item.opt("id") as? String)?.take(MAX_ID)?.takeIf { it.isNotEmpty() && seen.add(it) } ?: continue
            val progress = (item.opt("progress") as? Number)?.toInt()?.let { if (it < 0) -1 else minOf(it, 100) } ?: -1
            val base = (item.opt("chronometerBase") as? Number)?.toLong()?.takeIf { it > 0 } ?: -1L
            val kind = (item.opt("kind") as? String)?.takeIf { it in KINDS } ?: "other"
            out.put(
                JSONObject().put("id", id)
                    .put("appLabel", (item.opt("appLabel") as? String).orEmpty().take(MAX_LABEL))
                    .put("title", (item.opt("title") as? String).orEmpty().take(MAX_TITLE))
                    .put("text", (item.opt("text") as? String).orEmpty().take(MAX_TEXT))
                    .put("progress", progress).put("chronometerBase", base).put("countdown", item.optBoolean("countdown", false)).put("kind", kind),
            )
        }
        return out
    }

    /** Opaque, stable while nothing shown changes (the clock `at` is not part of it). */
    fun rev(items: JSONArray): String = SyncDigest.rev((0 until items.length()).map { items.getJSONObject(it).toString() })

    fun packet(items: JSONArray, at: Long) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_ACTIVITY).apply {
        this["at"] = at
        this["items"] = items
    }
}
