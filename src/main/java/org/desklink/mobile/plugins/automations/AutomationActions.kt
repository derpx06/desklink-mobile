/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.runcommand.RunCommandPlugin
import org.desklink.mobile.plugins.terminal.TerminalActivity

/**
 * F7 phone actions. Only the phone's own public APIs: no shell, never accessibility. Screens open
 * from a heads-up the person taps (Android does not let a background app start an activity).
 */
object AutomationActions {
    sealed interface Result {
        data object Done : Result
        data class Failed(val reason: String) : Result
    }

    /** "Ring": the alarm sound this long at most, then it stops by itself. */
    const val RING_MS = 15_000L

    private const val CHANNEL_NOTIFY_ID = 7400

    fun run(context: Context, action: Step): Result = when (action.kind) {
        Rules.ACTION_RING -> ring(context)
        Rules.ACTION_RINGER -> ringer(context, action["mode"])
        Rules.ACTION_VOLUME -> volume(context, action["stream"], action["percent"]?.toIntOrNull())
        Rules.ACTION_DND -> dnd(context, action["on"] != "false")
        Rules.ACTION_OPEN_TERMINALS -> deskScreen(context, action["device"], terminals = true)
        Rules.ACTION_OPEN_DESK_SCREEN -> deskScreen(context, action["device"], terminals = false)
        Rules.ACTION_NOTIFY -> notify(context, context.getString(R.string.automations_title), action["text"] ?: "", null)
        Rules.ACTION_RUN_DESK_COMMAND -> runDeskCommand(action["device"], action["command"])
        else -> Result.Failed("unknown")
    }

    private fun ring(context: Context): Result {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return Result.Failed("no sound")
        val tone = RingtoneManager.getRingtone(context, uri) ?: return Result.Failed("no sound")
        tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        Handler(Looper.getMainLooper()).post {
            tone.play()
            Handler(Looper.getMainLooper()).postDelayed({ tone.stop() }, RING_MS)
        }
        return Result.Done
    }

    private fun ringer(context: Context, mode: String?): Result {
        val audio = context.getSystemService(AudioManager::class.java) ?: return Result.Failed("no audio")
        val target = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return Result.Failed("mode")
        }
        // Silent (and leaving it) needs Do Not Disturb access on Android 7+.
        if (!dndAllowed(context) && (target == AudioManager.RINGER_MODE_SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT)) {
            return Result.Failed("needs Do Not Disturb access")
        }
        return runCatching { audio.ringerMode = target; Result.Done }.getOrElse { Result.Failed("refused") }
    }

    private fun volume(context: Context, stream: String?, percent: Int?): Result {
        val audio = context.getSystemService(AudioManager::class.java) ?: return Result.Failed("no audio")
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val level = percent?.coerceIn(0, 100) ?: return Result.Failed("percent")
        return runCatching {
            audio.setStreamVolume(type, audio.getStreamMaxVolume(type) * level / 100, 0)
            Result.Done
        }.getOrElse { Result.Failed("refused") }
    }

    fun dndAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun dnd(context: Context, on: Boolean): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Result.Failed("unsupported")
        if (!dndAllowed(context)) return Result.Failed("needs Do Not Disturb access")
        val manager = context.getSystemService(NotificationManager::class.java) ?: return Result.Failed("unsupported")
        manager.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Result.Done
    }

    private fun desk(id: String?) = DeskLinkApplication.getInstance().let { app ->
        id?.let(app::getDevice) ?: app.devices.values.firstOrNull { it.isPaired && it.hasActiveWebRtcFeatureTransport }
            ?: app.devices.values.firstOrNull { it.isPaired }
    }

    private fun deskScreen(context: Context, deviceId: String?, terminals: Boolean): Result {
        val device = desk(deviceId) ?: return Result.Failed("no desk")
        val open = if (terminals) {
            TerminalActivity.intent(context, device.deviceId)
        } else {
            Intent(context, org.desklink.mobile.plugins.mousepad.MousePadActivity::class.java).putExtra("deviceId", device.deviceId)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val title = context.getString(if (terminals) R.string.automations_open_terminals_title else R.string.automations_open_screen_title, device.name)
        return notify(context, title, context.getString(R.string.automations_tap_to_open), open)
    }

    private fun notify(context: Context, title: String, text: String, open: Intent?): Result {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Result.Failed("notifications off")
        val tap = open?.let { PendingIntent.getActivity(context, it.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(Rules.MAX_TEXT_CHARS))
            .setContentText(text.take(Rules.MAX_TEXT_CHARS))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .apply { tap?.let(::setContentIntent) }
            .build()
        return runCatching {
            manager.notify("automation", CHANNEL_NOTIFY_ID + (title.hashCode() and 0xFF), notification)
            Result.Done
        }.getOrElse { Result.Failed("notifications off") }
    }

    private fun runDeskCommand(deviceId: String?, command: String?): Result {
        val key = command ?: return Result.Failed("no command chosen")
        val device = desk(deviceId) ?: return Result.Failed("no desk")
        if (!device.hasActiveWebRtcFeatureTransport) return Result.Failed("desk not connected")
        val plugin = device.getPlugin(RunCommandPlugin::class.java) ?: return Result.Failed("run command off")
        plugin.runCommand(key)
        return Result.Done
    }
}
