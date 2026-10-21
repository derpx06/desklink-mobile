/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.clipboard

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat

@RequiresApi(Build.VERSION_CODES.N)
class ClipboardTileService : TileService() {
    /** Like the other desk tiles: the desk's name, or "Not connected" and unavailable. */
    override fun onStartListening() {
        super.onStartListening()
        org.desklink.mobile.ui.surfaces.DeskTileService.render(this, org.desklink.mobile.ui.surfaces.TileKind.CLIPBOARD)
    }

    override fun onClick() {
        super.onClick()

        // A tap is an explicit send: forced, with a toast saying what happened.
        TileServiceCompat.startActivityAndCollapse(this, PendingIntentActivityWrapper(
            this, 0, ClipboardFloatingActivity.getIntent(this, true), PendingIntent.FLAG_ONE_SHOT, true
        ))
    }
}
