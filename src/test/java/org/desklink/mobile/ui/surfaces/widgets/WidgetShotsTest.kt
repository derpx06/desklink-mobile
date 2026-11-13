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

    /** [computer]: the name every tappable's spoken name must carry (a button says which computer it acts on). */
    protected fun shoot(name: String, widthDp: Float, heightDp: Float, build: (Context) -> RemoteViews, computer: String? = null, check: (View, Context) -> Unit = { _, _ -> }) {
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
            // The picture first, so a failing check still leaves it to look at.
            val bitmap = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
            stage.draw(Canvas(bitmap))
            FileOutputStream(File(ShotDir.dir, "widget-$form-$name-${if (dark) "dark" else "light"}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(widget, context)
            accessible(widget, context, name, computer)
        }
    }

    private fun accessible(root: View, context: Context, name: String, computer: String?) {
        val density = context.resources.displayMetrics.density
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.hasOnClickListeners()) {
                val named = !v.contentDescription.isNullOrBlank() || (v as? TextView)?.text?.isNotBlank() == true
                assertTrue("$name: a tappable without a name (id ${v.id})", named)
                val spoken = v.contentDescription?.toString().orEmpty()
                assertTrue("$name: \"$spoken\" shows a blank where a name should be", !org.desklink.mobile.ui.shots.BLANK_NAME.containsMatchIn(spoken))
                if (computer != null) assertTrue("$name: \"$spoken\" does not say which computer", spoken.contains(computer))
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


    @Test fun statusConnected() = shoot("status-connected", w2, h(1), { ComputerStatusWidget().render(it, live, SizeF(110f, 40f)) }, "arch-desk")
    @Test fun statusConnectedWide() = shoot("status-wide", w3, h(1), { ComputerStatusWidget().render(it, live, SizeF(180f, 40f)) }, "arch-desk")
    @Test fun statusAway() = shoot("status-relay", w3, h(1), { ComputerStatusWidget().render(it, away, SizeF(180f, 40f)) }, "arch-desk")
    @Test fun statusFullWords() = shoot("status-4x1-relay", w4, h(1), { ComputerStatusWidget().render(it, away, SizeF(250f, 40f)) }, "arch-desk")
    @Test fun statusCantReach() = shoot("status-cant-reach", w3, h(1), { ComputerStatusWidget().render(it, down, SizeF(180f, 40f)) }, "arch-desk")
    @Test fun statusNone() = shoot("status-none", w2, h(1), { ComputerStatusWidget().render(it, null, SizeF(110f, 40f)) })

    @Test fun actionsSmall() = shoot("actions-4x1", w4, h(1), { QuickActionsWidget().render(it, live, SizeF(250f, 40f)) }, "arch-desk")
    @Test fun actionsLarge() = shoot("actions-4x2", w4, h(2), { QuickActionsWidget().render(it, live, SizeF(250f, 110f)) }, "arch-desk")
    @Test fun actionsLargeNoLock() = shoot("actions-4x2-no-lock", w4, h(2), { QuickActionsWidget().render(it, live.copy(lock = false), SizeF(250f, 110f)) }, "arch-desk")
    @Test fun actionsUnreachable() = shoot("actions-4x2-unreachable", w4, h(2), { QuickActionsWidget().render(it, down.copy(lock = true), SizeF(250f, 110f)) }, "arch-desk")
    @Test fun actionsUnreachableSmall() = shoot("actions-4x1-unreachable", w4, h(1), { QuickActionsWidget().render(it, connecting, SizeF(250f, 40f)) }, "arch-desk")

    private val titles = NotesView.Titles(listOf("Shopping list", "Meeting notes", "Ideas for the weekend", "Wi‑Fi passwords").map { NoteLine(it, it) }, 3)

    @Test fun notesSmall() = shoot("notes-2x2", w2, h(2), { NotesWidget().draw(it, NotesView.Titles(titles.lines.take(1), 6)) })
    @Test fun notesMedium() = shoot("notes-4x3", w4, h(3), { NotesWidget().draw(it, NotesView.Titles(titles.lines.take(3), 4)) })
    @Test fun notesTall() = shoot("notes-4x4", w4, h(4), { NotesWidget().draw(it, titles) })
    // The real render path, locked: a source that fails the test if it is read at all, with notes that would show if it were.
    @Test fun notesLocked() = shoot("notes-locked", w2, h(2), { NotesWidget().renderWith(it, SizeF(110f, 160f), locked = true) { fail("the notes were read while the phone is locked"); titles.lines } }, check = { root, ctx ->
        // The lock-screen rule: no row, no title, no text of any note is in the tree.
        val texts = mutableListOf<String>()
        fun walk(v: View) { if (v.visibility != View.VISIBLE) return; if (v is TextView) texts += v.text.toString(); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(root)
        assertTrue(texts.none { t -> titles.lines.any { it.title == t } })
        assertTrue(texts.any { it.startsWith(ctx.getString(org.desklink.mobile.R.string.widget_notes_locked)) })
    })
    @Test fun notesUnlockedThroughTheRealPathShowsTheTitles() {
        val v = NotesWidget().renderWith(base, SizeF(110f, 260f), locked = false, unavailable = false) { titles.lines }.apply(base, FrameLayout(base))
        assertEquals("Shopping list", (find(v, org.desklink.mobile.R.id.n_row1_text) as TextView).text.toString())
    }
    @Test fun notesEmpty() = shoot("notes-empty", w2, h(2), { NotesWidget().draw(it, NotesView.Empty) })

    @Test fun transfersRunning() = shoot("transfers-running", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Running("holiday-photos.zip", true, 42), "Bucket · 120 MB") }, "arch-desk")
    @Test fun transfersLast() = shoot("transfers-last", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Last("invoice-march.pdf", false, System.currentTimeMillis() - 5 * 60_000), "Bucket · 120 MB") }, "arch-desk")
    @Test fun transfersIdle() = shoot("transfers-idle", w4, h(1), { TransfersWidget().draw(it, live, TransfersView.Idle, null) }, "arch-desk")

    @Test fun terminalsReady() = shoot("terminals-ready", w2, h(1), { TerminalsWidget().render(it, live, SizeF(110f, 40f)) }, "arch-desk")
    @Test fun terminalsNone() = shoot("terminals-none-open", w2, h(1), { TerminalsWidget().render(it, live.copy(terminalCount = 0), SizeF(110f, 40f)) }, "arch-desk")
    @Test fun terminalsOff() = shoot("terminals-access-off", w2, h(1), { TerminalsWidget().render(it, live.copy(terminals = TerminalAccess.OFF), SizeF(110f, 40f)) }, "arch-desk")
    @Test fun terminalsUnreachable() = shoot("terminals-unreachable", w2, h(1), { TerminalsWidget().render(it, down, SizeF(110f, 40f)) }, "arch-desk")

    private val desk = org.desklink.mobile.ui.surfaces.DeskSnapshot(
        id = "d", name = "arch-desk", link = org.desklink.mobile.ui.surfaces.DeskLink.CONNECTED, ready = true, keepAwake = null, keepAwakePending = false,
        terminals = 2, terminalAttached = true, unseenFinished = 3, mediaTitle = "Night Drive", mediaPlaying = true, lastSeen = null,
    )
    private val deskDown = desk.copy(link = org.desklink.mobile.ui.surfaces.DeskLink.OFFLINE, ready = false, terminals = 0, terminalAttached = false, unseenFinished = 0, mediaTitle = null, lastSeen = 1_762_000_000_000L)
    private fun deskWidget(layout: Int, d: org.desklink.mobile.ui.surfaces.DeskSnapshot?) = { c: Context -> org.desklink.mobile.ui.surfaces.DeskWidgetProvider.build(c, layout, d) }

    @Test fun deskSmall() = shoot("desk-2x2", w2, h(2), deskWidget(org.desklink.mobile.R.layout.widget_desk_small, desk), "arch-desk")
    @Test fun deskSmallOffline() = shoot("desk-2x2-offline", w2, h(2), deskWidget(org.desklink.mobile.R.layout.widget_desk_small, deskDown), "arch-desk")
    @Test fun deskSmallNone() = shoot("desk-2x2-none", w2, h(2), deskWidget(org.desklink.mobile.R.layout.widget_desk_small, null))
    // A 2x2 in portrait is taller than the minimum: two rows of actions.
    @Test fun deskMedium() = shoot("desk-2x2-tall", w2, 200f, deskWidget(org.desklink.mobile.R.layout.widget_desk_medium, desk), "arch-desk")
    @Test fun deskMediumOffline() = shoot("desk-2x2-tall-offline", w2, 200f, deskWidget(org.desklink.mobile.R.layout.widget_desk_medium, deskDown), "arch-desk")
    @Test fun deskLarge() = shoot("desk-4x3", w4, 260f, deskWidget(org.desklink.mobile.R.layout.widget_desk_large, desk), "arch-desk")
    @Test fun deskLargeOffline() = shoot("desk-4x3-offline", w4, 260f, deskWidget(org.desklink.mobile.R.layout.widget_desk_large, deskDown), "arch-desk")
    @Test fun deskLargeNone() = shoot("desk-4x3-none", w4, 260f, deskWidget(org.desklink.mobile.R.layout.widget_desk_large, null))

    @Test fun actionsNone() = shoot("actions-4x2-none", w4, h(2), { QuickActionsWidget().render(it, null, SizeF(250f, 110f)) })
    @Test fun transfersNone() = shoot("transfers-none", w4, h(1), { TransfersWidget().draw(it, null, TransfersView.Idle, null) })
    @Test fun terminalsNonePaired() = shoot("terminals-none-paired", w2, h(1), { TerminalsWidget().render(it, null, SizeF(110f, 40f)) })

    @Test
    fun theLayoutsHoldTheirIds() {
        val v = QuickActionsWidget().render(base, live, SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals(View.VISIBLE, find(v, org.desklink.mobile.R.id.act_lock).visibility)
        // Never hidden: a computer that does not let this phone lock it shows Lock dimmed, and says why.
        val noLock = QuickActionsWidget().render(base, live.copy(lock = false), SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals(View.VISIBLE, find(noLock, org.desklink.mobile.R.id.act_lock).visibility)
        val icon = find(noLock, org.desklink.mobile.R.id.act_lock_icon) as android.widget.ImageView
        if (android.os.Build.VERSION.SDK_INT >= 31) assertEquals(0.38f, icon.alpha, 0.01f) else assertEquals(97, icon.imageAlpha)
        assertEquals("Lock arch-desk, unavailable. Not available right now", find(noLock, org.desklink.mobile.R.id.act_lock).contentDescription)
    }

    @Test
    fun theSpokenNamesSayWhatATapDoesOnWhichComputerAndItsState() {
        val v = QuickActionsWidget().render(base, live, SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals("Open arch-desk in DeskLink. Connected over Wi‑Fi", find(v, org.desklink.mobile.R.id.w_header).contentDescription)
        assertEquals("Send clipboard to arch-desk", find(v, org.desklink.mobile.R.id.act_clipboard).contentDescription)
        val off = QuickActionsWidget().render(base, down, SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals("Open arch-desk in DeskLink. Can't reach", find(off, org.desklink.mobile.R.id.w_header).contentDescription)
        assertEquals("Ring arch-desk, unavailable. Offline", find(off, org.desklink.mobile.R.id.act_ring).contentDescription)
        val none = QuickActionsWidget().render(base, null, SizeF(250f, 110f)).apply(base, FrameLayout(base))
        assertEquals("Open DeskLink. No computer paired. Tap to pair a computer", find(none, org.desklink.mobile.R.id.w_header).contentDescription)
        assertEquals("Lock computer, unavailable. No computer paired", find(none, org.desklink.mobile.R.id.act_lock).contentDescription)
        val status = ComputerStatusWidget().render(base, live.copy(charging = true), SizeF(110f, 40f)).apply(base, FrameLayout(base))
        assertTrue(status.contentDescription.toString().startsWith("Open arch-desk in DeskLink. Connected over Wi‑Fi. Battery 84%, charging"))
        val term = TerminalsWidget().render(base, live, SizeF(110f, 40f)).apply(base, FrameLayout(base))
        assertEquals("Start a new terminal on arch-desk. 2 terminals", term.contentDescription)
        val big = org.desklink.mobile.ui.surfaces.DeskWidgetProvider.build(base, org.desklink.mobile.R.layout.widget_desk_large, desk).apply(base, FrameLayout(base))
        assertEquals("Open terminals on arch-desk. 2 terminals. one is open on this phone. 3 commands finished", find(big, org.desklink.mobile.R.id.widget_terminal_row).contentDescription)
        assertEquals("2 terminals · open here", (find(big, org.desklink.mobile.R.id.widget_terminal_text) as TextView).text.toString())
    }

    @Test
    fun hugeTextDropsTheOneRowLabelsButNotTheNames() {
        org.robolectric.RuntimeEnvironment.setFontScale(2.0f)
        val v = QuickActionsWidget().render(base, live, SizeF(250f, 40f)).apply(base, FrameLayout(base))
        assertEquals(View.GONE, find(v, org.desklink.mobile.R.id.act_screen_label).visibility)
        assertEquals("Show the screen of arch-desk", find(v, org.desklink.mobile.R.id.act_screen).contentDescription)
        org.robolectric.RuntimeEnvironment.setFontScale(1.0f)
        val w = QuickActionsWidget().render(base, live, SizeF(250f, 40f)).apply(base, FrameLayout(base))
        assertEquals(View.VISIBLE, find(w, org.desklink.mobile.R.id.act_screen_label).visibility)
    }
}

/** Android 14: wallpaper colours (the system accent palette; Robolectric's default one). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MaterialYouWidgetShots : WidgetShots() { override val form = "m3" }

/** The system's "Larger" font size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class LargeTextWidgetShots : WidgetShots() {
    override val form = "m3-font130"

    @org.junit.Before fun fontScale() = org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
}

/** The largest font size (200 %). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class HugeTextWidgetShots : WidgetShots() {
    override val form = "m3-font200"

    @org.junit.Before fun fontScale() = org.robolectric.RuntimeEnvironment.setFontScale(2.0f)
}

/** Android 11: no wallpaper colours, the DeskLink tokens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-xxhdpi")
class TokensWidgetShots : WidgetShots() { override val form = "tokens" }
