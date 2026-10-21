package org.desklink.mobile.plugins.mousepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopViewTransformTest {
    // A 1000x500 view showing a 1920x1080 desktop: fit 1000/1920, letterboxed
    // top and bottom by (500 - 562.5)/2 < 0 → width-bound? 500/1080 < 1000/1920,
    // so height-bound: fit = 500/1080, picture 888.9 wide, bars 55.6 each side.
    private val w = 1000
    private val h = 500
    private val dw = 1920
    private val dh = 1080

    @Test
    fun anUnzoomedTouchMapsThroughTheLetterbox() {
        val centre = desktopPointFor(500f, 250f, DesktopViewTransform(), w, h, dw, dh)!!
        assertEquals(960f, centre.x, 0.5f)
        assertEquals(540f, centre.y, 0.5f)
        assertNull("the side bars are not the desktop", desktopPointFor(20f, 250f, DesktopViewTransform(), w, h, dw, dh))
        val corner = desktopPointFor(55.6f, 0f, DesktopViewTransform(), w, h, dw, dh)!!
        assertEquals(0f, corner.x, 1f)
        assertEquals(0f, corner.y, 0.01f)
    }

    @Test
    fun zoomKeepsTheFocusStillAndTouchesFollowIt() {
        val zoomed = DesktopViewTransform().zoomedBy(2f, 500f, 250f, w.toFloat(), h.toFloat())
        assertEquals(2f, zoomed.scale)
        // The focus point still shows the same desktop pixel.
        val focus = desktopPointFor(500f, 250f, zoomed, w, h, dw, dh)!!
        assertEquals(960f, focus.x, 0.5f)
        assertEquals(540f, focus.y, 0.5f)
        // 100 view pixels right of it is half as far on the desktop as unzoomed.
        val right = desktopPointFor(600f, 250f, zoomed, w, h, dw, dh)!!
        assertEquals(960f + 100f * 1080f / 500f / 2f, right.x, 0.5f)
    }

    @Test
    fun zoomAndPanAreClampedSoThePictureAlwaysCoversTheView() {
        val t = DesktopViewTransform()
        assertEquals(1f, t.zoomedBy(0.5f, 0f, 0f, 1000f, 500f).scale)
        assertEquals(DesktopViewTransform.MAX_SCALE, t.zoomedBy(100f, 0f, 0f, 1000f, 500f).scale)
        val zoomed = t.zoomedBy(2f, 0f, 0f, 1000f, 500f)
        val farLeft = zoomed.pannedBy(10_000f, 10_000f, 1000f, 500f)
        assertEquals(0f, farLeft.offsetX)
        assertEquals(0f, farLeft.offsetY)
        val farRight = zoomed.pannedBy(-10_000f, -10_000f, 1000f, 500f)
        assertEquals(-1000f, farRight.offsetX)
        assertEquals(-500f, farRight.offsetY)
        assertEquals(zoomed, zoomed.zoomedBy(Float.NaN, 0f, 0f, 1000f, 500f))
    }

    @Test
    fun fillingCoversTheWholeViewLikeYouTubesZoomToFill() {
        // A 1920x1080 desktop on a 2400x1080 landscape phone: fit leaves
        // 240 px bars each side; fill scales 1.25 and crops top and bottom.
        val fill = DesktopViewTransform.filling(2400, 1080, 1920, 1080)
        assertEquals(1.25f, fill.scale, 0.001f)
        assertEquals(-300f, fill.offsetX, 0.5f)
        assertEquals(-135f, fill.offsetY, 0.5f)
        // Every corner of the view is now desktop, not a bar.
        assertEquals(0f, desktopPointFor(0f, 540f, fill, 2400, 1080, 1920, 1080)!!.x, 0.5f)
        assertEquals(1919f, desktopPointFor(2399.9f, 540f, fill, 2400, 1080, 1920, 1080)!!.x, 0.5f)
        // The view centre is still the desktop centre.
        val centre = desktopPointFor(1200f, 540f, fill, 2400, 1080, 1920, 1080)!!
        assertEquals(960f, centre.x, 0.5f)
        assertEquals(540f, centre.y, 0.5f)
        assertEquals(DesktopViewTransform(), DesktopViewTransform.filling(1920, 1080, 1920, 1080))
    }

    @Test
    fun scrollDistanceScalesWithZoom() {
        val one = desktopPixelsPerViewPixel(DesktopViewTransform(), w, h, dw, dh)
        assertEquals(1080f / 500f, one, 0.001f)
        val two = desktopPixelsPerViewPixel(DesktopViewTransform(scale = 2f), w, h, dw, dh)
        assertEquals(one / 2f, two, 0.001f)
    }
}
