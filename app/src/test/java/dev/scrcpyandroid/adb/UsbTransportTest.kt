package dev.scrcpyandroid.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private class FakeUsbIo(
    override val maxPacketSize: Int = 512,
    var inData: ByteArray = ByteArray(0),
    var inError: Boolean = false,
) : UsbIo {
    val outLengths = mutableListOf<Int>()
    val outBytes = java.io.ByteArrayOutputStream()
    var inPos = 0
    var closed = false

    override fun bulkOut(buf: ByteArray, off: Int, len: Int): Int {
        outLengths += len
        outBytes.write(buf, off, len)
        return len
    }

    override fun bulkIn(buf: ByteArray, len: Int): Int {
        if (inError) return -1
        val n = minOf(maxPacketSize, len, inData.size - inPos)
        System.arraycopy(inData, inPos, buf, 0, n)
        inPos += n
        return n
    }

    override fun close() {
        closed = true
    }
}

class UsbTransportTest {
    @Test
    fun writeSplitsInto16kSegments() = runBlocking {
        val io = FakeUsbIo()
        val data = ByteArray(40000) { it.toByte() }
        UsbTransport(io, "usb:fake").write(data)
        assertEquals(listOf(16384, 16384, 7232), io.outLengths)
        assertArrayEquals(data, io.outBytes.toByteArray())
    }

    @Test
    fun readFullyAcrossPackets() = runBlocking {
        val data = ByteArray(1500) { (it * 7).toByte() }
        val t = UsbTransport(FakeUsbIo(inData = data), "usb:fake")
        assertArrayEquals(data, t.readFully(1500))
    }

    @Test
    fun surplusBytesAreCachedForNextRead() = runBlocking {
        val data = ByteArray(600) { it.toByte() }
        val t = UsbTransport(FakeUsbIo(inData = data), "usb:fake")
        assertArrayEquals(data.copyOfRange(0, 24), t.readFully(24))
        assertArrayEquals(data.copyOfRange(24, 600), t.readFully(576))
    }

    @Test
    fun transferErrorThrows() = runBlocking {
        val t = UsbTransport(FakeUsbIo(inError = true), "usb:fake")
        try {
            t.readFully(24)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("USB transfer failed"))
        }
    }

    @Test
    fun closeClosesIo() {
        val io = FakeUsbIo()
        UsbTransport(io, "usb:fake").close()
        assertTrue(io.closed)
    }
}
