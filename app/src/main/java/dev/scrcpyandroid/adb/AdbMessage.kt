package dev.scrcpyandroid.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Adb {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453
    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 1024 * 1024
    const val HEADER_SIZE = 24
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3
}

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
)

data class AdbHeader(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

class AdbProtocolException(msg: String) : IOException(msg)

/** Encodes the 24-byte header followed by the payload, all integers little-endian. */
fun AdbMessage.encode(): ByteArray = encodeHeader() + payload

/** Encodes only the 24-byte header (little-endian); the payload is sent separately. */
fun AdbMessage.encodeHeader(): ByteArray {
    var checksum = 0
    for (b in payload) checksum += b.toInt() and 0xFF
    val buf = ByteBuffer.allocate(Adb.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(command)
    buf.putInt(arg0)
    buf.putInt(arg1)
    buf.putInt(payload.size)
    buf.putInt(checksum)
    buf.putInt(command xor -1)
    return buf.array()
}

/** Writes [msg] through [AdbTransport.writeMessage] (header and payload as separate parts). */
internal suspend fun AdbTransport.writeMessage(msg: AdbMessage) = writeMessage(msg.encodeHeader(), msg.payload)

/** Decodes the first 24 bytes of [b]; throws [AdbProtocolException] if too short or the magic is wrong. */
fun decodeHeader(b: ByteArray): AdbHeader {
    if (b.size < Adb.HEADER_SIZE) {
        throw AdbProtocolException("ADB header too short: ${b.size} bytes")
    }
    val buf = ByteBuffer.wrap(b, 0, Adb.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    val command = buf.int
    val arg0 = buf.int
    val arg1 = buf.int
    val length = buf.int
    buf.int // checksum, not part of AdbHeader
    val magic = buf.int
    if (magic != (command xor -1)) {
        throw AdbProtocolException("ADB magic mismatch for command 0x${command.toString(16)}")
    }
    return AdbHeader(command, arg0, arg1, length)
}
