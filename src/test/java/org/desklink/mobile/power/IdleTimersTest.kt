/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.power

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * W7: with the screen off the phone does nothing but its heartbeats. A periodic timer is where that is lost, so each file that
 * may start one is named here with the reason it may. A new one fails this test until it is added with its reason, which is
 * the question to answer: what stops it when nothing is going on?
 */
class IdleTimersTest {
    // Any periodic scheduling: an executor's fixed rate or delay, and a Timer (written out or imported).
    private val periodic = Regex("""scheduleWithFixedDelay|scheduleAtFixedRate|\bTimer\(""")

    private val allowed = mapOf(
        "webrtc/WebRtcSessionCoordinator.kt" to "the link's heartbeat and liveness probes: the one thing that runs while connected",
        "webrtc/WebRtcTransport.kt" to "debug-only stats logs: each starts with the first decoded frame (or a screen share) and ends two polls after the pictures stop, so nothing polls getStats while idle",
        "plugins/folders/BucketEngine.kt" to "the Bucket's tick: returns at once when there is no Bucket, link or offer, and rescans rarely with the screen off",
        "webrtc/WebRtcFileTransferManager.kt" to "ThreadFileRunner.every, unused by the manager: its clock runs through tickWhile, only while a transfer is live",
    )

    @Test
    fun onlyTheFilesWithAReasonStartPeriodicTimers() {
        val root = File("src/main/java")
        val found = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { periodic.containsMatchIn(it.readText()) }
            .map { it.relativeTo(File(root, "org/desklink/mobile")).path }
            .toSortedSet()
        assertEquals("a periodic timer was added or removed: update the list with its reason", allowed.keys.toSortedSet(), found)
    }
}
