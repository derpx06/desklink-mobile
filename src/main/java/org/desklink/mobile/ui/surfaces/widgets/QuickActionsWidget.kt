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
import org.desklink.mobile.ui.surfaces.DeskSurfaces

class QuickActionsWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(250f, 40f), SizeF(250f, 110f))

    /** One action's place in the layout, its spoken name with and without a computer, and its tap. */
    private class Slot(val root: Int, val icon: Int, val label: Int, val named: Int, val plain: Int)

    private val slots = mapOf(
        WidgetAction.SCREEN to Slot(R.id.act_screen, R.id.act_screen_icon, R.id.act_screen_label, R.string.widget_cd_screen, R.string.home_desk_screen),
        WidgetAction.FILES to Slot(R.id.act_files, R.id.act_files_icon, R.id.act_files_label, R.string.widget_cd_files, R.string.home_send_files),
        WidgetAction.CLIPBOARD to Slot(R.id.act_clipboard, R.id.act_clipboard_icon, R.id.act_clipboard_label, R.string.widget_cd_clipboard, R.string.home_send_clipboard_short),
        WidgetAction.RING to Slot(R.id.act_ring, R.id.act_ring_icon, R.id.act_ring_label, R.string.widget_cd_ring, R.string.home_ring_desk),
        WidgetAction.LOCK to Slot(R.id.act_lock, R.id.act_lock_icon, R.id.act_lock_label, R.string.widget_cd_lock, R.string.home_lock_desk),
    )

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews {
        val large = size.height >= 110f
        val v = views(context, if (large) R.layout.widget_actions_large else R.layout.widget_actions_small)
        val id = facts?.id
        val name = facts?.name
        // At huge text the icons stay (each with its spoken name); the labels would be cut off.
        val labels = WidgetRules.roomForExtras(DeskWidgets.fontScale(context))
        WidgetRules.actions(facts).forEach { ui ->
            val slot = slots.getValue(ui.action)
            DeskWidgets.setEnabledLook(v, slot.icon, slot.label, ui.enabled)
            v.show(slot.label, labels)
            val reason = ui.reason?.let { context.getString(it, name.orEmpty()) }
            v.setContentDescription(slot.root, DeskWidgets.spokenAction(context, DeskWidgets.named(context, slot.named, slot.plain, name), ui.enabled, reason))
            // A button that is off still answers a tap: with the reason, in Home's words. With no computer at all, the tap
            // opens the app, where one is paired.
            v.setOnClickPendingIntent(
                slot.root,
                when {
                    facts == null -> DeskWidgets.openApp(context, null)
                    ui.action == WidgetAction.RING -> DeskWidgets.send(context, DeskSurfaces.Action.RING, id)
                    ui.action == WidgetAction.LOCK -> DeskWidgets.send(context, DeskSurfaces.Action.LOCK, id)
                    else -> DeskWidgets.act(context, ui.action, id)
                },
            )
        }
        if (large) {
            val status = WidgetRules.status(facts)
            DeskWidgets.header(context, v, name, status.tone, context.getString(status.label), DeskWidgets.openApp(context, id))
        }
        return v
    }
}
