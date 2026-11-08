package dev.scrcpyandroid.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface

/**
 * Hardware decoder rendering straight to [surface] (no frame queueing: every output buffer is
 * released for rendering as soon as it is available).
 *
 * Threading: MediaCodec runs in synchronous mode. Input ([onHeader]/[onSession]/[onPacket]/
 * [release]) is driven by the session's single video thread; output is drained on a separate
 * "scrcpy-decoder-out" thread, which is stopped and joined before any reconfigure or release, so
 * the codec is never stopped under its feet.
 *
 * [onVideoSize] is called (from the output thread) with the crop-derived size on every
 * INFO_OUTPUT_FORMAT_CHANGED; it is the display-aspect authority.
 */
class VideoDecoder(
    private val surface: Surface,
    private val codec: VideoCodec,
    private val onVideoSize: (w: Int, h: Int) -> Unit,
) : FrameSink {
    private var mc: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var output: OutputDrainer? = null
    private var released = false

    override fun onHeader(h: StreamHeader) = configure(h.width, h.height)

    override fun onSession(w: Int, h: Int) {
        // Same size (e.g. encoder restart): the following config packet is fed as new CSD.
        if (w != width || h != height) configure(w, h)
    }

    override fun onPacket(p: VideoPacket) {
        val codec = mc ?: return
        if (p.data.isEmpty()) return
        var index: Int
        do {
            index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            // Without a live drainer no input buffer would ever come back: fail instead of hanging.
            if (index < 0 && output?.isAlive != true) throw IllegalStateException("decoder output stopped")
        } while (index < 0)
        val buf = codec.getInputBuffer(index) ?: throw IllegalStateException("no input buffer $index")
        buf.clear()
        buf.put(p.data)
        var flags = 0
        if (p.isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        if (p.isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        codec.queueInputBuffer(index, 0, p.data.size, if (p.isConfig) 0 else p.ptsUs, flags)
    }

    override fun release() {
        if (released) return
        released = true
        stopCodec()
        mc?.release()
        mc = null
    }

    /** (Re)configures for [w]x[h]; an existing codec is stopped (back to Uninitialized) and reused. */
    private fun configure(w: Int, h: Int) {
        if (released) return
        stopCodec()
        val codec = mc ?: MediaCodec.createDecoderByType(this.codec.mime).also { mc = it }
        val format = MediaFormat.createVideoFormat(this.codec.mime, w, h)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        width = w
        height = h
        output = OutputDrainer(codec).also { it.start() }
    }

    private fun stopCodec() {
        val drainer = output ?: return
        output = null
        drainer.shutdown()
        try {
            mc?.stop()
        } catch (_: IllegalStateException) {
        }
    }

    private inner class OutputDrainer(private val codec: MediaCodec) : Thread("scrcpy-decoder-out") {
        @Volatile private var running = true

        init {
            isDaemon = true
        }

        fun shutdown() {
            running = false
            join()
        }

        override fun run() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val i = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                    when {
                        i >= 0 -> codec.releaseOutputBuffer(i, true)
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(codec.outputFormat)
                    }
                }
            } catch (_: IllegalStateException) {
                // Codec stopped/released or in error; the input side reports failures.
            }
        }

        private fun reportSize(f: MediaFormat) {
            val w = if (f.containsKey(KEY_CROP_LEFT) && f.containsKey(KEY_CROP_RIGHT)) {
                f.getInteger(KEY_CROP_RIGHT) - f.getInteger(KEY_CROP_LEFT) + 1
            } else {
                f.getInteger(MediaFormat.KEY_WIDTH)
            }
            val h = if (f.containsKey(KEY_CROP_TOP) && f.containsKey(KEY_CROP_BOTTOM)) {
                f.getInteger(KEY_CROP_BOTTOM) - f.getInteger(KEY_CROP_TOP) + 1
            } else {
                f.getInteger(MediaFormat.KEY_HEIGHT)
            }
            onVideoSize(w, h)
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 100_000L
        const val OUTPUT_TIMEOUT_US = 10_000L

        // MediaFormat.KEY_CROP_* are public only since API 33; the keys exist on all versions.
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}
