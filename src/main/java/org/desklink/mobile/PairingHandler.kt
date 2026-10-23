/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bouncycastle.util.Arrays
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.helpers.security.SslHelper
import org.desklink.mobile.R
import java.security.MessageDigest
import java.security.cert.Certificate
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

class PairingHandler(private val device: Device, private val callback: PairingCallback, var state: PairState) {
    enum class PairState {
        NotPaired,
        Requested,
        RequestedByPeer,
        Paired
    }

    interface PairingCallback {
        fun incomingPairRequest()

        fun pairingFailed(error: String)

        fun pairingSuccessful()

        fun unpaired(device: Device)
    }

    private val pairingJob = SupervisorJob()
    private val pairingScope = CoroutineScope(Dispatchers.IO + pairingJob)
    private var pairingTimestamp = 0L

    /** The negotiation a pairing notification was shown for; see [acceptPairing]. */
    val negotiationTimestamp: Long
        get() = pairingTimestamp

    // Serialises the accept/reject/timeout race. The timeout coroutine and a
    // notification tap used to interleave so the phone sent pair:false, then
    // pair:true, and ended up Paired while the desktop had already given up.
    private val stateLock = Any()

    /**
     * The certificate this negotiation is for: the one on the link when the
     * user accepted, or the one whose fingerprint the scanned QR code vouched
     * for. Completion is refused if the link changed since, and it is exactly
     * this certificate that gets pinned — not whatever link happens to be
     * first when the confirmation lands.
     */
    @Volatile
    var negotiatedCertificate: Certificate? = null
        private set
    private var lastNotPairedNotice = 0L

    // Whether this device takes the leading role against the peer.
    //
    // The same comparison the desktop makes in `device_links::local_goes_first`,
    // and the same one the WebRTC coordinator uses to pick an initiator. It
    // must stay identical on both sides: two role elections that can disagree
    // is a permanent flap, not a race that resolves.
    private fun localGoesFirst(): Boolean =
        DeviceHelper.getDeviceId(device.context) < device.deviceId

    // Starts, or restarts, the timeout for the negotiation in flight.
    //
    // One value for both roles — the desktop uses a single `PAIR_TIMEOUT_SECS`
    // for the same reason. Expiry now tells the peer, so the two ends no longer
    // need their timeouts ordered against each other to avoid both blaming the
    // other when they give up in the same second.
    private fun armTimer() {
        cancelTimer()
        pairingScope.launch {
            delay(pairTimeoutSeconds.seconds)
            val expired = synchronized(stateLock) {
                val negotiating = state == PairState.Requested || state == PairState.RequestedByPeer
                if (negotiating) this@PairingHandler.state = PairState.NotPaired
                negotiating
            }
            if (!expired) return@launch
            Log.w("PairingHandler", "Pairing timed out")
            // Tell the peer rather than failing silently, so its prompt is
            // withdrawn instead of being left on screen.
            sendPairFalse()
            callback.pairingFailed(device.context.getString(R.string.error_timed_out))
        }
    }

    // Sends `pair:false` over the bootstrap link, if there is one.
    //
    // Deliberately asks `hasBootstrapLink` rather than `isReachable`.
    // `isReachable` is also true when only the WebRTC feature transport is up,
    // but `desklink.pair` is a bootstrap-only packet: it skips the WebRTC
    // branch in `sendPacketBlocking` and falls through to an empty link list,
    // where the send is dropped and **no callback ever fires**. The guard read
    // as "the peer is there" while the packet went nowhere.
    private fun sendPairFalse() {
        if (!device.hasBootstrapLink) {
            Log.i("PairingHandler", "No bootstrap link for pair:false; the peer will be told when it next sends anything")
            return
        }
        val np = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
        np["pair"] = false
        device.sendPacket(np)
    }

