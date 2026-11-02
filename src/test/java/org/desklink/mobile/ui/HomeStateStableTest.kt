/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.compose.screen.home.HomeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Home is rebuilt from the devices every second; it may only recompose when something it shows changed. */
class HomeStateStableTest {
    @Test
    fun anUnknownPositionTimeIsZeroSoTwoBuildsASecondApartAreEqual() {
        assertEquals(0L, HomeModel.positionStamp(null))
        assertEquals(1234L, HomeModel.positionStamp(1234L))
    }

    @Test
    fun theHomeStateIsNeverBuiltFromTheWallClock() {
        val source = File("src/main/java/org/desklink/mobile/ui/compose/screen/home/HomeModel.kt").readText()
        assertFalse("a clock value in the state would change it every second", source.contains("currentTimeMillis"))
    }
}
