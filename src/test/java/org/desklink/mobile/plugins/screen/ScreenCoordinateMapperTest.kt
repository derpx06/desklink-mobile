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

    /**
     * The desktop encodes at most 1280 wide but injects pointer positions in its
     * own logical space. Mapping with the *decoded frame's* size put a tap at
     * two thirds of the way across a 1920x1080 desktop; the desktop's SCREEN_READY
     * size is the coordinate space that must be used.
     */
    @Test
    fun mapsIntoTheDesktopsLogicalSpaceNotTheDownscaledStreams() {
        val mapper = ScreenCoordinateMapper(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 640,
            viewHeight = 360
        )

        assertEquals(ScreenPoint(960, 540), mapper.mapTouch(320f, 180f))
        assertEquals(ScreenPoint(1919, 1079), mapper.mapTouch(640f, 360f))
        assertEquals(ScreenPoint(0, 0), mapper.mapTouch(0f, 0f))
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
