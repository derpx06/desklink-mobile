/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.SizeF
import android.widget.RemoteViews
import org.desklink.mobile.R
import org.desklink.mobile.plugins.notes.NotesStore

class NotesWidget : FamilyWidget() {
    override val sizes = listOf(SizeF(110f, 110f), SizeF(110f, 160f), SizeF(110f, 210f), SizeF(110f, 260f))
    override val background = true

    override fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews =
        renderWith(context, size, DeskWidgets.locked(context)) { NotesStore.notes(context).map { NoteLine(it.id, it.label) } }

    /** The real render path, with the notes source and the lock state given (a test passes a source that fails if it is read while locked). */
    fun renderWith(context: Context, size: SizeF, locked: Boolean, unavailable: Boolean = NotesStore.unavailable.value, notes: () -> List<NoteLine>): RemoteViews {
        // The notes are read only when the phone is unlocked: a locked phone never loads their text into the widget.
        val lines = if (locked) emptyList() else notes()
        return draw(context, WidgetRules.notes(lines, locked, unavailable, WidgetRules.noteRows(size.height)))
    }

    fun draw(context: Context, view: NotesView): RemoteViews {
        val v = views(context, R.layout.widget_notes)
        val rows = listOf(R.id.n_row1, R.id.n_row2, R.id.n_row3, R.id.n_row4, R.id.n_row5)
        rows.forEach { v.show(it, false) }
        v.show(R.id.n_message, false)
        v.show(R.id.n_more, false)
        v.setOnClickPendingIntent(R.id.n_title, DeskWidgets.openNote(context, ""))
        v.setOnClickPendingIntent(R.id.n_new, DeskWidgets.newNote(context))
        when (view) {
            NotesView.Hidden -> message(v, context.getString(R.string.widget_notes_locked) + "\n" + context.getString(R.string.widget_notes_locked_hint), DeskWidgets.openNote(context, ""))
            NotesView.Empty -> message(v, context.getString(R.string.widget_notes_none), DeskWidgets.newNote(context))
            NotesView.Unavailable -> message(v, context.getString(R.string.widget_notes_unavailable), DeskWidgets.openNote(context, ""))
            is NotesView.Titles -> {
                view.lines.forEachIndexed { i, line ->
                    v.show(rows[i], true)
                    v.setTextViewText(rows[i], line.title)
                    v.setContentDescription(rows[i], context.getString(R.string.widget_notes_open, line.title))
                    v.setOnClickPendingIntent(rows[i], DeskWidgets.openNote(context, line.id))
                }
                if (view.more > 0) {
                    v.show(R.id.n_more, true)
                    v.setTextViewText(R.id.n_more, context.resources.getQuantityString(R.plurals.widget_notes_more, view.more, view.more))
                }
            }
        }
        return v
    }

    private fun message(v: RemoteViews, text: String, tap: PendingIntent) {
        v.show(R.id.n_message, true)
        v.setTextViewText(R.id.n_message, text)
        v.setOnClickPendingIntent(R.id.n_message, tap)
    }

    companion object {
        /** Notes changed, or the phone locked or unlocked. */
        fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, NotesWidget::class.java))
            if (ids.isNotEmpty()) NotesWidget().update(context, manager, ids, null)
        }
    }
}

