package dev.scrcpyandroid.scrcpy

import java.nio.ByteBuffer

/**
 * Control-socket message encoders for the scrcpy v5.0.1 protocol (server
 * `control/ControlMessage.java` + `control/ControlMessageReader.java`; client `app/src/control_msg.c/.h`).
 * All multi-byte fields are big-endian.
 *
 *  - inject keycode (type 0, 14 bytes): u8 type, u8 action, i32 keycode, i32 repeat, i32 metaState
 *  - inject text (type 1): u8 type, u32 length, UTF-8 bytes (client limit 300 bytes per message)
 *  - inject touch (type 2, 32 bytes): u8 type, u8 action, i64 pointerId, i32 x, i32 y,
 *    u16 screenWidth, u16 screenHeight, u16 pressure (fixed point, 1.0 -> 0xFFFF), i32 actionButton, i32 buttons
 *  - set display power (type 10, 2 bytes): u8 type, u8 on (0/1)
 */
object ControlMessages {
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_SET_DISPLAY_POWER = 10

    /** SC_CONTROL_MSG_INJECT_TEXT_MAX_LENGTH: max UTF-8 bytes of one inject-text message. */
    const val INJECT_TEXT_MAX_LENGTH = 300

    fun keycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keycode)
            .putInt(repeat)
            .putInt(metaState)
            .array()

    /**
     * Splits [s] into inject-text messages of at most [INJECT_TEXT_MAX_LENGTH] UTF-8 bytes each,
     * cutting only between code points (never inside a multi-byte sequence or surrogate pair).
     */
    fun text(s: String): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val chunk = StringBuilder()
        var chunkBytes = 0
        var i = 0
        while (i < s.length) {
            val cpLen = Character.charCount(s.codePointAt(i))
            val cp = s.substring(i, i + cpLen)
            val n = cp.toByteArray(Charsets.UTF_8).size
            if (chunkBytes + n > INJECT_TEXT_MAX_LENGTH) {
                out += textMessage(chunk.toString())
                chunk.setLength(0)
                chunkBytes = 0
            }
            chunk.append(cp)
            chunkBytes += n
            i += cpLen
        }
        if (chunk.isNotEmpty()) out += textMessage(chunk.toString())
        return out
    }

    private fun textMessage(text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + utf8.size)
            .put(TYPE_INJECT_TEXT.toByte())
            .putInt(utf8.size)
            .put(utf8)
            .array()
    }

    fun touch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray =
        ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH_EVENT.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(pressureToU16(pressure).toShort())
            .putInt(actionButton)
            .putInt(buttons)
            .array()

    fun setDisplayPower(on: Boolean): ByteArray =
        byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)

    /** sc_float_to_u16fp: [0,1] -> u16 fixed point, 1.0 saturates to 0xFFFF. */
    private fun pressureToU16(p: Float): Int {
        val u = (p.coerceIn(0f, 1f) * 65536f).toInt()
        return if (u >= 0xFFFF) 0xFFFF else u
    }
}
