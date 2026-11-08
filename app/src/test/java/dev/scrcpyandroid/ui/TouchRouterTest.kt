package dev.scrcpyandroid.ui

import dev.scrcpyandroid.scrcpy.ControlMessages
import dev.scrcpyandroid.scrcpy.TouchMapper
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_CANCEL
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_DOWN
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_MOVE
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_POINTER_DOWN
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_POINTER_UP
import dev.scrcpyandroid.ui.TouchRouter.Companion.ACTION_UP
import dev.scrcpyandroid.ui.TouchRouter.Pointer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TouchRouterTest {

    /** Decoded inject-touch message. */
    private data class Touch(val action: Int, val id: Long, val x: Int, val y: Int, val w: Int, val h: Int, val pressure: Int)

    private fun decode(b: ByteArray): Touch {
        assertEquals(32, b.size)
        assertEquals(ControlMessages.TYPE_INJECT_TOUCH_EVENT, b[0].toInt())
        val bb = ByteBuffer.wrap(b)
        return Touch(
            action = bb.get(1).toInt(),
            id = bb.getLong(2),
            x = bb.getInt(10),
            y = bb.getInt(14),
            w = bb.getShort(18).toInt() and 0xFFFF,
            h = bb.getShort(20).toInt() and 0xFFFF,
            pressure = bb.getShort(22).toInt() and 0xFFFF,
        )
    }

    /**
     * View 1000x1000, video 500x1000 (portrait) -> content rect x in [250, 750), full height,
     * scale 1 view px = 1 device px... (500 device px over 500 view px).
     */
    private fun portraitRouter(): Pair<TouchMapper, TouchRouter> {
        val m = TouchMapper().apply {
            viewW = 1000; viewH = 1000; videoW = 500; videoH = 1000
        }
        return m to TouchRouter(m)
    }

    private fun TouchRouter.ev(action: Int, index: Int, vararg p: Pointer) = onEvent(action, index, p.toList()).map(::decode)

    @Test
    fun singleTouchInsideMapsDownMoveUp() {
        val (_, r) = portraitRouter()
        val down = r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 0.5f))
        assertEquals(listOf(Touch(0, 0, 50, 100, 500, 1000, 0x8000)), down)
        val move = r.ev(ACTION_MOVE, 0, Pointer(0, 400f, 200f, 0.5f))
        assertEquals(listOf(Touch(2, 0, 150, 200, 500, 1000, 0x8000)), move)
        val up = r.ev(ACTION_UP, 0, Pointer(0, 400f, 200f, 0.5f))
        assertEquals(listOf(Touch(1, 0, 150, 200, 500, 1000, 0x8000)), up)
    }

    @Test
    fun pointerStartingOnBarsIsIgnoredForWholeGesture() {
        val (_, r) = portraitRouter()
        assertTrue(r.ev(ACTION_DOWN, 0, Pointer(0, 100f, 500f, 1f)).isEmpty())
        // Moves into the content: still ignored.
        assertTrue(r.ev(ACTION_MOVE, 0, Pointer(0, 500f, 500f, 1f)).isEmpty())
        assertTrue(r.ev(ACTION_UP, 0, Pointer(0, 500f, 500f, 1f)).isEmpty())
    }

    @Test
    fun pointerStartingInsideIsClampedWhenMovingOntoBars() {
        val (_, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        val move = r.ev(ACTION_MOVE, 0, Pointer(0, 900f, 100f, 1f))
        assertEquals(listOf(Touch(2, 0, 499, 100, 500, 1000, 0xFFFF)), move)
        val up = r.ev(ACTION_UP, 0, Pointer(0, 0f, 100f, 1f))
        assertEquals(listOf(Touch(1, 0, 0, 100, 500, 1000, 0xFFFF)), up)
    }

    @Test
    fun multiPointerDownMoveUp() {
        val (_, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        val pd = r.ev(ACTION_POINTER_DOWN, 1, Pointer(0, 300f, 100f, 1f), Pointer(3, 600f, 700f, 1f))
        assertEquals(listOf(Touch(0, 3, 350, 700, 500, 1000, 0xFFFF)), pd)
        val move = r.ev(ACTION_MOVE, 0, Pointer(0, 310f, 110f, 1f), Pointer(3, 610f, 710f, 1f))
        assertEquals(
            listOf(Touch(2, 0, 60, 110, 500, 1000, 0xFFFF), Touch(2, 3, 360, 710, 500, 1000, 0xFFFF)),
            move,
        )
        val pu = r.ev(ACTION_POINTER_UP, 0, Pointer(0, 310f, 110f, 1f), Pointer(3, 610f, 710f, 1f))
        assertEquals(listOf(Touch(1, 0, 60, 110, 500, 1000, 0xFFFF)), pu)
        val move2 = r.ev(ACTION_MOVE, 0, Pointer(3, 620f, 720f, 1f))
        assertEquals(listOf(Touch(2, 3, 370, 720, 500, 1000, 0xFFFF)), move2)
        val up = r.ev(ACTION_UP, 0, Pointer(3, 620f, 720f, 1f))
        assertEquals(listOf(Touch(1, 3, 370, 720, 500, 1000, 0xFFFF)), up)
    }

    @Test
    fun secondPointerOnBarsIsIgnoredWhileFirstStillWorks() {
        val (_, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        assertTrue(r.ev(ACTION_POINTER_DOWN, 1, Pointer(0, 300f, 100f, 1f), Pointer(1, 50f, 100f, 1f)).isEmpty())
        val move = r.ev(ACTION_MOVE, 0, Pointer(0, 320f, 100f, 1f), Pointer(1, 400f, 100f, 1f))
        assertEquals(listOf(Touch(2, 0, 70, 100, 500, 1000, 0xFFFF)), move)
        assertTrue(r.ev(ACTION_POINTER_UP, 1, Pointer(0, 320f, 100f, 1f), Pointer(1, 400f, 100f, 1f)).isEmpty())
    }

    @Test
    fun cancelReleasesAllActivePointers() {
        val (_, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        r.ev(ACTION_POINTER_DOWN, 1, Pointer(0, 300f, 100f, 1f), Pointer(1, 400f, 100f, 1f))
        val cancel = r.ev(ACTION_CANCEL, 0, Pointer(0, 300f, 100f, 1f), Pointer(1, 400f, 100f, 1f))
        assertEquals(listOf(1, 1), cancel.map { it.action })
        assertEquals(setOf(0L, 1L), cancel.map { it.id }.toSet())
        // Nothing is active any more.
        assertTrue(r.ev(ACTION_MOVE, 0, Pointer(0, 300f, 100f, 1f)).isEmpty())
    }

    @Test
    fun pressureIsClampedToUnitRange() {
        val (_, r) = portraitRouter()
        assertEquals(0xFFFF, r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1.7f)).single().pressure)
        assertEquals(0, r.ev(ACTION_MOVE, 0, Pointer(0, 300f, 100f, -0.2f)).single().pressure)
    }

    @Test
    fun screenSizeFollowsSessionSizeChangeMidGesture() {
        val (m, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        // Session rotates to landscape 1000x500: content now y in [250, 750), full width.
        m.videoW = 1000; m.videoH = 500
        val move = r.ev(ACTION_MOVE, 0, Pointer(0, 300f, 300f, 1f))
        assertEquals(listOf(Touch(2, 0, 300, 50, 1000, 500, 0xFFFF)), move)
        // A move onto the (new) bars is clamped in the new geometry.
        val bar = r.ev(ACTION_MOVE, 0, Pointer(0, 300f, 100f, 1f))
        assertEquals(listOf(Touch(2, 0, 300, 0, 1000, 500, 0xFFFF)), bar)
    }

    @Test
    fun nothingIsSentWithoutVideo() {
        val r = TouchRouter(TouchMapper().apply { viewW = 1000; viewH = 1000 })
        assertTrue(r.onEvent(ACTION_DOWN, 0, listOf(Pointer(0, 300f, 100f, 1f))).isEmpty())
    }

    @Test
    fun newDownStartsAFreshGesture() {
        val (_, r) = portraitRouter()
        r.ev(ACTION_DOWN, 0, Pointer(0, 300f, 100f, 1f))
        // Lost UP (should not happen, but must not leak): a new DOWN on the bars resets tracking.
        assertTrue(r.ev(ACTION_DOWN, 0, Pointer(0, 100f, 100f, 1f)).isEmpty())
        assertTrue(r.ev(ACTION_MOVE, 0, Pointer(0, 300f, 100f, 1f)).isEmpty())
    }

    @Test
    fun encodesExactlyLikeControlMessages() {
        val (_, r) = portraitRouter()
        val raw = r.onEvent(ACTION_DOWN, 0, listOf(Pointer(7, 300f, 100f, 0.5f))).single()
        assertArrayEquals(ControlMessages.touch(0, 7, 50, 100, 500, 1000, 0.5f), raw)
    }
}
