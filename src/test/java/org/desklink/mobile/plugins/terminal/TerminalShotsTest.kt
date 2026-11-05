/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders recorded programs the way the phone draws them (a 360 dp wide screen) into
 * `~/.cache/android-ui/terminals/`, for review: `<case>.png` is the phone-sized grid at the normal font next
 * to the desk-sized grid with "fit width" (a smaller real font). Nothing here asserts pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class TerminalShotsTest {
    private val host = object : TerminalView.Host {
        override fun send(bytes: ByteArray) = Unit
        override fun takeModifiers() = TerminalView.Modifiers(ctrl = false, alt = false)
        override fun onGridChanged(rows: Int, cols: Int) = Unit
        override fun onTap() = Unit
        override fun onFontSizeChanged(sizePx: Int) = Unit
    }

    private val dir = File(System.getProperty("desklink.terminals") ?: (System.getProperty("user.home") + "/.cache/android-ui/terminals")).also { it.mkdirs() }

    private fun shot(name: String, cols: Int, fit: Boolean, heightPx: Int = 1800): Bitmap {
        val case = TerminalGrid.cases().first { it.name == name && it.cols == cols }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val screen = TerminalScreen(TerminalGrid.ROWS, cols, reply = {})
        TerminalGrid.play(screen, case.bytes, case.resizes)
        val view = TerminalView(activity, host)
        view.fitWidth = fit
        view.attach(screen)
        val width = 1080
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, heightPx)
        val bitmap = Bitmap.createBitmap(width, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun sheet(name: String, vararg panels: Pair<String, Bitmap>) {
        val gap = 24
        val label = 48
        val w = panels.sumOf { it.second.width } + gap * (panels.size + 1)
        val h = panels.maxOf { it.second.height } + label + gap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(60, 60, 64))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        var x = gap
        for ((title, bmp) in panels) {
            c.drawText(title, x.toFloat(), 36f, p)
            c.drawBitmap(bmp, x.toFloat(), label.toFloat(), null)
            x += bmp.width + gap
        }
        FileOutputStream(File(dir, "$name.png")).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun both(name: String, phoneCols: Int = 40, deskCols: Int = 120, rows: Int = 1800) = sheet(
        name,
        "$name $phoneCols columns, normal font" to shot(name, phoneCols, fit = false, heightPx = rows),
        "$name $deskCols columns, fit width" to shot(name, deskCols, fit = true, heightPx = rows),
    )

    @Test fun claude() = both("claude")
    @Test fun codex() = both("codex")
    @Test fun ink() = both("ink")
    @Test fun ratatui() = both("ratatui")
    @Test fun btop() = both("btop-like")
    @Test fun lazygit() = both("lazygit-like")
    @Test fun tmux() = both("tmux-like")
    @Test fun htop() = both("htop")
    @Test fun vim() = both("vim")
    @Test fun nano() = both("nano")
    @Test fun less() = both("less")
    @Test fun curses() = both("curses")
    @Test fun fzf() = both("fzf-like")
    @Test fun wide() = both("wide")
    @Test fun sgr() = both("sgr")
    @Test fun edit() = both("edit")
    @Test fun inkNaive() = both("ink-naive")
    @Test fun prompt() = both("prompt")
    @Test fun lsColor() = both("ls-color")

    /** A 60-column grid on a 360 dp phone: panned at the normal font (left edge) and fitted. */
    @Test fun sixtyColumns() = sheet(
        "ratatui-60",
        "ratatui 60 columns, normal font (panned)" to shot("ratatui", 60, fit = false),
        "ratatui 60 columns, fit width" to shot("ratatui", 60, fit = true),
    )
}
