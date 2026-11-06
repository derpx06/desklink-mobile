/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.app.PendingIntent
import android.content.Context
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.IdRes
import org.desklink.mobile.R
import org.desklink.mobile.ui.surfaces.DeskSurfaces

class QuickActionsWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(250f, 40f), SizeF(250f, 110f))

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val large = size.height >= 110f
        val v = views(context, if (large) R.layout.widget_actions_large else R.layout.widget_actions_small)
        val id = facts?.id
        val name = facts?.name.orEmpty()
        val buttons = WidgetRules.actions(facts).associateBy { it.action }
        fun button(a: WidgetAction, @IdRes root: Int, nameRes: Int, tap: PendingIntent) {
            val ui = buttons.getValue(a)
            DeskWidgets.setEnabledLook(v, root, ui.enabled)
            val label = context.getString(nameRes)
            v.setContentDescription(root, if (ui.enabled) label else context.getString(R.string.widget_action_off, label, ui.reason?.let { context.getString(it, name) }.orEmpty()))
            // A disabled button still answers a tap: with the reason, in Home's words.
            v.setOnClickPendingIntent(root, tap)
        }
        button(WidgetAction.SCREEN, R.id.act_screen, R.string.home_desk_screen, DeskWidgets.act(context, WidgetAction.SCREEN, id))
        button(WidgetAction.FILES, R.id.act_files, R.string.home_send_files, DeskWidgets.act(context, WidgetAction.FILES, id))
        button(WidgetAction.CLIPBOARD, R.id.act_clipboard, R.string.home_send_clipboard_short, DeskWidgets.act(context, WidgetAction.CLIPBOARD, id))
        button(WidgetAction.RING, R.id.act_ring, R.string.home_ring_desk, DeskWidgets.send(context, DeskSurfaces.Action.RING, id))
        // Lock is on the widget only while the computer lets this phone lock it.
        val lock = WidgetRules.lock(facts)
        v.show(R.id.act_lock, lock != null)
        if (lock != null) {
            val label = context.getString(R.string.home_lock_desk)
            DeskWidgets.setEnabledLook(v, R.id.act_lock, lock.enabled)
            v.setContentDescription(R.id.act_lock, if (lock.enabled) label else context.getString(R.string.widget_action_off, label, lock.reason?.let { context.getString(it, name) }.orEmpty()))
            v.setOnClickPendingIntent(R.id.act_lock, DeskWidgets.send(context, DeskSurfaces.Action.LOCK, id))
        }
        if (large) {
            val status = WidgetRules.status(facts)
            v.setTextViewText(R.id.w_name, facts?.name ?: context.getString(R.string.app_name))
            v.setTextViewText(R.id.w_status, context.getString(status.label))
            v.setImageViewResource(R.id.w_dot, DeskWidgets.dot(status.tone))
            v.setOnClickPendingIntent(R.id.w_header, DeskWidgets.openApp(context, id))
            v.setContentDescription(R.id.w_header, context.getString(R.string.widget_join, facts?.name ?: context.getString(R.string.app_name), context.getString(status.label)))
        }
        return v
    }
}

