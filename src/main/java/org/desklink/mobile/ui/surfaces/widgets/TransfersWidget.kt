/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.content.Context
import android.text.format.DateUtils
import android.util.SizeF
import android.widget.RemoteViews
import org.desklink.mobile.R
import org.desklink.mobile.helpers.Sizes
import org.desklink.mobile.plugins.folders.BucketEngine
import org.desklink.mobile.plugins.share.TransferHistory
import org.desklink.mobile.plugins.share.TransferProgress

class TransfersWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(250f, 40f))
    override val background = true

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val id = facts?.id
        val running = id?.let { TransferProgress.visibleFor(it) }?.takeIf { !it.finished && !it.failed }?.let { TransfersView.Running(it.title, it.upload, it.percent) }
        val last = id?.let { TransferHistory.store(context).forDevice(it).firstOrNull() }?.let { TransfersView.Last(it.name, it.sent, it.time) }
        val bucket = id?.let { BucketEngine.views.value[it] }?.takeIf { it.treeUri != null }?.let { context.getString(R.string.widget_bucket_size, Sizes.iec(it.bytes)) }
        return draw(context, facts, WidgetRules.transfers(running, last), bucket)
    }

    fun draw(context: Context, facts: ComputerFacts?, view: TransfersView, bucket: String?): RemoteViews {
        val v = views(context, R.layout.widget_transfers)
        val id = facts?.id
        v.show(R.id.t_progress, false)
        v.show(R.id.t_bucket, bucket != null && view !is TransfersView.Running)
        v.setTextViewText(R.id.t_bucket, bucket.orEmpty())
        when (view) {
            is TransfersView.Running -> {
                v.setTextViewText(R.id.t_title, view.name)
                val word = context.getString(if (view.upload) R.string.home_transfer_sending else R.string.home_transfer_receiving)
                v.setTextViewText(R.id.t_sub, view.percent?.let { context.getString(R.string.widget_join, word, "$it%") } ?: word)
                v.setImageViewResource(R.id.t_icon, if (view.upload) R.drawable.sym_upload else R.drawable.sym_arrow_downward)
                v.show(R.id.t_progress, true)
                v.setProgressBar(R.id.t_progress, 100, view.percent ?: 0, view.percent == null)
            }
            is TransfersView.Last -> {
                v.setTextViewText(R.id.t_title, view.name)
                val word = context.getString(if (view.sent) R.string.home_transfer_sent else R.string.home_transfer_received)
                v.setTextViewText(R.id.t_sub, context.getString(R.string.widget_join, word, DateUtils.getRelativeTimeSpanString(view.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)))
                v.setImageViewResource(R.id.t_icon, if (view.sent) R.drawable.sym_upload else R.drawable.sym_arrow_downward)
            }
            TransfersView.Idle -> {
                v.setTextViewText(R.id.t_title, context.getString(R.string.widget_transfers_idle))
                v.setTextViewText(R.id.t_sub, context.getString(R.string.widget_transfers_idle_hint))
                v.setImageViewResource(R.id.t_icon, R.drawable.sym_upload_file)
            }
        }
        v.setContentDescription(android.R.id.background, listOfNotNull(facts?.name, context.getString(R.string.widget_transfers_label), "", bucket).filter { it.isNotEmpty() }.joinToString(", "))
        v.setOnClickPendingIntent(android.R.id.background, DeskWidgets.openFiles(context, id))
        return v
    }
}

