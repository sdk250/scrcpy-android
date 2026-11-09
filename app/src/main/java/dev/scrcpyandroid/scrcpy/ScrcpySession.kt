package dev.scrcpyandroid.scrcpy

import dev.scrcpyandroid.adb.AdbConnection
import dev.scrcpyandroid.adb.AdbStream
import dev.scrcpyandroid.adb.AdbStreamRefusedException
import dev.scrcpyandroid.adb.push
import dev.scrcpyandroid.adb.shell
import dev.scrcpyandroid.adb.stat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Consumer of the video stream (implemented by [VideoDecoder]). All calls come from the single
 * video thread: [onHeader] once first, then [onSession]/[onPacket] in stream order, then [release].
 */
interface FrameSink {
    /** Stream header; carries the initial video size (the first session packet). */
    fun onHeader(h: StreamHeader)

    /** Mid-stream session packet (rotation, resize, encoder restart). A new config packet follows. */
    fun onSession(w: Int, h: Int)

    /** A media packet (config or frame). */
    fun onPacket(p: VideoPacket)

    fun release()

    /**
     * Asks a blocked [onPacket] to give up (called from any thread when the session ends), so the
     * video thread can reach [release]. Default: no-op.
     */
    fun abort() {}
}

sealed class SessionEvent {
    /**
     * Video geometry. [sessionW]/[sessionH] is the size announced by the server (session packets);
     * touch events MUST be encoded with it as screenW/screenH (the server drops any other size).
     * [displayW]/[displayH] is the decoder's crop-derived output size; layout and aspect ratio MUST
     * use it (it equals the session size until the decoder reports otherwise).
     * Emitted for the initial size, every session packet and every decoder size change;
     * identical consecutive events are not repeated.
     */
    data class VideoSize(val sessionW: Int, val sessionH: Int, val displayW: Int, val displayH: Int) : SessionEvent()

    /** Emitted exactly once, last. [serverLog] is the tail (<= 64 KiB) of the server's shell output. */
    data class Ended(val reason: EndReason, val serverLog: String) : SessionEvent()
}

/** Why a session ended; locale-independent, the UI turns it into text. */
sealed class EndReason {
    /** [ScrcpySession.stop] was called (user exit). Never shown. */
    data object Stopped : EndReason()
    /** The scope running the session was cancelled. Never shown. */
    data object Cancelled : EndReason()
    /** The ADB connection closed. */
    data object ConnectionLost : EndReason()
    /** No video and the link probe went unanswered (silent network drop). */
    data object Unresponsive : EndReason()
    data object ServerExited : EndReason()
    data object VideoEnded : EndReason()
    data class VideoError(val detail: String) : EndReason()
    data class ControlError(val detail: String) : EndReason()
    data class CodecMismatch(val received: Int, val requested: VideoCodec) : EndReason()
    /** Push/start/connect failed. */
    data class StartFailed(val detail: String) : EndReason()
}

/**
 * One mirroring session over [conn]: pushes the server if needed, starts it, connects the video
 * and control sockets (forward tunnel, scrcpy v5.0.1) and feeds the video to a [FrameSink].
 *
 * Threads: ADB I/O runs in [scope]; the video socket is read with blocking I/O on a dedicated
 * thread ("scrcpy-video") which also calls the [FrameSink]. [events] is safe to collect anywhere.
 * [stop] emits `Ended(EndReason.Stopped, log)` unless the session already ended.
 */
