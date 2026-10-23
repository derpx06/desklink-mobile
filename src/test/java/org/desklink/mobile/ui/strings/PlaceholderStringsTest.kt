/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.strings

import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.R
import org.desklink.mobile.helpers.DeskNames
import org.desklink.mobile.ui.shots.BLANK_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every string with a placeholder, rendered with a computer's name: none may read as if a name were missing. */
@RunWith(RobolectricTestRunner::class)
class PlaceholderStringsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val placeholder = Regex("""%(?:(\d+)\$)?([sd])""")

    @Test
    fun everyPlaceholderStringReadsCorrectlyWithANameSet() {
        var checked = 0
        for (field in R.string::class.java.fields) {
            val raw = context.getString(field.getInt(null))
            val found = placeholder.findAll(raw).toList()
            if (found.isEmpty()) continue
            val args = Array<Any>(found.maxOf { (it.groupValues[1].toIntOrNull() ?: 1) }) { "arch-desk" }
            found.forEach { m -> if (m.groupValues[2] == "d") args[(m.groupValues[1].toIntOrNull() ?: 1) - 1] = 3 }
            val rendered = String.format(raw, *args)
            assertFalse("${field.name} reads wrongly with a name: \"$rendered\"", BLANK_NAME.containsMatchIn(rendered))
            checked++
        }
        assertTrue("the placeholder strings were found ($checked)", checked > 100)
    }

    @Test
    fun aBlankNameBecomesYourComputer() {
        assertEquals("your computer", DeskNames.fallbackFor(null))
        assertEquals("your computer", DeskNames.fallbackFor("  "))
        assertEquals("arch-desk", DeskNames.fallbackFor("arch-desk"))
        // The sentence from the Bucket's empty state, with no device known.
        val sentence = context.getString(R.string.bucket_hero_title, DeskNames.of("unknown-device"))
        assertEquals("One place for anything you want on your computer", sentence)
    }
}
