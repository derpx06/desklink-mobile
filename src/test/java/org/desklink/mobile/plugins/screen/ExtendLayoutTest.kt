package org.desklink.mobile.plugins.screen

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendLayoutTest {
    @Test
    fun portraitUntilTheGateHasPassedThenLockedToLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ExtendLayout.orientation(gateOpen = false))
        // Sensor landscape: either landscape side, never portrait, whatever the auto-rotate switch says.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ExtendLayout.orientation(gateOpen = true))
    }

    @Test
    fun onlyALandscapeSizeIsTheViewArea() {
        assertEquals(2400 to 1080, ExtendLayout.publishable(2400, 1080))
        // A portrait size seen while the activity is still turning, or a square or empty one, is not asked for.
        assertNull(ExtendLayout.publishable(1080, 2400))
        assertNull(ExtendLayout.publishable(1080, 1080))
        assertNull(ExtendLayout.publishable(0, 0))
    }

    @Test
    fun theRequestWaitsForTheLandscapeAreaButNotForever() {
        assertFalse(ExtendLayout.readyToAsk(null, 0))
        assertFalse(ExtendLayout.readyToAsk(1080 to 2400, 500))
        assertTrue(ExtendLayout.readyToAsk(2400 to 1080, 0))
        assertTrue(ExtendLayout.readyToAsk(null, ExtendLayout.AREA_WAIT_MS))
    }
}
