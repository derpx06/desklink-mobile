/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper

/**
 * Turning on DeskLink's accessibility service (desk control, typing on the phone, locking it),
 * step by step. On Android 13+ a sideloaded app's service is greyed out as a "Restricted setting"
 * until App info → ⋮ → Allow restricted settings, which nobody finds unaided, so the steps say so
 * and both screens are one tap away. Re-checked on every return; closes itself once it is on.
 */
class AccessibilitySetupActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null

    override fun onResume() {
        super.onResume()
        dialog?.dismiss()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ASK)
        if (running()) {
            Toast.makeText(this, R.string.accessibility_setup_done, Toast.LENGTH_SHORT).show()
            return finish()
        }
        val steps = buildString {
            append(getString(if (enabled(this@AccessibilitySetupActivity)) R.string.accessibility_setup_restart else R.string.accessibility_setup_step))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) append("\n\n").append(getString(R.string.accessibility_setup_restricted))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_setup_title)
            .setMessage(steps)
            .setPositiveButton(R.string.accessibility_setup_open) { _, _ -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton(R.string.accessibility_setup_app_info) { _, _ ->
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun open(intent: Intent) {
        if (runCatching { startActivity(intent) }.isFailure) finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ASK = 1739

        /** Bound and working: what every feature actually needs. */
        fun running() = MouseReceiverService.instance != null

        /** Switched on in Settings (it may not be bound yet, or Android stopped it). */
        fun enabled(context: Context): Boolean {
            val ours = ComponentName(context, MouseReceiverService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == ours }
        }

        fun open(context: Context) = context.startActivity(
            Intent(context, AccessibilitySetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        /** A desk asked for control and could not have it: say why on the phone, one tap from the fix. */
        fun ask(context: Context, deskName: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = Intent(context, AccessibilitySetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(context, NOTIFICATION_ASK, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ASK,
                NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.accessibility_setup_ask, deskName))
                    .setContentText(context.getString(R.string.accessibility_setup_ask_text))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setTimeoutAfter(120_000)
                    .build(),
            )
        }
    }
}
