/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import org.desklink.mobile.DeviceInfo
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Ephemeral
import org.desklink.mobile.backends.relay.RelayLinkCrypto.LinkKeys
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Role
import org.json.JSONObject
import java.security.PrivateKey
import java.security.PublicKey

/**
 * One peer's end-to-end relay link: the handshake, then sealed frames.
 *
 * Pure state — messages in, messages and events out — so both roles are tested
 * against each other without a relay or a socket. Mirrors the desktop's
 * `relay_link/handshake.rs`; see [RelayLinkCrypto] and docs/RELAY.md §2. Not
 * thread-safe: the owner serializes calls.
 */
class RelayLinkSession(private val ids: Identities) {
    /** Who we are and who the peer must prove to be. */
    class Identities(
        val localFp: String,
        val peerFp: String,
        val localKey: PrivateKey,
        /** Our identity packet, one JSON line without its newline. */
        val localIdentity: String,
        /** The peer's key from its *stored pairing certificate*. */
        val peerKey: PublicKey,
        /** The device id the stored pairing names; the peer's identity must match. */
        val peerDeviceId: String,
    )

    sealed class Event {
        /** Send this JSON to the peer through the relay. */
        data class Send(val message: JSONObject) : Event()

        /** The link is up; [identity] is the peer's identity JSON, already checked to name the paired device. */
        data class Established(val identity: String) : Event()

        /** One decrypted DeskLink packet from the peer. */
        class Packet(val bytes: ByteArray) : Event()
    }

    private sealed class State {
        object Idle : State()
        class AwaitingH2(val mine: Ephemeral) : State()
        class AwaitingFin(val keys: LinkKeys, val transcript: ByteArray, val identity: String) : State()
        class Established(val keys: LinkKeys) : State()
    }

    val role: Role = if (RelayLinkCrypto.isInitiator(ids.localFp, ids.peerFp)) Role.INITIATOR else Role.RESPONDER
    private var state: State = State.Idle
    private var sendSeq = 0L
    private var receiveSeq = 0L

    val isEstablished: Boolean get() = state is State.Established

    /** Forgets the session, e.g. when the peer went offline. */
    fun reset() {
        state = State.Idle
        sendSeq = 0
        receiveSeq = 0
    }

    /** Begins (or restarts) a handshake: the initiator sends `h1`, the responder asks for one with `kick`. */
    fun start(): JSONObject {
        reset()
        if (role == Role.RESPONDER) return JSONObject().put("k", "kick")
        val mine = Ephemeral.generate()
        state = State.AwaitingH2(mine)
        return JSONObject()
            .put("k", "h1")
            .put("lv", RelayLinkCrypto.LINK_VERSION)
            .put("eph", RelayLinkCrypto.b64(mine.public))
            .put("nonce", RelayLinkCrypto.b64(mine.nonce))
            .put("identity", ids.localIdentity)
    }

    /** Seals one packet for the peer. */
    fun seal(plaintext: ByteArray): JSONObject {
        val established = state as? State.Established ?: error("Relay link is not established")
        sendSeq += 1
        return JSONObject()
            .put("k", "d")
            .put("seq", sendSeq)
            .put("ct", RelayLinkCrypto.seal(established.keys.send, sendSeq, plaintext))
    }

    /**
     * Handles one message from the peer. A throw means the session is broken;
     * the owner restarts it (rate-limited) with [start].
     */
    fun receive(message: JSONObject): List<Event> {
        val kind = message.optString("k")
        return when {
            // A handshake already in flight answers the kick; restarting it would
            // cross the responder's h2 to the first h1 and livelock.
            kind == "kick" && role == Role.INITIATOR && state is State.AwaitingH2 -> emptyList()
            kind == "kick" && role == Role.INITIATOR -> listOf(Event.Send(start()))
            kind == "h1" && role == Role.RESPONDER -> onH1(message)
            kind == "h2" && role == Role.INITIATOR -> onH2(message)
            kind == "fin" && role == Role.RESPONDER -> onFin(message)
            kind == "d" -> onData(message)
            else -> throw IllegalArgumentException("Unexpected relay link message")
        }
    }

