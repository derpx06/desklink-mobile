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

    // Loops that wait with a coroutine delay, and Handlers that post themselves again: the timers the first test cannot see.
    private val delayLoop = Regex("""while \((true|[^)]*)\)\s*\{.{0,500}?\bdelay\(""", RegexOption.DOT_MATCHES_ALL)
    private val selfPosting = Regex("""postDelayed\(\s*(this|[A-Za-z_]*(Task|Tick|Poll|Runnable|Refresh|Check)[A-Za-z_]*)\s*,""")

    private val allowedLoops = mapOf(
        "plugins/mousepad/MousePadActivity.kt" to "the control retry (stops after a refusal) and the controls auto-hide; only while the screen view is started",
        "plugins/terminal/TerminalActivity.kt" to "list refresh and preview scrape; StartedEffect, only while the terminal screen is started",
        "ui/MainActivity.kt" to "the 1 Hz UI tick inside repeatOnLifecycle(STARTED), and the 20 s QR proof wait",
        "ui/compose/components/Primitives.kt" to "connection stats poll; StartedEffect, only while on screen",
        "ui/compose/screen/home/HomeMediaUi.kt" to "the seek bar; StartedEffect and only while the media is playing",
        "ui/compose/screen/tabs/TabScreens.kt" to "terminal list refresh and previews; StartedEffect, only while the tab is on screen",
        "BackgroundService.kt" to "the work-lease recheck: re-arms only while work holds the lease, and the lease lapses on its own",
        "Device.kt" to "the doze/peer expiry handler: armed only for a live link",
        "plugins/findmyphone/FlashlightManager.kt" to "the flash blink, only while the phone is ringing (60 s limit)",
        "plugins/mprisreceiver/MprisReceiverPlugin.java" to "position ticks, only while media plays",
    )

    @Test
    fun onlyTheFilesWithAReasonLoopOnADelayOrRepostAHandler() {
        val root = File("src/main/java")
        val found = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { val t = it.readText(); delayLoop.containsMatchIn(t) || selfPosting.containsMatchIn(t) }
            .map { it.relativeTo(File(root, "org/desklink/mobile")).path }
            .toSortedSet()
        assertEquals("a delay loop or self-posting Handler was added or removed: update the list with its reason", allowedLoops.keys.toSortedSet(), found)
    }

    /** The text of every `LaunchedEffect(...) { ... }` block of [source] (braces matched), with where it starts. */
    private fun launchedEffects(source: String): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var from = 0
        while (true) {
            val at = source.indexOf("LaunchedEffect(", from)
            if (at < 0) break
            from = at + 1
            val open = source.indexOf('{', at)
            if (open < 0) continue
            var depth = 0
            var i = open
            while (i < source.length) {
                if (source[i] == '{') depth++ else if (source[i] == '}') { depth--; if (depth == 0) break }
                i++
            }
            found += source.substring(at, minOf(i + 1, source.length)).let { at to it }
        }
        return found
    }

    // A poll loop in a LaunchedEffect keeps running behind another app unless it is tied to the lifecycle (StartedEffect wraps
    // repeatOnLifecycle). These loops are the ones that are not, each with why.
    private val loopsAllowedUngated = mapOf(
        "plugins/mousepad/MousePadActivity.kt" to "the control retry: ends at the first grant or refusal, and the whole screen is gone when the activity stops",
    )

    @Test
    fun aPollLoopInAComposableIsTiedToTheLifecycle() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val relative = file.relativeTo(File(root, "org/desklink/mobile")).path
            launchedEffects(file.readText()).filter { (_, block) ->
                Regex("""while \(""").containsMatchIn(block) && block.contains("delay(") &&
                    !block.contains("repeatOnLifecycle") && !block.contains("collectAsStateWithLifecycle")
            }.map { relative }
        }.toSortedSet()
        assertEquals("a LaunchedEffect with a delay loop must be a StartedEffect (or be listed with its reason)", loopsAllowedUngated.keys.toSortedSet(), offenders)
    }
}
