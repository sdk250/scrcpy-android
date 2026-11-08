package dev.scrcpyandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCommandTest {
    @Test
    fun buildDefaultCommand() {
        assertEquals(
            "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 " +
                "scid=1234abcd log_level=info tunnel_forward=true audio=false control=true cleanup=true " +
                "video_codec=h264 max_size=0 video_bit_rate=8000000 max_fps=60 clipboard_autosync=false",
            ServerCommand.build("5.0.1", 0x1234abcd, ScrcpyOptions()),
        )
    }

    @Test
    fun buildH265WithLimits() {
        val cmd = ServerCommand.build("5.0.1", 1, ScrcpyOptions(1024, 4_000_000, 30, VideoCodec.H265))
        assertTrue(cmd, cmd.contains("video_codec=h265 max_size=1024 video_bit_rate=4000000 max_fps=30"))
    }

    @Test
    fun scidKeepsLow31Bits() {
        val cmd = ServerCommand.build("5.0.1", 0xF234abcd.toInt(), ScrcpyOptions())
        assertTrue(cmd, cmd.contains(" scid=7234abcd "))
        assertEquals("scrcpy_7234abcd", ServerCommand.socketName(0xF234abcd.toInt()))
    }

    @Test
    fun socketNamePadsHex() {
        assertEquals("scrcpy_000000ab", ServerCommand.socketName(0xab))
    }
}
