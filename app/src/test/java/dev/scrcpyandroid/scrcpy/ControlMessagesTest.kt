package dev.scrcpyandroid.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class ControlMessagesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun keycodeLayout() {
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0), ControlMessages.keycode(0, 4))
    }

    @Test
    fun keycodeCarriesRepeatAndMeta() {
        val b = ByteBuffer.wrap(ControlMessages.keycode(1, 66, repeat = 2, metaState = 0x1000))
        assertEquals(0, b.get().toInt())
        assertEquals(1, b.get().toInt())
        assertEquals(66, b.int)
        assertEquals(2, b.int)
        assertEquals(0x1000, b.int)
    }

    @Test
    fun touchCarriesScreenSize() {
        val m = ControlMessages.touch(0, 0L, 100, 200, 1080, 2400, 1f)
        assertEquals(32, m.size)
        val b = ByteBuffer.wrap(m)
        assertEquals(2, b.get(0).toInt())
        assertEquals(0, b.get(1).toInt())
        assertEquals(0L, b.getLong(2))
        assertEquals(100, b.getInt(10))
        assertEquals(200, b.getInt(14))
        assertEquals(1080, b.getShort(18).toInt() and 0xFFFF)
        assertEquals(2400, b.getShort(20).toInt() and 0xFFFF)
        assertEquals(0xFFFF, b.getShort(22).toInt() and 0xFFFF)
        assertEquals(0, b.getInt(24))
        assertEquals(0, b.getInt(28))
    }

    @Test
    fun touchEncodesPressureAndPointerAndButtons() {
        val b = ByteBuffer.wrap(ControlMessages.touch(2, -1L, 5, 6, 10, 20, 0.5f, actionButton = 1, buttons = 3))
        assertEquals(2, b.get(1).toInt())
        assertEquals(-1L, b.getLong(2))
        assertEquals(0x8000, b.getShort(22).toInt() and 0xFFFF)
        assertEquals(1, b.getInt(24))
        assertEquals(3, b.getInt(28))
        val zero = ByteBuffer.wrap(ControlMessages.touch(1, 0L, 0, 0, 1, 1, 0f))
        assertEquals(0, zero.getShort(22).toInt())
    }

    @Test
    fun textLayout() {
        val parts = ControlMessages.text("ab")
        assertEquals(1, parts.size)
        assertArrayEquals(bytes(1, 0, 0, 0, 2, 'a'.code, 'b'.code), parts[0])
    }

    @Test
    fun textSplitsAt300BytesOnCharBoundary() {
        // 400 x 3 bytes = 1200 bytes -> 4 chunks of exactly 300 bytes (100 chars) each.
        val s = "中".repeat(400)
        val parts = ControlMessages.text(s)
        assertEquals(4, parts.size)
        assertEquals(300, ByteBuffer.wrap(parts[0]).getInt(1))
        assertEquals(1 + 4 + 300, parts[0].size)
        val joined = parts.joinToString("") { p ->
            val len = ByteBuffer.wrap(p).getInt(1)
            assertEquals(1, p[0].toInt())
            assertEquals(p.size - 5, len)
            String(p, 5, len, Charsets.UTF_8)
        }
        assertEquals(s, joined)
    }

    @Test
    fun textChunkStopsBeforeAMultiByteCharThatWouldOverflow() {
        // 1 + 100 x 3 = 301 bytes: 300 would cut the last "中", so the first chunk holds 298 bytes.
        val s = "a" + "中".repeat(100)
        val parts = ControlMessages.text(s)
        assertEquals(2, parts.size)
        assertEquals(298, ByteBuffer.wrap(parts[0]).getInt(1))
        assertEquals("中", String(parts[1], 5, parts[1].size - 5, Charsets.UTF_8))
    }

    @Test
    fun textNeverSplitsSurrogatePair() {
        // 299 ASCII bytes followed by a 4-byte emoji: the emoji must move to the next chunk.
        val s = "a".repeat(299) + "😀" + "b"
        val parts = ControlMessages.text(s)
        assertEquals(2, parts.size)
        assertEquals(299, ByteBuffer.wrap(parts[0]).getInt(1))
        assertEquals("😀b", String(parts[1], 5, parts[1].size - 5, Charsets.UTF_8))
    }

    @Test
    fun emptyTextProducesNoMessages() {
        assertEquals(0, ControlMessages.text("").size)
    }

    @Test
    fun setDisplayPowerOff() {
        assertArrayEquals(bytes(10, 0), ControlMessages.setDisplayPower(false))
        assertArrayEquals(bytes(10, 1), ControlMessages.setDisplayPower(true))
    }
}
