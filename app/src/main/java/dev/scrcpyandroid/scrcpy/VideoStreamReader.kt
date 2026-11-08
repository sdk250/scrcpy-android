package dev.scrcpyandroid.scrcpy

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/*
 * Video socket demuxer for the scrcpy v5.0.1 protocol (server `DesktopConnection.java`,
 * `device/Streamer.java`, `video/SurfaceEncoder.java`; client `app/src/demuxer.c`). Big-endian.
 *
 *   [dummy byte 0x00]            only on the first socket in forward-tunnel mode
 *   [device name: 64 bytes]      UTF-8, NUL padded (at most 63 bytes used)
 *   [codec id: u32]              "h264"=0x68323634, "h265"=0x68323635; 0 = stream disabled, 1 = config error
 *   then a sequence of 12-byte headers:
 *     session packet  (MSB of byte 0 set): u32 flags (bit31=session, bit0=client resized), u32 width, u32 height.
 *                     Sent before the first frame and again on every encoder restart (rotation, size change).
 *     media packet    (MSB clear): u64 ptsAndFlags (bit62=config, bit61=key frame, low 61 bits=pts µs),
 *                     u32 size, then `size` payload bytes. Config packets carry no pts.
 */

/** Stream metadata; [width]/[height] come from the first session packet. */
data class StreamHeader(val deviceName: String, val codecId: Int, val width: Int, val height: Int)

/** Video size announced by a session packet (scrcpy 5.x). */
data class SessionMeta(val width: Int, val height: Int, val clientResized: Boolean)

/**
 * One unit read from the video stream. Either a media packet ([session] == null) or a mid-stream
 * session packet ([session] != null, empty [data], no pts) that announces a new video size; a new
 * config packet follows it, so the decoder must be reconfigured.
 */
class VideoPacket(
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val data: ByteArray,
    val session: SessionMeta? = null,
) {
    val isSession: Boolean get() = session != null
}

class VideoStreamReader(input: InputStream) {
    private val input = DataInputStream(input)
    private val header = ByteArray(HEADER_SIZE)

    /** Reads the connection-check byte sent on the first socket in forward-tunnel mode. */
    fun readDummyByte() {
        input.readUnsignedByte()
    }

    /** Reads device meta, codec id and the initial session packet. */
    fun readHeader(): StreamHeader {
        val nameField = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        input.readFully(nameField)
        val nameLen = nameField.indexOf(0).let { if (it < 0) nameField.size else it }
        val name = String(nameField, 0, nameLen, Charsets.UTF_8)

        val codecId = input.readInt()
        when (codecId) {
            0 -> throw IOException("Video stream disabled by the device")
            1 -> throw IOException("Video stream configuration error on the device")
        }

        if (!readHeaderBytes()) throw EOFException("Missing session packet")
        val session = parseSession() ?: throw IOException("Expected a session packet")
        if (session.width <= 0 || session.height <= 0) {
            throw IOException("Invalid video size ${session.width}x${session.height}")
        }
        return StreamHeader(name, codecId, session.width, session.height)
    }

    /** Next packet, or null on a clean end of stream (EOF exactly at a packet boundary). */
    fun readPacket(): VideoPacket? {
        if (!readHeaderBytes()) return null
        parseSession()?.let { return VideoPacket(0, false, false, EMPTY, it) }

        val ptsFlags = getLong(0)
        val size = getInt(8)
        if (size <= 0) throw IOException("Invalid packet size $size")
        val data = ByteArray(size)
        input.readFully(data)
        val isConfig = ptsFlags and FLAG_CONFIG != 0L
        return VideoPacket(
            ptsUs = if (isConfig) 0 else ptsFlags and PTS_MASK,
            isConfig = isConfig,
            isKeyFrame = ptsFlags and FLAG_KEY_FRAME != 0L,
            data = data,
        )
    }

    /** Fills [header]; false on EOF before the first byte, [EOFException] on a partial header. */
    private fun readHeaderBytes(): Boolean {
        val first = input.read()
        if (first < 0) return false
        header[0] = first.toByte()
        input.readFully(header, 1, HEADER_SIZE - 1)
        return true
    }

    private fun parseSession(): SessionMeta? {
        if (header[0].toInt() and 0x80 == 0) return null
        return SessionMeta(getInt(4), getInt(8), clientResized = header[3].toInt() and 1 != 0)
    }

    private fun getInt(off: Int): Int =
        (header[off].toInt() and 0xFF shl 24) or (header[off + 1].toInt() and 0xFF shl 16) or
            (header[off + 2].toInt() and 0xFF shl 8) or (header[off + 3].toInt() and 0xFF)

    private fun getLong(off: Int): Long =
        (getInt(off).toLong() shl 32) or (getInt(off + 4).toLong() and 0xFFFFFFFFL)

    private companion object {
        const val DEVICE_NAME_FIELD_LENGTH = 64
        const val HEADER_SIZE = 12
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = FLAG_KEY_FRAME - 1
        val EMPTY = ByteArray(0)
    }
}