    private fun onH1(message: JSONObject): List<Event> {
        reset()
        checkVersion(message)
        val peerEph = decode(message, "eph")
        val peerNonce = decode(message, "nonce")
        val identity = checkedIdentity(message)
        val mine = Ephemeral.generate()
        val keys = RelayLinkCrypto.deriveKeys(mine, peerEph, Role.RESPONDER, ids.peerFp, ids.localFp, peerNonce, mine.nonce)
        val transcript = RelayLinkCrypto.transcript(
            ids.peerFp, ids.localFp, peerEph, mine.public, peerNonce, mine.nonce, identity, ids.localIdentity,
        )
        val h2 = JSONObject()
            .put("k", "h2")
            .put("lv", RelayLinkCrypto.LINK_VERSION)
            .put("eph", RelayLinkCrypto.b64(mine.public))
            .put("nonce", RelayLinkCrypto.b64(mine.nonce))
            .put("identity", ids.localIdentity)
            .put("sig", RelayLinkCrypto.sign(ids.localKey, Role.RESPONDER, transcript))
        state = State.AwaitingFin(keys, transcript, identity)
        return listOf(Event.Send(h2))
    }

    private fun onH2(message: JSONObject): List<Event> {
        val awaiting = state as? State.AwaitingH2 ?: throw IllegalArgumentException("Unexpected relay link h2")
        state = State.Idle
        checkVersion(message)
        val mine = awaiting.mine
        val peerEph = decode(message, "eph")
        val peerNonce = decode(message, "nonce")
        val identity = checkedIdentity(message)
        val transcript = RelayLinkCrypto.transcript(
            ids.localFp, ids.peerFp, mine.public, peerEph, mine.nonce, peerNonce, ids.localIdentity, identity,
        )
        require(RelayLinkCrypto.verify(ids.peerKey, Role.RESPONDER, transcript, text(message, "sig"))) {
            "Relay link peer failed to prove its pairing key"
        }
        val keys = RelayLinkCrypto.deriveKeys(mine, peerEph, Role.INITIATOR, ids.localFp, ids.peerFp, mine.nonce, peerNonce)
        val fin = JSONObject()
            .put("k", "fin")
            .put("sig", RelayLinkCrypto.sign(ids.localKey, Role.INITIATOR, transcript))
        state = State.Established(keys)
        return listOf(Event.Send(fin), Event.Established(identity))
    }

    private fun onFin(message: JSONObject): List<Event> {
        val awaiting = state as? State.AwaitingFin ?: throw IllegalArgumentException("Unexpected relay link fin")
        state = State.Idle
        require(RelayLinkCrypto.verify(ids.peerKey, Role.INITIATOR, awaiting.transcript, text(message, "sig"))) {
            "Relay link peer failed to prove its pairing key"
        }
        state = State.Established(awaiting.keys)
        return listOf(Event.Established(awaiting.identity))
    }

    private fun onData(message: JSONObject): List<Event> {
        val established = state as? State.Established
            ?: throw IllegalArgumentException("Relay link data before the handshake")
        val seq = message.optLong("seq", 0)
        // Strictly increasing: the relay delivers in order, so a repeat or a
        // reordering can only be a replay.
        require(seq > receiveSeq) { "Replayed relay frame" }
        val plaintext = RelayLinkCrypto.open(established.keys.receive, seq, text(message, "ct"))
        receiveSeq = seq
        return listOf(Event.Packet(plaintext))
    }

    /** The peer's identity JSON, after checking it names the paired device. */
    private fun checkedIdentity(message: JSONObject): String {
        val identity = text(message, "identity")
        val packet = runCatching { NetworkPacket.unserialize(identity) }
            .getOrElse { throw IllegalArgumentException("Relay link identity is not a packet") }
        require(DeviceInfo.isValidIdentityPacket(packet)) { "Relay link identity is invalid" }
        require(packet.getString("deviceId") == ids.peerDeviceId) { "Relay link identity names another device" }
        return identity
    }

    private fun checkVersion(message: JSONObject) =
        require(message.optInt("lv", -1) == RelayLinkCrypto.LINK_VERSION) { "Unsupported relay link version" }

    private fun text(message: JSONObject, key: String): String =
        message.optString(key).takeIf { message.has(key) && it.isNotEmpty() }
            ?: throw IllegalArgumentException("Relay link message has no $key")

    private fun decode(message: JSONObject, key: String): ByteArray =
        runCatching { RelayLinkCrypto.unb64(text(message, key)) }
            .getOrElse { throw IllegalArgumentException("Relay link $key is not base64") }
}
