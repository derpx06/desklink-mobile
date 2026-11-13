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
    override val sizes = listOf(SizeF(110f, 40f), SizeF(180f, 40f), SizeF(250f, 40f))

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val v = views(context, R.layout.widget_status)
        val status = WidgetRules.status(facts)
        // Up to 3 cells: the short words ("Connected · Relay"); 4 cells: the full ones, as Home's header says them. Spoken: always the full ones.
        val words = context.getString(if (size.width < 250f) shortLabel(status) else status.label)
        val now = System.currentTimeMillis()
        val narrow = size.width < 180f
        val battery = facts?.takeIf { it.ready }?.let { DeskWidgets.batteryText(context, it) }
        val rtt = facts?.takeIf { it.ready }?.rttMs?.let { context.getString(R.string.home_meta_rtt, it) }
        val time = when {
            facts == null -> null
            facts.ready -> context.getString(R.string.widget_status_updated, DeskWidgets.clock(context, now))
            else -> facts.lastSeen?.let { context.getString(R.string.widget_status_last_seen, DeskWidgets.clock(context, it)) }
        }
        // Narrow: the battery moves under the name (beside it, it cut the name and the state short) and "Updated" goes.
        val detail = listOfNotNull(battery?.takeIf { narrow }, rtt, time?.takeIf { !narrow || facts?.ready != true }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        val batterySpoken = facts?.takeIf { it.ready }?.batteryPercent?.let {
            context.getString(if (facts.charging) R.string.widget_pc_battery_charging_spoken else R.string.widget_pc_battery_spoken, it)
        }
        val showDetail = detail != null && WidgetRules.roomForExtras(DeskWidgets.fontScale(context))

        DeskWidgets.header(context, v, facts?.name, status.tone, words, tap = null)
        v.setTextViewText(R.id.w_battery_text, battery.orEmpty())
        v.show(R.id.w_battery, battery != null && !narrow)
        v.setTextViewText(R.id.w_detail, detail.orEmpty())
        v.show(R.id.w_detail, showDetail)
        // One row: the state takes a second line only when nothing is under it.
        v.setInt(R.id.w_status, "setMaxLines", if (showDetail) 1 else 2)
        // One target, read as one sentence: what a tap does, then the state in full words (never only the short ones).
        val hint = if (facts == null) context.getString(R.string.widget_not_paired_hint) else null
        val spoken = listOfNotNull(DeskWidgets.named(context, R.string.widget_cd_open, R.string.widget_cd_open_app, facts?.name), context.getString(status.label), batterySpoken, rtt, time, hint)
        v.setContentDescription(android.R.id.background, spoken.joinToString(". "))
        v.setOnClickPendingIntent(android.R.id.background, DeskWidgets.openApp(context, facts?.id))
        return v
    }

    private fun shortLabel(status: WidgetStatus): Int = when (status) {
        WidgetStatus.WIFI -> R.string.home_short_wifi
        WidgetStatus.RELAY, WidgetStatus.TURN -> R.string.home_short_relay
        else -> status.label
    }
}

