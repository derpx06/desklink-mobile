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

class TerminalsWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(110f, 40f), SizeF(180f, 40f))

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val v = views(context, R.layout.widget_terminals)
        val id = facts?.id
        val name = facts?.name.orEmpty()
        val view = WidgetRules.terminals(facts)
        // Two cells hold the computer's name or one short fact under the title, not both; from three cells, both.
        val wide = size.width >= 180f
        val action = DeskWidgets.named(context, R.string.widget_cd_new_terminal, R.string.widget_terminals_new, facts?.name)
        val title: String
        val sub: String
        val spoken: String
        when (view) {
            is TerminalsView.Ready -> {
                title = context.getString(R.string.widget_terminals_new)
                val open = view.open.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.widget_terminals, it, it) }
                sub = listOfNotNull(name, open?.takeIf { wide }).joinToString(" · ")
                spoken = listOfNotNull(action, open).joinToString(". ")
            }
            // Access is the computer's to give: shown as last heard; the tap asks again and opens the Terminals tab, never a terminal.
            TerminalsView.AccessOff -> {
                val off = context.getString(R.string.widget_terminals_access_off)
                title = if (wide) off else context.getString(R.string.widget_terminals_access_off_short)
                val hint = context.getString(R.string.widget_terminals_access_hint)
                sub = if (wide) context.getString(R.string.widget_join, name, hint) else hint
                spoken = listOf(DeskWidgets.spokenAction(context, action, false, off), hint).joinToString(". ")
            }
            is TerminalsView.NotReady -> {
                title = context.getString(R.string.widget_terminals_new)
                val reason = context.getString(if (facts == null && !wide) R.string.widget_not_paired_short else view.label, name)
                sub = if (facts == null || !wide) reason else context.getString(R.string.widget_join, name, reason)
                spoken = listOfNotNull(DeskWidgets.spokenAction(context, action, false, reason), context.getString(R.string.widget_not_paired_hint).takeIf { facts == null }).joinToString(". ")
            }
        }
        v.setTextViewText(R.id.term_title, title)
        v.setTextViewText(R.id.term_sub, sub)
        v.setImageViewResource(R.id.term_dot, DeskWidgets.dot(WidgetRules.status(facts).tone))
        DeskWidgets.setEnabledLook(v, R.id.term_icon, null, view is TerminalsView.Ready)
        v.setContentDescription(android.R.id.background, spoken)
        // No computer: the tap opens the app to pair one. Otherwise WidgetActionActivity checks the live state first.
        v.setOnClickPendingIntent(android.R.id.background, if (facts == null) DeskWidgets.openApp(context, null) else DeskWidgets.act(context, WidgetAction.TERMINAL, id))
        return v
    }
}
