/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.desklink.mobile.Device
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.share.ShareEntry

/**
 * "Send to computer" in the text-selection menu of any app (PROCESS_TEXT). This is the release-safe way to send a phone copy without
 * a tap in DeskLink and without READ_LOGS: the selected text arrives in the intent, so no clipboard read is needed.
 *
 * It is exported (the system menu of other apps must reach it), so an app can also start it with any text. Therefore nothing is sent
 * until the person taps a computer or Send on a sheet that shows what and to whom (as the share target does); the text is only ever
 * read from the intent and nothing is returned to the calling app.
 */
class SendSelectedTextActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = SendSelected.textOf(intent)
        val targets = SendSelected.targets()
        when (SendSelected.plan(text, targets.size)) {
            SendSelected.Plan.NO_TEXT -> end(R.string.send_selected_nothing)
            SendSelected.Plan.NO_COMPUTER -> end(R.string.send_selected_no_computer)
            SendSelected.Plan.ONE -> dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.share_confirm_title, targets[0].name))
                .setMessage(text!!.take(ShareEntry.MAX_SHOWN))
                .setPositiveButton(R.string.share_confirm_send) { _, _ -> send(text, targets[0]) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            SendSelected.Plan.CHOOSE -> dialog = AlertDialog.Builder(this)
                .setTitle(R.string.send_selected_pick)
                .setItems(targets.map { it.name }.toTypedArray()) { _, which -> send(text!!, targets[which]) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun send(text: String, device: Device) {
        val sent = device.getPlugin(ClipboardPlugin::class.java)?.sendSelected(text) == true
        org.desklink.mobile.helpers.AppLog.event("clipboard", if (sent) "selected text sent" else "selected text not sent")
        end(if (sent) 0 else R.string.send_selected_too_large, if (sent) getString(R.string.send_selected_sent, device.name) else null)
    }

    private fun end(message: Int, text: String? = null) {
        Toast.makeText(this, text ?: getString(message), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}

/** What "Send to computer" does with the selection and the computers that can take it (pure parts are tested). */
object SendSelected {
    enum class Plan { NO_TEXT, NO_COMPUTER, ONE, CHOOSE }

    /** The selected text of a PROCESS_TEXT intent; null for any other action or a blank selection. Read only. */
    fun textOf(intent: Intent): String? {
        if (intent.action != Intent.ACTION_PROCESS_TEXT) return null
        return runCatching { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun plan(text: String?, computers: Int): Plan = when {
        text == null -> Plan.NO_TEXT
        computers <= 0 -> Plan.NO_COMPUTER
        computers == 1 -> Plan.ONE
        else -> Plan.CHOOSE
    }

    /** The computers that have clipboard sharing on and can take it now (paired, feature-ready). */
    fun targets(): List<Device> = runCatching {
        val key = Plugin.getPluginKey(ClipboardPlugin::class.java)
        DeskLinkApplication.getInstance().devices.values.filter { it.isPaired && it.isPluginEnabled(key) && it.hasActiveWebRtcFeatureTransport }
    }.getOrDefault(emptyList())
}
