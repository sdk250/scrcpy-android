package dev.scrcpyandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeometryTest {

    @Test
    fun fitPortraitIntoLandscapeLetterboxes() {
        val r = fitCenter(1080, 2400, 2400, 1080)
        assertEquals(1080f, r.height, 1e-3f)
        assertEquals(1080f * 1080f / 2400f, r.width, 1e-3f)
        assertEquals((2400f - r.width) / 2f, r.left, 1e-3f)
        assertEquals(0f, r.top, 1e-3f)
    }

    @Test
    fun fitKeepsAspectExactly() {
        val rnd = Random(42)
        repeat(100) {
            val srcW = rnd.nextInt(1, 4000)
            val srcH = rnd.nextInt(1, 4000)
            val dstW = rnd.nextInt(1, 4000)
            val dstH = rnd.nextInt(1, 4000)
            val r = fitCenter(srcW, srcH, dstW, dstH)
            assertTrue("aspect $srcW x $srcH into $dstW x $dstH", Math.abs(r.width / r.height - srcW.toFloat() / srcH) < 1e-3f)
            assertTrue(r.width <= dstW + 1e-3f)
            assertTrue(r.height <= dstH + 1e-3f)
            val touchesEdge = Math.abs(r.width - dstW) < 1e-3f || Math.abs(r.height - dstH) < 1e-3f
            assertTrue("no edge touched for $srcW x $srcH into $dstW x $dstH", touchesEdge)
        }
    }

    @Test
    fun fitWithNonPositiveDimensionsIsEmpty() {
        assertEquals(FitRect(0f, 0f, 0f, 0f), fitCenter(0, 100, 100, 100))
        assertEquals(FitRect(0f, 0f, 0f, 0f), fitCenter(100, -1, 100, 100))
        assertEquals(FitRect(0f, 0f, 0f, 0f), fitCenter(100, 100, 0, 100))
        assertEquals(FitRect(0f, 0f, 0f, 0f), fitCenter(100, 100, 100, -5))
    }

    @Test
    fun mapsCenterAndCorners() {
        val m = TouchMapper()
        m.videoW = 1080; m.videoH = 2400
        m.viewW = 2400; m.viewH = 1080
        assertEquals(540 to 1200, m.map(1200f, 540f))
        val r = fitCenter(1080, 2400, 2400, 1080)
        assertEquals(0 to 0, m.map(r.left, r.top))
    }

    @Test
    fun outsideContentReturnsNull() {
        val m = TouchMapper()
        m.videoW = 1080; m.videoH = 2400
        m.viewW = 2400; m.viewH = 1080
        assertNull(m.map(100f, 500f))
        assertNull(m.map(2300f, 500f))
    }

    @Test
    fun noVideoReturnsNull() {
        val m = TouchMapper()
        m.viewW = 2400; m.viewH = 1080
        assertNull(m.map(1200f, 540f))
        assertNull(m.clampToContent(1200f, 540f))
    }

    @Test
    fun clampToContentMapsBlackBarPointsOntoEdges() {
        val m = TouchMapper()
        m.videoW = 1080; m.videoH = 2400
        m.viewW = 2400; m.viewH = 1080
        assertEquals(0 to 0, m.clampToContent(0f, 0f))
        assertEquals(1079 to 2399, m.clampToContent(2400f, 1080f))
    }

    @Test
    fun mapsWithLatestVideoSizeAfterRotation() {
        val m = TouchMapper()
        m.viewW = 2400; m.viewH = 1080
        m.videoW = 1080; m.videoH = 2400
        assertEquals(540 to 1200, m.map(1200f, 540f))
        m.videoW = 2400; m.videoH = 1080
        assertEquals(1200 to 540, m.map(1200f, 540f))
    }
}
