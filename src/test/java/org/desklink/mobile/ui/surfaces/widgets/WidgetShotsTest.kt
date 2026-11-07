/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.surfaces.widgets

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.shots.ShotDir
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Each widget layout, drawn from a RemoteViews exactly as the launcher inflates it, in light and dark, over a wallpaper-like
 * backdrop: `widget-<name>-<light|dark>.png` in the shots folder (`-Ddesklink.shots=<dir>`). Also the checks a screen reader
 * and a thumb need: every tappable has a name and is at least 48 dp each way.
 */
abstract class WidgetShots {
    protected val base: Application = ApplicationProvider.getApplicationContext()

    private fun themed(dark: Boolean): Context {
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(config)
    }

    /** [form] is part of the file name ("m3" with wallpaper colours on Android 12+, "tokens" for the DeskLink palette before it). */
    abstract val form: String

    protected fun shoot(name: String, widthDp: Float, heightDp: Float, build: (Context) -> RemoteViews, check: (View, Context) -> Unit = { _, _ -> }) {
        for (dark in listOf(false, true)) {
            val context = themed(dark)
            val density = context.resources.displayMetrics.density
            val pad = (8 * density).toInt()
            val w = (widthDp * density).toInt()
            val h = (heightDp * density).toInt()
            val stage = FrameLayout(context).apply {
                setBackgroundColor(if (dark) Color.parseColor("#FF2A3140") else Color.parseColor("#FFB8C4E0"))
                setPadding(pad, pad, pad, pad)
            }
            val widget = build(context).apply(context, stage)
            stage.addView(widget, FrameLayout.LayoutParams(w, h))
            stage.measure(View.MeasureSpec.makeMeasureSpec(w + 2 * pad, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h + 2 * pad, View.MeasureSpec.EXACTLY))
            stage.layout(0, 0, w + 2 * pad, h + 2 * pad)
            check(widget, context)
            accessible(widget, context, name)
            val bitmap = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
            stage.draw(Canvas(bitmap))
            FileOutputStream(File(ShotDir.dir, "widget-$form-$name-${if (dark) "dark" else "light"}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun accessible(root: View, context: Context, name: String) {
        val density = context.resources.displayMetrics.density
        fun walk(v: View) {
            if (v.hasOnClickListeners() && v.visibility == View.VISIBLE) {
                val named = !v.contentDescription.isNullOrBlank() || (v as? TextView)?.text?.isNotBlank() == true
                assertTrue("$name: a tappable without a name (id ${v.id})", named)
                assertTrue("$name: tappable ${v.id} is ${v.width / density} x ${v.height / density} dp, under 48", v.width / density >= 47.5f && v.height / density >= 47.5f)
            }
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
    }

    protected fun find(root: View, id: Int): View = root.findViewById(id)

    protected val live = ComputerFacts(
        id = "d", name = "arch-desk", connection = DeviceConnection.LOCAL, path = WebRtcConnectionPath.LAN, ready = true, batteryPercent = 84, rttMs = 12,
        screen = true, files = true, clipboard = true, ring = true, lock = true, terminalCount = 2,
    )
    protected val away = live.copy(connection = DeviceConnection.AWAY, path = WebRtcConnectionPath.TURN, rttMs = 88, batteryPercent = null)
    protected val down = live.copy(connection = DeviceConnection.OFFLINE, path = null, ready = false, batteryPercent = null, rttMs = null, lastSeen = 1_762_000_000_000L, lock = false)
    protected val connecting = down.copy(connection = DeviceConnection.CONNECTING)

    // sizes a launcher really gives: 2 cells ~160 dp wide, 4 cells ~330, rows ~74 dp tall
    protected val w2 = 160f
    protected val w3 = 240f
    protected val w4 = 330f
    protected fun h(rows: Int) = 74f * rows - 2f


    @Test fun statusConnected() = shoot("status-connected", w2, h(1), { ComputerStatusWidget().render(it, live, SizeF(110f, 40f)) })
    @Test fun statusConnectedWide() = shoot("status-wide", w3, h(1), { ComputerStatusWidget().render(it, live, SizeF(180f, 40f)) })
    @Test fun statusAway() = shoot("status-relay", w3, h(1), { ComputerStatusWidget().render(it, away, SizeF(180f, 40f)) })
    @Test fun statusCantReach() = shoot("status-cant-reach", w3, h(1), { ComputerStatusWidget().render(it, down, SizeF(180f, 40f)) })
    @Test fun statusNone() = shoot("status-none", w2, h(1), { ComputerStatusWidget().render(it, null, SizeF(110f, 40f)) })

    @Test fun actionsSmall() = shoot("actions-4x1", w4, h(1), { QuickActionsWidget().render(it, live, SizeF(250f, 40f)) })
    @Test fun actionsLarge() = shoot("actions-4x2", w4, h(2), { QuickActionsWidget().render(it, live, SizeF(250f, 110f)) })
    @Test fun actionsLargeNoLock() = shoot("actions-4x2-no-lock", w4, h(2), { QuickActionsWidget().render(it, live.copy(lock = false), SizeF(250f, 110f)) })
    @Test fun actionsUnreachable() = shoot("actions-4x2-unreachable", w4, h(2), { QuickActionsWidget().render(it, down.copy(lock = true), SizeF(250f, 110f)) })
    @Test fun actionsUnreachableSmall() = shoot("actions-4x1-unreachable", w4, h(1), { QuickActionsWidget().render(it, connecting, SizeF(250f, 40f)) })

    private val titles = NotesView.Titles(listOf("Shopping list", "Meeting notes", "Ideas for the weekend", "Wi‑Fi passwords").map { NoteLine(it, it) }, 3)

    @Test fun notesSmall() = shoot("notes-2x2", w2, h(2), { NotesWidget().draw(it, NotesView.Titles(titles.lines.take(1), 6)) })
    @Test fun notesMedium() = shoot("notes-4x3", w4, h(3), { NotesWidget().draw(it, NotesView.Titles(titles.lines.take(3), 4)) })
    @Test fun notesTall() = shoot("notes-4x4", w4, h(4), { NotesWidget().draw(it, titles) })
    // The real render path, locked: a source that fails the test if it is read at all, with notes that would show if it were.
    @Test fun notesLocked() = shoot("notes-locked", w2, h(2), { NotesWidget().renderWith(it, SizeF(110f, 160f), locked = true) { fail("the notes were read while the phone is locked"); titles.lines } }, check = { root, ctx ->
        // The lock-screen rule: no row, no title, no text of any note is in the tree.
        val texts = mutableListOf<String>()
        fun walk(v: View) { if (v.visibility == View.VISIBLE && v is TextView) texts += v.text.toString(); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(root)
        assertTrue(texts.none { t -> titles.lines.any { it.title == t } })
        assertTrue(texts.any { it.startsWith(ctx.getString(org.desklink.mobile.R.string.widget_notes_locked)) })
    })
    @Test fun notesUnlockedThroughTheRealPathShowsTheTitles() {
        val v = NotesWidget().renderWith(base, SizeF(110f, 260f), locked = false, unavailable = false) { titles.lines }.apply(base, FrameLayout(base))
        assertEquals("Shopping list", (find(v, org.desklink.mobile.R.id.n_row1) as TextView).text.toString())
    }
    @Test fun notesEmpty() = shoot("notes-empty", w2, h(2), { NotesWidget().draw(it, NotesView.Empty) })

    @Test fun transfersRunning() = shoot("transfers-running", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Running("holiday-photos.zip", true, 42), "Bucket · 120 MB") })
    @Test fun transfersLast() = shoot("transfers-last", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Last("invoice-march.pdf", false, System.currentTimeMillis() - 5 * 60_000), "Bucket · 120 MB") })
    @Test fun transfersIdle() = shoot("transfers-idle", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Idle, null) })

    @Test fun terminalsReady() = shoot("terminals-ready", w2, h(1), { TerminalsWidget().render(it, live, SizeF(110f, 40f)) })
    @Test fun terminalsNone() = shoot("terminals-none-open", w2, h(1), { TerminalsWidget().render(it, live.copy(terminalCount = 0), SizeF(110f, 40f)) })
    @Test fun terminalsOff() = shoot("terminals-access-off", w2, h(1), { TerminalsWidget().render(it, live.copy(terminals = TerminalAccess.OFF), SizeF(110f, 40f)) })
    @Test fun terminalsUnreachable() = shoot("terminals-unreachable", w2, h(1), { TerminalsWidget().render(it, down, SizeF(110f, 40f)) })

    @Test
    fun theLayoutsHoldTheirIds() {
        val v = QuickActionsWidget().render(base, live, SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals(View.VISIBLE, find(v, org.desklink.mobile.R.id.act_lock).visibility)
        val noLock = QuickActionsWidget().render(base, live.copy(lock = false), SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals(View.GONE, find(noLock, org.desklink.mobile.R.id.act_lock).visibility)
    }
}

/** Android 14: wallpaper colours (the system accent palette; Robolectric's default one). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MaterialYouWidgetShots : WidgetShots() { override val form = "m3" }
