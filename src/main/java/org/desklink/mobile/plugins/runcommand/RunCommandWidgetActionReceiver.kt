/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.plugins.runcommand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.desklink.mobile.DeskLinkApplication

/**
 * Runs the command a widget list item was tapped on.
 *
 * This is deliberately a separate, **non-exported** receiver rather than a
 * branch in [RunCommandWidgetProvider]. That provider must stay exported so the
 * system can deliver `APPWIDGET_UPDATE`, and an exported receiver can be
 * targeted explicitly by any application regardless of its intent filters — so
 * handling `RUN_COMMAND_ACTION` there let any app on the phone, holding no
 * permissions at all, execute an arbitrary command on the paired desktop.
 *
 * The widget reaches this receiver through a `PendingIntent` it creates itself,
 * which carries this application's identity and therefore still resolves to a
 * component nobody else can address.
 */
class RunCommandWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RUN_COMMAND_ACTION) {
            return
        }
        val targetCommand = intent.getStringExtra(TARGET_COMMAND)
        val targetDevice = intent.getStringExtra(TARGET_DEVICE)
        val plugin = DeskLinkApplication.getInstance()
            .getDevicePlugin(targetDevice, RunCommandPlugin::class.java)
        if (plugin == null) {
            Log.w("RunCommandWidget", "Device not available or runcommand plugin disabled")
            return
        }
        try {
            plugin.runCommand(targetCommand)
        } catch (ex: Exception) {
            Log.e("RunCommandWidget", "Error running command", ex)
        }
    }
}