    fun packetReceived(np: NetworkPacket) {
        cancelTimer()
        val wantsPair = np.getBoolean("pair")
        if (wantsPair) {
            // A *request* carries a timestamp; a plain acceptance does not.
            // That distinction already breaks the acknowledgement loop below,
            // and it is what makes a crossing request recognisable.
            val incomingTimestamp = np.getLong("timestamp", -1L)
            when (state) {
                PairState.Requested -> {
                    if (incomingTimestamp == -1L) {
                        // The peer answered us: the ordinary happy path.
                        pairingDone()
                    } else if (localGoesFirst()) {
                        // Both sides asked at once and this one leads. Its
                        // request stands; the peer will prompt when it arrives.
                        Log.i("PairingHandler", "Ignoring a crossing pair request; this device leads")
                        armTimer()
                    } else {
                        // This side follows: abandon our own request and adopt
                        // the leader's negotiation, timestamp included.
                        //
                        // Treating a crossing request as an acceptance — which
                        // is what this did — completed the pairing with neither
                        // user ever shown a verification key, throwing away the
                        // only defence against a man in the middle while TLS
                        // still trusts anything. It also left each side keyed
                        // off its own timestamp, so the two screens showed
                        // different codes.
                        Log.i("PairingHandler", "Adopting the peer's crossing pair request; this device follows")
                        pairingTimestamp = incomingTimestamp
                        state = PairState.RequestedByPeer
                        armTimer()
                        callback.incomingPairRequest()
                    }
                }
                PairState.RequestedByPeer -> {
                    // Rearm. `cancelTimer()` runs at the top of this method,
                    // before the state is even examined, so returning here left
                    // the 25s prompt with **no timer at all** — a peer that
                    // merely resent its request made the prompt hang forever.
                    Log.w(
                        "PairingHandler",
                        "Ignoring second pairing request before the first one timed out"
                    )
                    armTimer()
                }

                PairState.Paired -> {
                    // A request from a device this side already trusts is a
                    // re-confirmation, not a new negotiation.
                    //
                    // This used to unpair — dropping the trusted-device record
                    // and certificate — and then re-enter as a fresh incoming
                    // request. The desktop client keeps its trust in the same
                    // situation, so the two ends ended up permanently
                    // disagreeing about whether they were paired: the phone had
                    // forgotten the desktop while the desktop still trusted the
                    // phone.
                    //
                    // Only a *request* is answered. From protocol v8 onwards a
                    // request carries a `timestamp` and a plain acceptance does
                    // not, so the two are distinguishable — and testing for it
                    // is what makes this terminate. Replying to every
                    // `pair: true` would reply to the peer's acknowledgement and
                    // ping-pong forever, which is the acknowledgement loop the
                    // pre-v8 protocol could not avoid and the original reason
                    // this case unpaired instead of replying.
                    if (incomingTimestamp == -1L) {
                        Log.i("PairingHandler", "Ignoring a pair acknowledgement; already paired")
                    } else {
                        Log.i(
                            "PairingHandler",
                            "Re-confirming an existing pairing instead of unpairing",
                        )
                        val acknowledgement = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
                        acknowledgement["pair"] = true
                        device.sendPacket(acknowledgement)
                    }
                }

                PairState.NotPaired -> {
                    if (device.protocolVersion >= 8) {
                        if (incomingTimestamp == -1L) {
                            // Drop it, and **do not tear anything down**. This
                            // used to call `unpaired(device)`, whose teardown
                            // does `edit { clear() }` on the whole per-device
                            // preferences file — so a single malformed packet,
                            // from an unauthenticated peer before any pairing
                            // existed, wiped this device's stored state.
                            Log.w("PairingHandler", "Ignoring a pair request with no timestamp")
                            return
                        }
                        val currentTimestamp = System.currentTimeMillis() / 1000L
                        if (abs(incomingTimestamp - currentTimestamp) > allowedTimestampDifferenceSeconds) {
                            Log.w("PairingHandler", "Ignoring a pair request with an implausible timestamp")
                            callback.pairingFailed(device.context.getString(R.string.error_clocks_not_match))
                            return
                        }
                        pairingTimestamp = incomingTimestamp
                    }

                    state = PairState.RequestedByPeer
                    armTimer()
                    callback.incomingPairRequest()
                }
            }
        } else {
            Log.i("PairingHandler", "Unpair request received")
            when (state) {
                PairState.NotPaired -> Log.i("PairingHandler", "Ignoring unpair request for already unpaired device")
                // Requested: We started pairing and got rejected
                // RequestedByPeer: They stared pairing, then cancelled
                PairState.Requested, PairState.RequestedByPeer -> {
                    // A refused QR proof is, in practice, a code that was replaced (it rotates after a few minutes).
                    val refusedProof = proofSent && state == PairState.Requested
                    state = PairState.NotPaired
                    proofSent = false
                    callback.pairingFailed(device.context.getString(if (refusedProof) R.string.qr_error_expired else R.string.error_canceled_by_other_peer))
                }

                PairState.Paired -> {
                    state = PairState.NotPaired
                    callback.unpaired(device)
                }
            }
        }
    }

    fun verificationKey(): String? {
        return if (device.protocolVersion >= 8) {
            if (state != PairState.Requested && state != PairState.RequestedByPeer) {
                return null
            } else {
                getVerificationKey(SslHelper.certificate, device.certificate, pairingTimestamp)
            }
        } else {
            getVerificationKeyV7(SslHelper.certificate, device.certificate)
        }
    }

