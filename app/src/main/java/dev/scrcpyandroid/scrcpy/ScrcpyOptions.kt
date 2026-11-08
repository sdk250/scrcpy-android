package dev.scrcpyandroid.scrcpy

import android.content.SharedPreferences

/** Video codecs supported by this client. [id] is the scrcpy codec id ("h264"/"h265" in ASCII). */
enum class VideoCodec(val arg: String, val mime: String, val id: Int) {
    H264("h264", "video/avc", 0x68323634),
    H265("h265", "video/hevc", 0x68323635),
}

/** User-tunable mirroring options. [maxSize] 0 means native resolution. */
data class ScrcpyOptions(
    val maxSize: Int = 0,
    val bitRate: Int = 8_000_000,
    val maxFps: Int = 60,
    val codec: VideoCodec = VideoCodec.H264,
    val turnScreenOff: Boolean = false,
)

/** Persists [ScrcpyOptions] in [prefs]; unknown/missing values fall back to the defaults. */
class SettingsRepository(private val prefs: SharedPreferences) {
    fun load(): ScrcpyOptions {
        val d = ScrcpyOptions()
        return ScrcpyOptions(
            maxSize = prefs.getInt(KEY_MAX_SIZE, d.maxSize),
            bitRate = prefs.getInt(KEY_BIT_RATE, d.bitRate),
            maxFps = prefs.getInt(KEY_MAX_FPS, d.maxFps),
            codec = VideoCodec.entries.firstOrNull { it.name == prefs.getString(KEY_CODEC, null) } ?: d.codec,
            turnScreenOff = prefs.getBoolean(KEY_TURN_SCREEN_OFF, d.turnScreenOff),
        )
    }

    fun save(o: ScrcpyOptions) {
        prefs.edit()
            .putInt(KEY_MAX_SIZE, o.maxSize)
            .putInt(KEY_BIT_RATE, o.bitRate)
            .putInt(KEY_MAX_FPS, o.maxFps)
            .putString(KEY_CODEC, o.codec.name)
            .putBoolean(KEY_TURN_SCREEN_OFF, o.turnScreenOff)
            .apply()
    }

    private companion object {
        const val KEY_MAX_SIZE = "max_size"
        const val KEY_BIT_RATE = "bit_rate"
        const val KEY_MAX_FPS = "max_fps"
        const val KEY_CODEC = "codec"
        const val KEY_TURN_SCREEN_OFF = "turn_screen_off"
    }
}
