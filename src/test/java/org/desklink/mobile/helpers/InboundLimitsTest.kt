/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundLimitsTest {
    @Test
    fun inputStreamsAndOtherFeaturesHaveTheirOwnBoundsPerType() {
        var now = 0L
        val limits = InboundLimits.ForDesk("test") { now }
        val motion = (1..InboundLimits.MAX_INPUT_PER_SECOND + 1).count { limits.allow(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST) }
        assertEquals(InboundLimits.MAX_INPUT_PER_SECOND, motion)
        now = 1_000
        assertTrue("a new second", limits.allow(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST))
        val pings = (1..InboundLimits.MAX_FEATURE_PER_MINUTE + 1).count { limits.allow(DeskLinkProtocol.PACKET_TYPE_PING) }
        assertEquals(InboundLimits.MAX_FEATURE_PER_MINUTE, pings)
        assertTrue("other types are counted apart", limits.allow(DeskLinkProtocol.PACKET_TYPE_BATTERY))
        now = 61_000
        assertTrue(limits.allow(DeskLinkProtocol.PACKET_TYPE_PING))
    }

    @Test
    fun crashReportsAreKeptSeparatelyAndBounded() {
        val dir = java.nio.file.Files.createTempDirectory("crash").toFile()
        repeat(8) { i -> CrashReporter.keep(dir, "report $i", now = 1_000L + i) }
        val kept = CrashReporter.reports(dir)
        assertEquals(CrashReporter.MAX_REPORTS, kept.size)
        assertEquals("the newest comes first", "report 7", kept.first().readText())
        dir.deleteRecursively()
    }
}
