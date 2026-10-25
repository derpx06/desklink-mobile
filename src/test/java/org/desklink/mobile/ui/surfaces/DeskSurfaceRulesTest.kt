/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.ui.surfaces

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeskSurfaceRulesTest {
    private fun desk(ready: Boolean = true, keepAwake: Boolean? = false, pending: Boolean = false, allowed: Boolean = true) = DeskSnapshot(
        id = "d", name = "arch-desk", link = DeskSurfaceRules.link(ready, reachable = true), ready = ready,
        keepAwake = keepAwake, keepAwakePending = pending, keepAwakeAllowed = allowed, terminals = 0, terminalAttached = false,
        unseenFinished = 0, mediaTitle = null, mediaPlaying = false, lastSeen = null,
    )

    private fun tile(kind: TileKind, desk: DeskSnapshot?) = DeskSurfaceRules.tile(kind, desk, "Not connected", "Saving…")

    @Test
    fun actionTilesAreUsableOnlyWhileTheDeskIsReady() {
        for (kind in listOf(TileKind.LOCK, TileKind.RING, TileKind.CLIPBOARD)) {
            assertEquals(TileUi(Tile.STATE_INACTIVE, "arch-desk"), tile(kind, desk()))
            assertEquals(TileUi(Tile.STATE_UNAVAILABLE, "Not connected"), tile(kind, desk(ready = false)))
            assertEquals(TileUi(Tile.STATE_UNAVAILABLE, "Not connected"), tile(kind, null))
        }
    }

    @Test
    fun keepAwakeFollowsTheDesksOwnValue() {
        assertEquals(TileUi(Tile.STATE_ACTIVE, "arch-desk"), tile(TileKind.KEEP_AWAKE, desk(keepAwake = true)))
        assertEquals(TileUi(Tile.STATE_INACTIVE, "arch-desk"), tile(TileKind.KEEP_AWAKE, desk(keepAwake = false)))
        // Asked, not yet confirmed by the desk.
        assertEquals(TileUi(Tile.STATE_ACTIVE, "Saving…"), tile(TileKind.KEEP_AWAKE, desk(keepAwake = true, pending = true)))
        // No report yet, or stale.
        assertEquals(Tile.STATE_UNAVAILABLE, tile(TileKind.KEEP_AWAKE, desk(keepAwake = null)).state)
        assertEquals(Tile.STATE_UNAVAILABLE, tile(TileKind.KEEP_AWAKE, desk(ready = false, keepAwake = true)).state)
    }

    @Test
    fun keepAwakeIsDisabledWithWhereToTurnItOnWhileTheComputerHasNotAllowedIt() {
        val ui = DeskSurfaceRules.tile(TileKind.KEEP_AWAKE, desk(keepAwake = false, allowed = false), "Not connected", "Saving…", "Turn on at arch-desk")
        assertEquals(TileUi(Tile.STATE_UNAVAILABLE, "Turn on at arch-desk"), ui)
        // Its real state is not hidden by this: allowed again shows the desk's own value.
        assertEquals(Tile.STATE_ACTIVE, tile(TileKind.KEEP_AWAKE, desk(keepAwake = true, allowed = true)).state)
        assertEquals(false, org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin.keepAwakeUsable(null))
    }

    @Test
    fun theLinkAndBadgeReadAsTheWidgetShowsThem() {
        assertEquals(DeskLink.CONNECTED, DeskSurfaceRules.link(ready = true, reachable = false))
        assertEquals(DeskLink.CONNECTING, DeskSurfaceRules.link(ready = false, reachable = true))
        assertEquals(DeskLink.OFFLINE, DeskSurfaceRules.link(ready = false, reachable = false))
        assertNull(DeskSurfaceRules.terminalBadge(0))
        assertEquals("3", DeskSurfaceRules.terminalBadge(3))
        assertEquals("9+", DeskSurfaceRules.terminalBadge(12))
        assertNull(DeskSurfaceRules.lastSeenLabel(null) { "x" })
        assertEquals("14:05", DeskSurfaceRules.lastSeenLabel(1L) { "14:05" })
    }

    @Test
    fun redrawsAreAtMostOneASecond() {
        assertEquals(0L, DeskSurfaceRules.nextRefreshDelay(lastRefreshAt = 0, now = 5_000))
        assertEquals(700L, DeskSurfaceRules.nextRefreshDelay(lastRefreshAt = 4_300, now = 4_600))
        assertEquals(0L, DeskSurfaceRules.nextRefreshDelay(lastRefreshAt = 4_000, now = 5_000))
    }
}
