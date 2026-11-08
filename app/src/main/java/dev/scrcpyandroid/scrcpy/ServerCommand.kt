package dev.scrcpyandroid.scrcpy

/**
 * Builds the shell command that starts the bundled scrcpy server (v5.0.1, see `Options.parse`).
 * The first argument must equal the server's version exactly, so pass `BuildConfig.SCRCPY_VERSION`.
 * The server is used in forward-tunnel mode (we connect to its abstract socket via ADB `localabstract:`).
 * [ScrcpyOptions.turnScreenOff] is not a server argument: it is applied with
 * [ControlMessages.setDisplayPower] once the control socket is up.
 */
object ServerCommand {
    const val REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"

    fun build(version: String, scid: Int, o: ScrcpyOptions): String =
        listOf(
            "CLASSPATH=$REMOTE_PATH",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            version,
            "scid=${hex(scid)}",
            "log_level=info",
            "tunnel_forward=true",
            "audio=false",
            "control=true",
            "cleanup=true",
            "video_codec=${o.codec.arg}",
            "max_size=${o.maxSize}",
            "video_bit_rate=${o.bitRate}",
            "max_fps=${o.maxFps}",
            "clipboard_autosync=false", // defaults to true in 5.x; clipboard sync is a non-goal
        ).joinToString(" ")

    /** Abstract socket name the server listens on: "scrcpy_%08x" of the 31-bit scid. */
    fun socketName(scid: Int): String = "scrcpy_${hex(scid)}"

    private fun hex(scid: Int) = "%08x".format(scid and 0x7FFFFFFF)
}
