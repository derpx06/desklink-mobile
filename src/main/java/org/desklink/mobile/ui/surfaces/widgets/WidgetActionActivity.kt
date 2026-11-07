/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.extensions.isReadable
import org.desklink.mobile.plugins.clipboard.ClipboardFloatingActivity
import org.desklink.mobile.plugins.mousepad.MousePadActivity
import org.desklink.mobile.plugins.share.SendFileActivity
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.surfaces.DeskSurfaces

/**
 * Where a widget button that opens a screen goes first. It has no window of its own: it asks [WidgetRules.gate]
 * about the *live* state (the picture on the home screen may be older than the link), then either opens the screen or
 * says why not in Home's wording. Nothing is queued behind a button that looked like it worked. Not exported: only the
 * widgets' own PendingIntents reach it.
 */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.isReadable()) run()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun run() {
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { runCatching { WidgetAction.valueOf(it) }.getOrNull() } ?: return
        val app = DeskLinkApplication.getInstance()
        val baked = intent.getStringExtra(EXTRA_DEVICE_ID)
        // The widget names its computer: act on that one or on nothing. A computer that is gone is never swapped for another.
        val device = if (baked != null) app.getDevice(baked)?.takeIf { it.isPaired } else DeskSurfaces.currentDesk(this)
        if (baked != null && device == null) {
            tell(getString(R.string.widget_unpaired))
            DeskSurfaces.refresh(this)
            return
        }
        val facts = device?.let { WidgetFacts.of(this, it) }
        val id = device?.deviceId
        when (val gate = WidgetRules.gate(action, facts)) {
            is Gate.Say -> tell(getString(gate.message, facts?.name.orEmpty()))
            Gate.TerminalsTab -> {
                // "Off" was the last answer, not a verdict: ask again, and let the tab (with its Retry) show what the computer says.
                app.terminalClient(id)?.refresh()
                tell(getString(R.string.terminal_not_allowed_title))
                open(MainActivity.terminalsIntent(this, id))
            }
            Gate.Go -> when (action) {
                // The screens ask for the session password or the fingerprint themselves.
                WidgetAction.SCREEN -> open(Intent(this, MousePadActivity::class.java).putExtra("deviceId", id))
                WidgetAction.FILES -> open(Intent(this, SendFileActivity::class.java).putExtra("deviceId", id))
                // Android 10+ reads the clipboard only for the app in front: the floating activity is it.
                WidgetAction.CLIPBOARD -> open(ClipboardFloatingActivity.getIntent(this, true, id))
                WidgetAction.TERMINAL -> open(TerminalActivity.intent(this, id.orEmpty(), newTerminal = true))
                WidgetAction.RING -> DeskSurfaces.perform(this, DeskSurfaces.Action.RING, id)
                WidgetAction.LOCK -> DeskSurfaces.perform(this, DeskSurfaces.Action.LOCK, id)
            }
        }
    }

    private fun open(intent: Intent) = startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun tell(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
