package dev.scrcpyandroid.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class VideoStreamReaderTest {
    private fun stream(build: DataOutputStream.() -> Unit): VideoStreamReader {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).build()
        return VideoStreamReader(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun DataOutputStream.deviceMeta(name: String) {
        val field = ByteArray(64)
        name.toByteArray().copyInto(field)
        write(field)
    }

    private fun DataOutputStream.session(w: Int, h: Int, clientResized: Boolean = false) {
        writeInt(0x80000000.toInt() or (if (clientResized) 1 else 0))
        writeInt(w)
        writeInt(h)
    }

    private fun DataOutputStream.packet(ptsFlags: Long, data: ByteArray) {
        writeLong(ptsFlags)
        writeInt(data.size)
        write(data)
    }

    @Test
    fun readsHeaderAndPackets() {
        val r = stream {
            writeByte(0) // dummy byte
            deviceMeta("Pixel")
            writeInt(0x68323634) // codec id "h264"
            session(1080, 2400)
            packet(1L shl 62, byteArrayOf(1, 2, 3)) // config
            packet((1L shl 61) or 1000, byteArrayOf(4, 5)) // key frame, pts 1000
            packet(2000, byteArrayOf(6)) // plain frame
        }
        r.readDummyByte()
        assertEquals(StreamHeader("Pixel", 0x68323634, 1080, 2400), r.readHeader())

        val config = r.readPacket()!!
        assertTrue(config.isConfig)
        assertFalse(config.isKeyFrame)
        assertFalse(config.isSession)
        assertArrayEquals(byteArrayOf(1, 2, 3), config.data)

        val key = r.readPacket()!!
        assertFalse(key.isConfig)
        assertTrue(key.isKeyFrame)
        assertEquals(1000L, key.ptsUs)
        assertArrayEquals(byteArrayOf(4, 5), key.data)

        val frame = r.readPacket()!!
        assertFalse(frame.isKeyFrame)
        assertEquals(2000L, frame.ptsUs)

        assertNull(r.readPacket())
    }

    @Test
    fun midStreamSessionPacketReportsNewSize() {
        val r = stream {
            deviceMeta("Pixel")
            writeInt(0x68323635)
            session(1080, 2400)
            session(2400, 1080, clientResized = true) // rotation / resize
            packet(1L shl 62, byteArrayOf(9))
        }
        assertEquals(StreamHeader("Pixel", 0x68323635, 1080, 2400), r.readHeader())
        val s = r.readPacket()!!
        assertTrue(s.isSession)
        assertFalse(s.isConfig)
        assertEquals(SessionMeta(2400, 1080, clientResized = true), s.session)
        assertEquals(0, s.data.size)
        assertTrue(r.readPacket()!!.isConfig)
        assertNull(r.readPacket())
    }

    @Test
    fun deviceNameUsesUtf8UpToNul() {
        val r = stream {
            deviceMeta("Pixel 9 é")
            writeInt(0x68323634)
            session(1, 2)
        }
        assertEquals("Pixel 9 é", r.readHeader().deviceName)
    }

    @Test(expected = IOException::class)
    fun disabledStreamCodecFails() {
        stream {
            deviceMeta("Pixel")
            writeInt(1) // configuration error on the device
        }.readHeader()
    }

    @Test(expected = EOFException::class)
    fun truncatedPacketThrows() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).packet(5, byteArrayOf(1, 2, 3))
        // header (12) + 1 of 3 payload bytes
        VideoStreamReader(ByteArrayInputStream(bytes.toByteArray().copyOf(13))).readPacket()
    }

    @Test(expected = EOFException::class)
    fun missingDummyByteThrows() {
        VideoStreamReader(ByteArrayInputStream(ByteArray(0))).readDummyByte()
    }
}
