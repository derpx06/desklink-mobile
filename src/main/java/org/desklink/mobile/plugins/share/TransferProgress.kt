/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The newest file transfer, as its notification shows it, for Home's transfer row. Written by
 * [UploadNotification] and [ReceiveNotification], so the row and the notification never disagree.
 */
object TransferProgress {
    data class Transfer(
        val deviceId: String,
        val jobId: Long,
        val upload: Boolean,
        val title: String,
        /** 0–100, or null while the size is unknown. */
        val percent: Int?,
        val finished: Boolean = false,
        val failed: Boolean = false,
        val updatedAt: Long = SystemClock.elapsedRealtime(),
        /** The whole job's size; 0 when unknown. */
        val totalBytes: Long = 0,
        /** Smoothed; 0 until two updates are known. */
        val bytesPerSecond: Long = 0,
        /** The link went away mid-send: it continues by itself when the desk is back. */
        val resuming: Boolean = false,
        /** A received file is whole and is being copied into place. */
        val saving: Boolean = false,
    )

    /** A finished transfer stays on Home this long. */
    const val SHOW_FINISHED_MS = 5_000L

    var current by mutableStateOf<Transfer?>(null)
        private set

    @JvmStatic
    @JvmOverloads
    fun update(deviceId: String, jobId: Long, upload: Boolean, title: String?, percent: Int?, totalBytes: Long = 0) {
        val previous = current?.takeIf { it.jobId == jobId }
        val now = SystemClock.elapsedRealtime()
        val total = totalBytes.takeIf { it > 0 } ?: previous?.totalBytes ?: 0
        val speed = if (previous != null && total > 0 && percent != null && previous.percent != null && now > previous.updatedAt) {
            val instant = (percent - previous.percent).coerceAtLeast(0) * total / 100 * 1000 / (now - previous.updatedAt)
            if (previous.bytesPerSecond == 0L) instant else (previous.bytesPerSecond * 3 + instant) / 4
        } else {
            previous?.bytesPerSecond ?: 0
        }
        // Percent moves in whole steps: keep the last measurement until it moves again.
        if (previous != null && percent == previous.percent && title == previous.title) return
        current = Transfer(deviceId, jobId, upload, title ?: previous?.title.orEmpty(), percent ?: previous?.percent, updatedAt = now, totalBytes = total, bytesPerSecond = speed)
    }

    /** The file arrived whole and is being put in place: no percentage, but not finished either. */
    @JvmStatic
    fun saving(deviceId: String, jobId: Long, title: String, totalBytes: Long) {
        current = Transfer(deviceId, jobId, upload = false, title = title, percent = null, totalBytes = totalBytes, saving = true)
    }

    @JvmStatic
    fun finish(jobId: Long, title: String, failed: Boolean) {
        current?.takeIf { it.jobId == jobId }?.let { current = it.copy(title = it.title.ifEmpty { title }, finished = true, failed = failed, updatedAt = SystemClock.elapsedRealtime()) }
    }

    /** Paused by a lost link, not failed: the next connection resumes it. */
    @JvmStatic
    fun resuming(jobId: Long) {
        current?.takeIf { it.jobId == jobId }?.let { current = it.copy(resuming = true, bytesPerSecond = 0, updatedAt = SystemClock.elapsedRealtime()) }
    }

    /** The paused send for [deviceId] will never resume (discarded): its "resumes when back" row goes. */
    @JvmStatic
    fun dropResuming(deviceId: String) {
        if (current?.let { it.deviceId == deviceId && it.resuming } == true) current = null
    }

    @JvmStatic
    fun cancel(jobId: Long) {
        if (current?.jobId == jobId) current = null
    }

    /** The same Cancel as the transfer's notification. */
    fun cancelIntent(context: android.content.Context, transfer: Transfer): android.content.Intent =
        android.content.Intent(context, ShareBroadcastReceiver::class.java)
            .setAction(SharePlugin.ACTION_CANCEL_SHARE)
            .putExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA, transfer.deviceId)
            .putExtra(SharePlugin.CANCEL_SHARE_BACKGROUND_JOB_ID_EXTRA, transfer.jobId)

    /** What Home shows for [deviceId] now: running, or finished in the last few seconds. */
    fun visibleFor(deviceId: String, now: Long = SystemClock.elapsedRealtime()): Transfer? =
        current?.takeIf { it.deviceId == deviceId && (!it.finished || it.resuming || now - it.updatedAt < SHOW_FINISHED_MS) }
}
