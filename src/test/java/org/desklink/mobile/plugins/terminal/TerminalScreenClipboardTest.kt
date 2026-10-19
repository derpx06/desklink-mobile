/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * The emulator decodes OSC 52 with `android.util.Base64`, which needs real Android classes, hence
 * Robolectric. A program on the desktop must not be able to set, or read, the phone's clipboard.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalScreenClipboardTest {
    @Test
    fun anOsc52CopyRequestCannotTouchTheClipboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("user", "what the user copied"))

        val replies = mutableListOf<String>()
        val screen = TerminalScreen(6, 20, reply = { replies += String(it) })
        val payload = Base64.getEncoder().encodeToString("rm -rf ~".toByteArray())
        screen.write("\u001b]52;c;$payload\u0007after".toByteArray())
        // ... and a request to read it back.
        screen.write("\u001b]52;c;?\u0007".toByteArray())

        assertEquals("what the user copied", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(replies.none { it.contains("52") })
        assertEquals("after", screen.tailText(6).trimEnd())
    }
}
