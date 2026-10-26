/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.share.SendAgain
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.DeliveredTransfers
import org.desklink.mobile.webrtc.FileCheckpoint
import org.desklink.mobile.webrtc.FileTransferStore
import org.desklink.mobile.webrtc.SyncDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** After an unpair nothing the phone held for that computer is left, and what is another computer's stays. */
@RunWith(RobolectricTestRunner::class)
class PeerStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val desk = "11111111-aaaa-bbbb-cccc-111111111111"
    private val other = "22222222-aaaa-bbbb-cccc-222222222222"

    /** Every per-computer preference key the app writes: `<what>_<deviceId>`. */
    private val prefixes = listOf(
        "glide_allowed_", "type_on_phone_", "mic_consent_", "desk_sound_", "desk_sound_push_", "desk_sound_volume_", "screen_display_",
        "private_notifications_", "key_unreachable_url_list",
    )

    @Test
    fun everyPerDevicePreferenceIsCleared() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit { prefixes.forEach { putBoolean(it + desk, true); putBoolean(it + other, true) }; putBoolean("dynamic_colors", true) }
        PeerState.forget(app, desk)
        val left = prefs.all.keys
        assertEquals("nothing of the unpaired computer", emptyList<String>(), left.filter { desk in it })
        assertTrue("another computer's choices stay", prefixes.all { it + other in left })
        assertTrue("and the phone's own", "dynamic_colors" in left)
    }

    @Test
    fun theDigestKeptTransfersAndSendAgainRecordsAreForgotten() {
        val digest = SyncDigest { "me" }
        digest.record("peer", NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply { this["rev"] = "r1" })
        assertEquals("r1", digest.havePacket("peer").getJSONObject("have")!!.getString("settings"))
        digest.forgetAll()
        assertEquals(0, digest.havePacket("peer").getJSONObject("have")!!.length())

        val id = UUID.randomUUID().toString()
        val store = FileTransferStore(File(app.filesDir, "webrtc-transfers"))
        store.partial(id).writeBytes(ByteArray(10))
        store.save(FileCheckpoint(id, "receive", "t".repeat(32), desk, "a.bin", 10, 1, 0))
        val otherId = UUID.randomUUID().toString()
        store.save(FileCheckpoint(otherId, "receive", "t".repeat(32), other, "b.bin", 10, 1, 0))
        DeliveredTransfers(File(app.filesDir, "webrtc-delivered.json")).add(DeliveredTransfers.Record(id, "t", desk, "a.bin", 10, "h", System.currentTimeMillis()))
        SendAgain.add(app, null, id, desk, "a.bin", true)

        PeerState.forget(app, desk)

        assertEquals(null, store.load(id))
        assertEquals(false, store.partial(id).exists())
        assertEquals("another computer's transfer stays", otherId, store.load(otherId)?.transferId)
        assertEquals(null, DeliveredTransfers(File(app.filesDir, "webrtc-delivered.json")).find(id, "t", desk, null, null))
        assertEquals(emptyList<String>(), SendAgain.list(app).filter { it.deviceId == desk }.map { it.id })
    }

    @Test
    fun aShortIdNeverMatchesByAccident() {
        assertEquals(emptyList<String>(), PeerState.preferenceKeys(listOf("glide_allowed_x", "x"), "x"))
    }

    @Test
    fun aLateSweepAfterAFastRePairLeavesTheNewPairingsChoicesAlone() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        // Unpair began at generation 5; the same computer was paired again (generation 6) and has new consents.
        prefs.edit { putBoolean("glide_allowed_$desk", true) }
        PeerState.forgetLate(app, desk, unpairedGeneration = 5, currentGeneration = 6, paired = true)
        assertTrue("the new pairing's consent survives", prefs.getBoolean("glide_allowed_$desk", false))
        // Not re-paired: the sweep runs.
        PeerState.forgetLate(app, desk, unpairedGeneration = 5, currentGeneration = 5, paired = false)
        assertEquals(false, prefs.contains("glide_allowed_$desk"))
        // Re-paired but the generation did not move (cannot happen), or paired again: still skipped.
        prefs.edit { putBoolean("glide_allowed_$desk", true) }
        PeerState.forgetLate(app, desk, 5, 5, paired = true)
        assertTrue(prefs.getBoolean("glide_allowed_$desk", false))
    }
}
