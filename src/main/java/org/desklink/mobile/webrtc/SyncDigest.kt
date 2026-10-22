/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Delta resync (W2). On every feature-ready both sides used to send their whole state. Now each
 * snapshot carries an opaque `rev` the sender chose; the receiver remembers the last `rev` it fully
 * applied, per feature, and tells the sender in `desklink.sync.have` as the link comes up. The
 * sender then skips the features the receiver already holds. Revs live in memory only: a restart of
 * either process is a new epoch ([myInstance] / the peer's instance id) and everything is sent.
 *
 * Wire: docs in stageD-protocol.md, "W2 — connection".
 */
class SyncDigest(private val myInstance: () -> String) {
    /** What this side has applied from the peer, by the peer's instance id then feature. */
    private val received = HashMap<String, MutableMap<String, String>>()

    /** What the peer says it holds of ours, for the current link; null until its `have` arrives. */
    private var peerHave: Map<String, String>? = null
    private var peerHaveInstance: String? = null
    private val waiting = ArrayList<() -> Unit>()
    private var linkGeneration = 0

    /**
     * The connection ended or was replaced: a `have` belongs to one connection only, so a stale one
     * can never suppress a snapshot the next connection needs. (A `have` that arrives a moment before
     * this side's own feature-ready is kept: nothing ends the connection in between.)
     */
    @Synchronized
    fun endLink() {
        peerHave = null
        peerHaveInstance = null
        waiting.clear()
        linkGeneration++
    }

    /**
     * Runs [action] once: at once if the peer's `have` is already here, else when it arrives, else
     * after [timeoutMs] (scheduled through [schedule]) with everything to be sent. Never blocks.
     */
    fun whenHave(timeoutMs: Long, schedule: (Long, Runnable) -> Unit, action: () -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val generation = synchronized(this) { linkGeneration }
        // A wait that outlives its connection does nothing: the next connection has its own feature-ready.
        val run = { if (synchronized(this) { linkGeneration == generation } && once.compareAndSet(false, true)) action() }
        val ready = synchronized(this) {
            if (peerHave != null) true else {
                waiting.add(run)
                false
            }
        }
        if (ready) return run()
        schedule(timeoutMs, Runnable { run() })
    }

    /** The peer's `have`. Ignored when it is malformed. */
    fun onHave(packet: NetworkPacket) {
        val instance = packet.getStringOrNull("peerInstance")?.takeIf { it.length <= MAX_REV_LENGTH } ?: return
        val body = packet.getJSONObject("have") ?: return
        val parsed = parseHave(body) ?: return
        val callbacks = synchronized(this) {
            peerHave = parsed
            peerHaveInstance = instance
            waiting.toList().also { waiting.clear() }
        }
        callbacks.forEach { it() }
    }

    /**
     * Whether the snapshot of [feature] with [rev] must be sent: unless the peer has told us it holds
     * exactly this one, for this process of ours.
     */
    @Synchronized
    fun shouldSend(feature: String, rev: String): Boolean {
        val have = peerHave ?: return true
        if (peerHaveInstance != myInstance()) return true // it saw an older process of ours
        return have[feature] != rev
    }

    /**
     * [packet] of the peer's process [peerInstance] was fully applied by a plugin: remember its
     * `rev`. Only features that arrive as one self-contained packet are remembered ([REPORTED]); notes
     * and snippets (an index that is followed by a fetch round) and anything multi-part are never
     * reported, so the desk simply sends them again.
     */
    @Synchronized
    fun record(peerInstance: String?, packet: NetworkPacket) {
        val instance = peerInstance ?: return
        val feature = featureOf(packet)?.takeIf { it in REPORTED } ?: return
        val rev = packet.getStringOrNull("rev")?.takeIf { it.isNotEmpty() && it.length <= MAX_REV_LENGTH } ?: return
        if (received.size >= MAX_PEERS && instance !in received) received.clear()
        received.getOrPut(instance) { HashMap() }[feature] = rev
    }

    /** The `have` for the current peer process; revs from any other process of it are not offered. */
    @Synchronized
    fun havePacket(peerInstance: String?): NetworkPacket = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE).apply {
        this["peerInstance"] = peerInstance.orEmpty()
        this["have"] = JSONObject().also { body ->
            received[peerInstance].orEmpty().entries.take(MAX_FEATURES).forEach { body.put(it.key, it.value) }
        }
    }

    companion object {
        const val SYNC_WAIT_MS = 1_000L
        const val MAX_REV_LENGTH = 64
        const val MAX_FEATURES = 8
        const val MAX_FEATURE_LENGTH = 32
        private const val MAX_PEERS = 8

        /** The desk's features this phone reports in its `have`. */
        val REPORTED = setOf("settings", "media")

        const val NOTIFICATIONS = "notifications"
        const val CONTACTS = "contacts"
        const val MEDIA = "media"
        const val NOTES = "notes"
        const val SNIPPETS = "snippets"
        const val SETTINGS = "settings"

        /** The digested feature a snapshot packet belongs to, or null. */
        fun featureOf(packet: NetworkPacket): String? = when (packet.type) {
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION -> NOTIFICATIONS.takeIf { packet.getBoolean("snapshot", false) }
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS -> CONTACTS
            DeskLinkProtocol.PACKET_TYPE_MPRIS -> MEDIA
            DeskLinkProtocol.PACKET_TYPE_NOTES -> NOTES
            DeskLinkProtocol.PACKET_TYPE_SNIPPETS -> SNIPPETS
            DeskLinkProtocol.PACKET_TYPE_SETTINGS -> SETTINGS
            else -> null
        }

        /** Pure: a `have` body, or null when it has too many entries or an oversized key or value. */
        fun parseHave(body: JSONObject): Map<String, String>? {
            if (body.length() > MAX_FEATURES) return null
            val out = HashMap<String, String>()
            for (key in body.keys()) {
                val value = body.opt(key) as? String ?: return null
                if (key.isEmpty() || key.length > MAX_FEATURE_LENGTH || value.length > MAX_REV_LENGTH) return null
                out[key] = value
            }
            return out
        }

        /** An opaque rev: a SHA-256 prefix of [parts], separated unambiguously. */
        fun rev(parts: Iterable<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
