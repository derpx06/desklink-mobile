/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.about

import org.desklink.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutReportDetailsTest {
    private val about = AboutData("DeskLink", R.drawable.desklink_mark, "0.3.0", build = "20300 · debug", developer = "derpx06")

    /** Exactly version, Android, phone model and a count: nothing that names a person, a computer or a key. */
    @Test fun detailsCarryOnlyVersionsModelAndACount() {
        val lines = aboutReportDetails(about, pairedComputers = 2).lines()
        assertEquals(4, lines.size)
        assertEquals("DeskLink 0.3.0 (build 20300 · debug)", lines[0])
        assert(lines[1].startsWith("Android ")) { lines[1] }
        assert(lines[2].startsWith("Phone: ")) { lines[2] }
        assertEquals("Paired computers: 2", lines[3])
    }
}