class ScrcpySession(
    private val conn: AdbConnection,
    private val options: ScrcpyOptions,
    private val version: String,
    private val serverBytes: () -> ByteArray,
    private val scope: CoroutineScope,
    private val scid: Int = Random.nextInt() and 0x7FFFFFFF,
    private val videoTimeoutMs: Long = DEFAULT_VIDEO_TIMEOUT_MS,
) {
    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SessionEvent> = _events

    private val started = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val endedEmitted = AtomicBoolean(false)
    private val released = CompletableDeferred<Unit>()
    private val releaseDone = AtomicBoolean(false)

    /** Set once the video loop coroutine has been launched; from then on it owns [releaseVideo]. */
    @Volatile private var videoLaunched = false
    private val log = LogBuffer(LOG_LIMIT)
    private val shellDone = CompletableDeferred<Unit>()
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    /** Guards [ended] transitions and every emission, so nothing can be emitted after Ended. */
    private val emitLock = Any()
    private var lastSize: SessionEvent.VideoSize? = null

    @Volatile private var shell: AdbStream? = null
    @Volatile private var video: AdbStream? = null
    @Volatile private var control: AdbStream? = null
    @Volatile private var startJob: Job? = null
    @Volatile private var sink: FrameSink? = null
    @Volatile private var watchdogJob: Job? = null

    /** Signalled by the video thread for every packet read; consumed by [watchVideo]. */
    private val videoActivity = Channel<Unit>(Channel.CONFLATED)

    private val videoDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scrcpy-video").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Pushes/starts the server and connects; returns once video is flowing or the session ended
     * (failures are reported as [SessionEvent.Ended], not thrown). [sinkFactory] gets the codec and
     * a callback for the decoder's output size (e.g. `{ c, cb -> VideoDecoder(surface, c, cb) }`);
     * it is invoked on the video thread. A no-op if the session was already stopped.
     */
    suspend fun start(sinkFactory: (VideoCodec, (Int, Int) -> Unit) -> FrameSink) {
        if (!started.compareAndSet(false, true)) {
            check(ended.get()) { "start() may only be called once" }
            return
        }
        val job = scope.launch {
            try {
                connect(sinkFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish(if (conn.closed.value) EndReason.ConnectionLost else EndReason.StartFailed(e.message ?: e.toString()))
            } finally {
                if (!videoLaunched) releaseVideo()
            }
        }
        // Also runs if the job is cancelled (e.g. [scope] cancelled) before its body ever started.
        job.invokeOnCompletion { cause ->
            if (cause != null) finish(EndReason.Cancelled)
            if (!videoLaunched) releaseVideo()
        }
        startJob = job
        if (ended.get()) job.cancel()
        try {
            job.join()
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    /** Queues a control message; sent in order by a single writer. Dropped once the session ended. */
    fun send(msg: ByteArray) {
        if (ended.get()) return
        outgoing.trySend(msg)
    }

    /** Idempotent. Closes all streams; emits `Ended(EndReason.Stopped)` if the session had not ended yet. */
    fun stop() = finish(EndReason.Stopped, waitForServerLog = false)

    /**
     * Completes once the [FrameSink] has been released and the video thread has exited (or was
     * never started). [SessionEvent.Ended] may be emitted before that: callers MUST await this
     * before reusing or destroying the Surface the decoder renders to.
     */
    suspend fun awaitReleased() = released.await()

    private suspend fun connect(sinkFactory: (VideoCodec, (Int, Int) -> Unit) -> FrameSink) {
        if (ended.get()) return
        val bytes = serverBytes()
        val mtime = serverMtime(version)
        val st = conn.stat(ServerCommand.REMOTE_PATH)
        if (st.size != bytes.size || st.mtime != mtime) {
            conn.push(bytes, ServerCommand.REMOTE_PATH, mtime = mtime)
        }

        val sh = adopt(conn.shell(ServerCommand.build(version, scid, options))) { shell = it }
        scope.launch { collectLog(sh) }

        // Forward-mode order (v5.0.1 DesktopConnection.open/Server.java, official client
        // server.c): the server accepts video, writes only the dummy byte, then blocks accepting
        // control; device meta is written only after both sockets are connected and codec meta
        // later from the encoder thread. So all sockets must be open before reading the header.
        val service = "localabstract:" + ServerCommand.socketName(scid)
        val v = adopt(openWithRetry(service)) { video = it }
        val reader = VideoStreamReader(v.asInputStream())
        withContext(videoDispatcher) { reader.readDummyByte() }
        val c = adopt(openWithRetry(service)) { control = it }
        val header = withContext(videoDispatcher) { reader.readHeader() }
        if (header.codecId != options.codec.id) {
            finish(EndReason.CodecMismatch(header.codecId, options.codec))
            return
        }

        if (options.turnScreenOff) c.write(ControlMessages.setDisplayPower(false))
        scope.launch { drain(c) }
        scope.launch { writeLoop(c) }

        onSessionSize(header.width, header.height)
        if (ended.get()) return
        val videoJob = scope.launch(videoDispatcher) { videoLoop(reader, header, sinkFactory) }
        videoLaunched = true
        // If the job is cancelled before its body runs, the finally in videoLoop never executes.
        videoJob.invokeOnCompletion { releaseVideo() }
        if (videoTimeoutMs > 0) {
            watchdogJob = scope.launch { watchVideo() }
            if (ended.get()) watchdogJob?.cancel()
        }
    }

    /** Publishes [s] via [set]; if the session ended meanwhile, closes it and aborts. */
    private inline fun adopt(s: AdbStream, set: (AdbStream) -> Unit): AdbStream {
        set(s)
        if (ended.get()) {
            s.close()
            throw CancellationException("session ended")
        }
        return s
    }

    private suspend fun openWithRetry(service: String): AdbStream {
        var last: Throwable? = null
        for (attempt in 1..SOCKET_ATTEMPTS) {
            try {
                return conn.open(service)
            } catch (e: AdbStreamRefusedException) {
                last = e
            }
            if (attempt < SOCKET_ATTEMPTS) delay(SOCKET_RETRY_MS)
        }
        throw IOException("could not connect to the scrcpy server socket", last)
    }

    /**
     * Spec 6.9: a dropped network (no TCP reset) would otherwise go unnoticed forever. A static
     * screen legitimately produces no video (KEY_REPEAT_PREVIOUS_FRAME_AFTER is not honoured by
     * every encoder, e.g. Pixel 7 Pro / Android 17), so silence alone proves nothing: after
     * [videoTimeoutMs] without a packet, probe the link with an ADB round trip (OPEN of a no-op
     * shell). Any answer (OKAY or refusal) means the device is alive; no answer within
     * [PROBE_TIMEOUT_MS] means the link is dead: close the ADB connection (the registry then marks
     * the device OFFLINE).
     */
    private suspend fun watchVideo() {
        while (!ended.get()) {
            if (withTimeoutOrNull(videoTimeoutMs) { videoActivity.receive() } != null) continue
            if (!probeLink()) {
                finish(EndReason.Unresponsive)
                conn.close()
                return
            }
        }
    }

    private suspend fun probeLink(): Boolean {
        val answered = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                conn.open(PROBE_SERVICE).close()
                true
            } catch (e: AdbStreamRefusedException) {
                true
            } catch (e: IOException) {
                false
            }
        }
        return answered == true
    }

    private suspend fun collectLog(sh: AdbStream) {
        try {
            while (true) log.append(sh.read() ?: break)
        } finally {
            shellDone.complete(Unit)
        }
        finish(if (conn.closed.value) EndReason.ConnectionLost else EndReason.ServerExited)
    }

    /** Device messages are discarded, but must be read so flow control never stalls the server. */
    private suspend fun drain(c: AdbStream) {
        while (c.read() != null) Unit
    }

    /**
     * Single control writer. Each WRTE waits for the device's OKAY (one round trip), so everything
     * queued meanwhile is sent as one write (up to [AdbConnection.maxPayload]) instead of letting
     * high-rate input (multi-touch MOVE) pile up behind one round trip per message.
     */
    private suspend fun writeLoop(c: AdbStream) {
        var carry: ByteArray? = null
        val batch = ByteArrayOutputStream()
        while (true) {
            val first = carry ?: outgoing.receiveCatching().getOrNull() ?: return
            carry = null
            batch.reset()
            batch.write(first)
            while (batch.size() < conn.maxPayload) {
                val next = outgoing.tryReceive().getOrNull() ?: break
                if (batch.size() + next.size > conn.maxPayload) {
                    carry = next
                    break
                }
                batch.write(next)
            }
            try {
                c.write(batch.toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return // stream/connection gone; ending is detected via the shell or video stream
            } catch (e: Exception) {
                finish(EndReason.ControlError(e.message ?: e.toString()))
                return
            }
        }
    }

    private fun videoLoop(
        reader: VideoStreamReader,
        header: StreamHeader,
        sinkFactory: (VideoCodec, (Int, Int) -> Unit) -> FrameSink,
    ) {
        try {
            if (ended.get()) return
            val s = sinkFactory(options.codec) { w, h -> onDecodedSize(w, h) }
            sink = s
            if (ended.get()) s.abort() // finish() may have run before [sink] was published
            s.onHeader(header)
            while (true) {
                val p = reader.readPacket() ?: break
                videoActivity.trySend(Unit)
                val meta = p.session
                if (meta != null) {
                    s.onSession(meta.width, meta.height)
                    onSessionSize(meta.width, meta.height)
                } else {
                    s.onPacket(p)
                }
            }
            finish(if (conn.closed.value) EndReason.ConnectionLost else EndReason.VideoEnded)
        } catch (e: Throwable) {
            finish(if (conn.closed.value) EndReason.ConnectionLost else EndReason.VideoError(e.message ?: e.toString()))
        } finally {
            releaseVideo()
        }
    }

    /**
     * Idempotent: releases the sink (if one was created), shuts the video thread down and completes
     * [released]. Only called once the video loop has finished or can no longer run.
     */
    private fun releaseVideo() {
        if (!releaseDone.compareAndSet(false, true)) return
        try {
            sink?.release()
        } catch (_: Throwable) {
        }
        videoDispatcher.close()
        released.complete(Unit)
    }

    /** New server-side size: display size resets to it until the decoder reports its own. */
    private fun onSessionSize(w: Int, h: Int) = emitSize(SessionEvent.VideoSize(w, h, w, h))

    /** Decoder output (crop) size: changes only the display fields. */
    private fun onDecodedSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(emitLock) {
            val cur = lastSize ?: return
            emitSize(cur.copy(displayW = w, displayH = h))
        }
    }

    private fun emitSize(e: SessionEvent.VideoSize) {
        synchronized(emitLock) {
            if (ended.get() || e == lastSize) return
            lastSize = e
            _events.tryEmit(e)
        }
    }

    /**
     * Ends the session once. Video/control are closed right away; unless stopping explicitly, the
     * server is given a short grace period to print its last words before Ended is emitted.
     */
    private fun finish(reason: EndReason, waitForServerLog: Boolean = true) {
        synchronized(emitLock) {
            if (!ended.compareAndSet(false, true)) return
        }
        sink?.abort()
        if (started.compareAndSet(false, true)) {
            // start() was never called: nothing will ever use the video thread.
            releaseVideo()
        }
        startJob?.cancel()
        watchdogJob?.cancel()
        outgoing.close()
        video?.close()
        control?.close()
        if (!waitForServerLog || shellDone.isCompleted || !scope.isActive) {
            emitEnded(reason)
        } else {
            scope.launch {
                try {
                    withTimeoutOrNull(SHELL_GRACE_MS) { shellDone.await() }
                } finally {
                    emitEnded(reason)
                }
            }
        }
    }

    private fun emitEnded(reason: EndReason) {
        if (!endedEmitted.compareAndSet(false, true)) return
        shell?.close()
        synchronized(emitLock) { _events.tryEmit(SessionEvent.Ended(reason, log.text())) }
    }

    /** Keeps the last [limit] bytes appended. */
    private class LogBuffer(private val limit: Int) {
        private var buf = ByteArray(0)

        @Synchronized
        fun append(chunk: ByteArray) {
            val joined = buf + chunk
            buf = if (joined.size > limit) joined.copyOfRange(joined.size - limit, joined.size) else joined
        }

        @Synchronized
        fun text(): String = String(buf, Charsets.UTF_8)
    }

    companion object {
        private const val LOG_LIMIT = 64 * 1024
        // 100 x 100 ms = 10 s, the official client's budget: old devices start the server slowly.
        private const val SOCKET_ATTEMPTS = 100
        private const val SOCKET_RETRY_MS = 100L
        private const val SHELL_GRACE_MS = 1000L
        const val DEFAULT_VIDEO_TIMEOUT_MS = 5000L
        const val PROBE_TIMEOUT_MS = 3000L
        const val PROBE_SERVICE = "shell:true"

        fun serverMtime(version: String): Int = (version.hashCode() and 0x7FFFFFFF)
    }
}
