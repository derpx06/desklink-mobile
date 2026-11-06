/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.content.Context
import android.util.SizeF
import android.widget.RemoteViews
import org.desklink.mobile.R

class ComputerStatusWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(110f, 40f), SizeF(180f, 40f))

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val v = views(context, R.layout.widget_status)
        val status = WidgetRules.status(facts)
        // Narrow (2 cells): the short words; wider: the full ones, as Home's header says them.
        val words = context.getString(if (size.width < 180f) shortLabel(status) else status.label)
        val now = System.currentTimeMillis()
        val rtt = facts?.rttMs
        val detail = when {
            facts == null -> null
            facts.ready -> listOfNotNull(rtt?.let { context.getString(R.string.home_meta_rtt, it) }, context.getString(R.string.widget_status_updated, DeskWidgets.clock(context, now)))
            else -> facts.lastSeen?.let { listOf(context.getString(R.string.widget_status_last_seen, DeskWidgets.clock(context, it))) }
        }?.joinToString(" · ")
        val battery = facts?.takeIf { it.ready }?.let { DeskWidgets.batteryText(context, it) }

        v.setTextViewText(R.id.w_name, facts?.name ?: context.getString(R.string.app_name))
        v.setTextViewText(R.id.w_status, words)
        v.setImageViewResource(R.id.w_dot, DeskWidgets.dot(status.tone))
        v.setTextViewText(R.id.w_battery, battery.orEmpty())
        v.show(R.id.w_battery, battery != null)
        v.setTextViewText(R.id.w_detail, detail.orEmpty())
        v.show(R.id.w_detail, detail != null)
        val spoken = listOfNotNull(facts?.name, context.getString(status.label), detail, battery).joinToString(", ")
        v.setContentDescription(android.R.id.background, spoken)
        v.setOnClickPendingIntent(android.R.id.background, DeskWidgets.openApp(context, facts?.id))
        return v
    }

    private fun shortLabel(status: WidgetStatus): Int = when (status) {
        WidgetStatus.WIFI -> R.string.home_short_wifi
        WidgetStatus.RELAY, WidgetStatus.TURN -> R.string.home_short_relay
        else -> status.label
    }
}

