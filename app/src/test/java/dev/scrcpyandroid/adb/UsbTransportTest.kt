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
    var outError: Boolean = false,
    var zeroReads: Int = 0,
    val blockUntilClosed: Boolean = false,
) : UsbIo {
    val inLens = mutableListOf<Int>()
    val entered = java.util.concurrent.CountDownLatch(1)
    private val closeLatch = java.util.concurrent.CountDownLatch(1)
    val outLengths = mutableListOf<Int>()
    val outBytes = java.io.ByteArrayOutputStream()
    var inPos = 0
    var closed = false

    override fun bulkOut(buf: ByteArray, off: Int, len: Int): Int {
        outLengths += len
        if (outError) return -1
        outBytes.write(buf, off, len)
        return len
    }

    override fun bulkIn(buf: ByteArray, len: Int): Int {
        inLens += len
        if (blockUntilClosed) {
            entered.countDown()
            closeLatch.await()
            return -1
        }
        if (inError) return -1
        if (zeroReads > 0) {
            zeroReads--
            return 0
        }
        val n = minOf(maxPacketSize, len, inData.size - inPos)
        System.arraycopy(inData, inPos, buf, 0, n)
        inPos += n
        return n
    }

    override fun close() {
        closed = true
        closeLatch.countDown()
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

    @Test(timeout = 5000)
    fun closeAbortsBlockedRead() {
        val io = FakeUsbIo(blockUntilClosed = true)
        val t = UsbTransport(io, "usb:fake")
        var result: Throwable? = null
        val th = Thread {
            result = try {
                runBlocking { t.readFully(24) }
                null
            } catch (e: Throwable) {
                e
            }
        }
        th.start()
        assertTrue(io.entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        t.close()
        th.join(3000)
        assertTrue(result is IOException)
    }

    @Test
    fun zeroLengthReadIsRetried() = runBlocking {
        val data = ByteArray(100) { it.toByte() }
        val t = UsbTransport(FakeUsbIo(inData = data, zeroReads = 3), "usb:fake")
        assertArrayEquals(data, t.readFully(100))
    }

    @Test
    fun bulkOutFailureThrows() = runBlocking {
        val t = UsbTransport(FakeUsbIo(outError = true), "usb:fake")
        try {
            t.write(ByteArray(10))
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("USB transfer failed", e.message)
        }
    }

    @Test
    fun ioAfterCloseThrows() = runBlocking {
        val t = UsbTransport(FakeUsbIo(inData = ByteArray(1000)), "usb:fake")
        t.readFully(10) // leaves cached surplus
        t.close()
        try {
            t.readFully(10)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        try {
            t.write(ByteArray(10))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun bulkInLenIsMultipleOfMaxPacketSize() = runBlocking {
        for (mps in listOf(64, 512)) {
            val io = FakeUsbIo(maxPacketSize = mps, inData = ByteArray(3000))
            UsbTransport(io, "usb:fake").readFully(3000)
            assertTrue(io.inLens.isNotEmpty())
            for (l in io.inLens) {
                assertTrue("len=$l mps=$mps", l >= mps && l % mps == 0)
            }
        }
    }
}
