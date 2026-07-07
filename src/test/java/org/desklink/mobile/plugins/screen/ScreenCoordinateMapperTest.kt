package org.desklink.mobile.plugins.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCoordinateMapperTest {
    @Test
    fun mapsTouchInsideLetterboxedSurfaceToRemoteCoordinates() {
        val mapper = ScreenCoordinateMapper(
            remoteWidth = 1280,
            remoteHeight = 720,
            viewWidth = 400,
            viewHeight = 400
        )

        assertEquals(ScreenPoint(640, 360), mapper.mapTouch(200f, 200f))
    }

    @Test
    fun returnsNullWhenTouchFallsOutsideDisplayedFrame() {
        val mapper = ScreenCoordinateMapper(
            remoteWidth = 1280,
            remoteHeight = 720,
            viewWidth = 400,
            viewHeight = 400
        )

        assertNull(mapper.mapTouch(200f, 20f))
    }

    @Test
    fun mapsTopLeftOfDisplayedFrameToZero() {
        val mapper = ScreenCoordinateMapper(
            remoteWidth = 1280,
            remoteHeight = 720,
            viewWidth = 400,
            viewHeight = 400
        )

        assertEquals(ScreenPoint(0, 0), mapper.mapTouch(0f, 87.5f))
    }
}
