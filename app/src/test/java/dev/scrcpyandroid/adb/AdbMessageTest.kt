package dev.scrcpyandroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbMessageTest {

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun encodeCnxn() {
        val payload = "host::\u0000".toByteArray()
        val bytes = AdbMessage(Adb.CNXN, Adb.VERSION, Adb.MAX_PAYLOAD, payload).encode()

        assertEquals(31, bytes.size)
        assertArrayEquals(
            byteArrayOf(0x43, 0x4E, 0x58, 0x4E),
            bytes.copyOfRange(0, 4),
        )
        assertEquals(7, leInt(bytes, 12))

        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        assertEquals(sum, leInt(bytes, 16))

        assertEquals(Adb.CNXN xor -1, leInt(bytes, 20))
        assertArrayEquals(payload, bytes.copyOfRange(24, 31))
    }

    @Test
    fun decodeRoundTrip() {
        val messages = listOf(
            AdbMessage(Adb.CNXN, Adb.VERSION, Adb.MAX_PAYLOAD, "host::\u0000".toByteArray()),
            AdbMessage(Adb.OPEN, 7, 0),
            AdbMessage(Adb.WRTE, -3, 42, byteArrayOf(1, 2, 3, -1, -128)),
            AdbMessage(Adb.AUTH, Adb.AUTH_TOKEN, 0, ByteArray(256) { it.toByte() }),
        )
        for (msg in messages) {
            val bytes = msg.encode()
            val header = decodeHeader(bytes.copyOfRange(0, Adb.HEADER_SIZE))
            assertEquals(AdbHeader(msg.command, msg.arg0, msg.arg1, msg.payload.size), header)
        }
    }

    @Test
    fun badMagicThrows() {
        val bytes = AdbMessage(Adb.OKAY, 1, 2).encode()
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        assertThrows(AdbProtocolException::class.java) {
            decodeHeader(bytes.copyOfRange(0, Adb.HEADER_SIZE))
        }
    }
}
