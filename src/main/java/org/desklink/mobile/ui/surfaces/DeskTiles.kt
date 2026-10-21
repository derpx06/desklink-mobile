/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces

import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import org.desklink.mobile.R

/**
 * A Quick Settings tile for the current desk. Its state comes from [DeskSurfaceRules.tile] each
 * time the shade shows it, and on every [DeskSurfaces.refresh] while it is listening.
 */
@RequiresApi(Build.VERSION_CODES.N)
abstract class DeskTileService(private val kind: TileKind) : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render(this, kind)
    }

    companion object {
        fun render(service: TileService, kind: TileKind) {
            val tile = service.qsTile ?: return
            val desk = DeskSurfaces.currentDesk(service)?.let { DeskSurfaces.snapshot(service, it) }
            val ui = DeskSurfaceRules.tile(kind, desk, service.getString(R.string.surfaces_not_connected), service.getString(R.string.settings_saving))
            tile.state = ui.state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = ui.subtitle
            tile.contentDescription = "${tile.label}, ${ui.subtitle}"
            tile.updateTile()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class LockDeskTileService : DeskTileService(TileKind.LOCK) {
    override fun onClick() {
        super.onClick()
        DeskSurfaces.perform(this, DeskSurfaces.Action.LOCK)
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class RingDeskTileService : DeskTileService(TileKind.RING) {
    override fun onClick() {
        super.onClick()
        DeskSurfaces.perform(this, DeskSurfaces.Action.RING)
    }
}

/** Keep the desk awake: the desk's own setting, asked for here, shown as the desk reports it. */
@RequiresApi(Build.VERSION_CODES.N)
class KeepAwakeTileService : DeskTileService(TileKind.KEEP_AWAKE) {
    override fun onClick() {
        super.onClick()
        DeskSurfaces.perform(this, DeskSurfaces.Action.KEEP_AWAKE)
        render(this, TileKind.KEEP_AWAKE)
    }
}
