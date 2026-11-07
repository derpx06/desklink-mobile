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
    override val sizes = listOf(SizeF(110f, 40f))

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val v = views(context, R.layout.widget_terminals)
        val id = facts?.id
        val name = facts?.name.orEmpty()
        val view = WidgetRules.terminals(facts)
        val (title, sub, tap) = when (view) {
            is TerminalsView.Ready -> Triple(
                context.getString(R.string.widget_terminals_new),
                if (view.open > 0) context.resources.getQuantityString(R.plurals.widget_terminals, view.open, view.open) else name,
                DeskWidgets.act(context, WidgetAction.TERMINAL, id),
            )
            // Access is the computer's to give: shown as last heard; the tap asks again and opens the Terminals tab, never a terminal.
            TerminalsView.AccessOff -> Triple(context.getString(R.string.widget_terminals_access_off), context.getString(R.string.widget_terminals_access_hint), DeskWidgets.act(context, WidgetAction.TERMINAL, id))
            is TerminalsView.NotReady -> Triple(context.getString(R.string.widget_terminals_new), context.getString(view.label, name), DeskWidgets.act(context, WidgetAction.TERMINAL, id))
        }
        v.setTextViewText(R.id.term_title, title)
        v.setTextViewText(R.id.term_sub, sub)
        DeskWidgets.setEnabledLook(v, R.id.term_icon, view is TerminalsView.Ready)
        v.setContentDescription(android.R.id.background, context.getString(R.string.widget_join, title, sub))
        v.setOnClickPendingIntent(android.R.id.background, tap)
        return v
    }
}