    fun requestPairing() {
        cancelTimer()

        if (state == PairState.Paired) {
            Log.w("PairingHandler", "requestPairing was called on an already paired device")
            callback.pairingFailed(device.context.getString(R.string.error_already_paired))
            return
        }

        if (state == PairState.RequestedByPeer) {
            Log.w("PairingHandler", "Pairing already started by the other end, accepting their request.")
            acceptPairing()
            return
        }

        if (!device.isReachable) {
            callback.pairingFailed(device.context.getString(R.string.error_not_reachable))
            return
        }

        // Re-sending a request must not restamp the negotiation. A peer already
        // sitting in RequestedByPeer ignores the duplicate and keeps the
        // original timestamp, so a fresh one here leaves the two users comparing
        // different 8-character keys for the same pairing.
        if (state != PairState.Requested) {
            pairingTimestamp = System.currentTimeMillis() / 1000L
        }
        state = PairState.Requested
        armTimer()

        val statusCallback: Device.SendPacketStatusCallback = object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {}

            override fun onFailure(e: Throwable) {
                cancelTimer()
                Log.e("PairingHandler", "Exception sending pairing request", e)
                this@PairingHandler.state = PairState.NotPaired
                callback.pairingFailed(device.context.getString(R.string.device_not_reachable))
            }
        }
        val np = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
        np["pair"] = true
        np["timestamp"] = pairingTimestamp
        device.sendPacket(np, statusCallback)
    }

    /**
     * Accepts the peer's pending request — only that, and only the negotiation
     * the user was shown when [expectedTimestamp] is given. Without the guard a
     * tap (or any app firing the old exported intent) paired a device in any
     * state, including one that had never asked.
     */
    fun acceptPairing(expectedTimestamp: Long? = null) {
        synchronized(stateLock) {
            if (state != PairState.RequestedByPeer) {
                Log.w("PairingHandler", "Ignoring accept: no pairing request is pending")
                return
            }
            if (expectedTimestamp != null && expectedTimestamp != pairingTimestamp) {
                Log.w("PairingHandler", "Ignoring accept for a superseded pairing request")
                return
            }
            negotiatedCertificate = device.certificate
            cancelTimer()
        }
        val stateCallback = object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                pairingDone()
            }

            override fun onFailure(e: Throwable) {
                Log.e("PairingHandler", "Exception sending accept pairing packet", e)
                this@PairingHandler.state = PairState.NotPaired
                callback.pairingFailed(device.context.getString(R.string.error_not_reachable))
            }
        }
        val np = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
        np["pair"] = true
        device.sendPacket(np, stateCallback)
    }

    /**
     * QR pairing: send the scanned code's proof instead of a prompt request.
     * The desktop answers with a plain `pair:true` (handled by the Requested
     * branch of [packetReceived], completing via [pairingDone]) or `pair:false`.
     */
    /** A QR proof was sent and has not been answered yet: a refusal then means the code is no good any more. */
    @Volatile private var proofSent = false

    fun requestPairingWithProof(mac: String, verifiedCertificate: Certificate) {
        proofSent = true
        synchronized(stateLock) {
            // Already paired with this very desktop: still send the proof, so a
            // desktop that lost its record is repaired, but leave our state.
            if (state != PairState.Paired) {
                negotiatedCertificate = verifiedCertificate
                state = PairState.Requested
                armTimer()
            }
        }
        val np = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_PAIR_QR)
        np["mac"] = mac
        device.sendPacket(np, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                synchronized(stateLock) { state = PairState.NotPaired }
                cancelTimer()
                callback.pairingFailed(device.context.getString(R.string.error_not_reachable))
            }
        })
    }

    /** A link with a different certificate replaced the one being negotiated on. */
    fun abandonNegotiation() {
        val abandoned = synchronized(stateLock) {
            val negotiating = state == PairState.Requested || state == PairState.RequestedByPeer
            if (negotiating) {
                cancelTimer()
                state = PairState.NotPaired
            }
            negotiating
        }
        if (abandoned) {
            device.hidePairingNotification()
            callback.pairingFailed(device.context.getString(R.string.error_canceled_by_other_peer))
        }
    }

    /** Rejects the pending request the notification was shown for, if still pending. */
    fun rejectPairingRequest(expectedTimestamp: Long) {
        synchronized(stateLock) {
            if (state != PairState.RequestedByPeer || expectedTimestamp != pairingTimestamp) return
        }
        cancelPairing()
    }

    fun cancelPairing() {
        cancelTimer()
        val wasPaired = state == PairState.Paired
        state = PairState.NotPaired
        sendPairFalse()
        // Rejecting a device this side already trusted must drop the persisted
        // record too, or it comes back paired on the next reconnect and the
        // user's "no" is silently undone. Reject and unpair differ in wording,
        // not in effect — the desktop's `reject_pair` was fixed the same way.
        if (wasPaired) {
            callback.unpaired(device)
        }
        callback.pairingFailed(device.context.getString(R.string.error_canceled_by_user))
    }

    @VisibleForTesting
    fun pairingDone() {
        proofSent = false
        val linkUnchanged = synchronized(stateLock) {
            val expected = negotiatedCertificate
            val unchanged = expected == null ||
                runCatching { device.certificate == expected }.getOrDefault(false)
            if (!unchanged) {
                state = PairState.NotPaired
                negotiatedCertificate = null
            }
            unchanged
        }
        if (!linkUnchanged) {
            // The link was replaced between the approval and its confirmation:
            // pinning now could pin someone else (CVE-2020-26164's pattern).
            Log.w("PairingHandler", "Refusing to complete pairing: the link changed")
            callback.pairingFailed(device.context.getString(R.string.error_canceled_by_other_peer))
            return
        }
        Log.i("PairingHandler", "Pairing done")
        state = PairState.Paired
        kotlin.runCatching {
            callback.pairingSuccessful()
        }.onFailure { e ->
            Log.e("PairingHandler", "Exception in pairingSuccessful callback, unpairing", e)
            state = PairState.NotPaired
        }
    }

    /**
     * Tells a peer that still believes it is paired that this side is not.
     *
     * This is the self-heal for the case where one device unpaired while the
     * other was unreachable. It cannot be expressed with [unpair]: that returns
     * early when already `NotPaired` — which is what makes it idempotent — so it
     * never transmits in exactly the situation this exists for. It also must not
     * be confused with cancelling a pairing that is genuinely in progress, so
     * the caller is responsible for only invoking it in `NotPaired`.
     */
    fun notifyPeerNotPaired() {
        if (state != PairState.NotPaired || !device.isReachable) return
        // Damped. This is driven by inbound feature packets, and a peer that
        // still believes it is paired can deliver a burst of them before it
        // processes the first reply — without this, one burst queues one
        // `pair:false` per packet.
        val now = System.currentTimeMillis()
        if (now - lastNotPairedNotice < notPairedNoticeIntervalMillis) return
        lastNotPairedNotice = now

        Log.i("PairingHandler", "Telling a peer that we are no longer paired")
        val np = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
        np["pair"] = false
        device.sendPacket(np)
    }

    fun unpair() {
        if (state == PairState.NotPaired) {
            cancelTimer()
            Log.i("PairingHandler", "Ignoring duplicate local unpair request")
            return
        }
        state = PairState.NotPaired
        cancelTimer()
        sendPairFalse()
        // The local revocation commits regardless of whether the peer heard.
        // If it did not, it keeps behaving as paired until it next sends a
        // feature packet, which `notifyPeerNotPaired` answers with `pair:false`.
        // That self-heal is why there is no retry queue here.
        callback.unpaired(device)
    }

    private fun cancelTimer() {
        pairingJob.cancelChildren()
    }

    companion object {
        /**
         * How long a pairing negotiation waits, in either direction.
         *
         * One value for both roles, matching the desktop's `PAIR_TIMEOUT_SECS`
         * and the 30s the protocol documents. The old 25s-incoming / 30s-outgoing
         * split existed only because expiry was silent: if both sides gave up in
         * the same second, each reported that the other had failed. `armTimer`
         * now sends `pair:false`, so the ordering trick is no longer load-bearing.
         */
        private const val pairTimeoutSeconds = 30

        private const val allowedTimestampDifferenceSeconds = 1_800 // 30 minutes
        private const val notPairedNoticeIntervalMillis = 5_000L

        // Concatenate in a deterministic order so on both devices the result is the same
        private fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray {
            return if (Arrays.compareUnsigned(a, b) < 0) {
                b + a
            } else {
                a + b
            }
        }

        /**
         * Six digits both screens show: SHA-256 over the two keys (larger
         * first), then the timestamp in decimal ASCII when there is one; the
         * first four digest bytes, big-endian, mod 10^6, zero-padded. Pinned
         * on the desktop too (`pairing.rs`, `verification_key`).
         */
        internal fun verificationCode(a: ByteArray, b: ByteArray, timestamp: Long?): String {
            val input = sortedConcat(a, b) + (timestamp?.toString()?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0))
            val hash = MessageDigest.getInstance("SHA-256").digest(input)
            val value = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xFFFFFFFFL
            return String.format(Locale.ROOT, "%06d", value % 1_000_000)
        }

        fun getVerificationKey(certificateA: Certificate, certificateB: Certificate, timestamp: Long): String =
            verificationCode(certificateA.publicKey.encoded, certificateB.publicKey.encoded, timestamp)

        fun getVerificationKeyV7(certificateA: Certificate, certificateB: Certificate): String =
            verificationCode(certificateA.publicKey.encoded, certificateB.publicKey.encoded, null)
    }

}
