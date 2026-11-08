package dev.scrcpyandroid.scrcpy

import dev.scrcpyandroid.adb.Adb
import dev.scrcpyandroid.adb.AdbConnection
import dev.scrcpyandroid.adb.AdbKeyPair
import dev.scrcpyandroid.adb.AdbMessage
import dev.scrcpyandroid.adb.FakeAdbd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class ScrcpySessionTest {
    private val key = AdbKeyPair.generate()
    private val server = ByteArray(1000) { it.toByte() }
    private val version = "5.0.1"
    private val scid = 0x1234
    private val socket = "localabstract:" + ServerCommand.socketName(scid)

    private fun le(vararg v: Int): ByteArray =
        ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

    private fun be(vararg v: Int): ByteArray =
        ByteBuffer.allocate(4 * v.size).also { b -> v.forEach { b.putInt(it) } }.array()

    private fun sessionPacket(w: Int, h: Int) = be(0x80000000.toInt(), w, h)

    private fun videoHeader(codecId: Int = VideoCodec.H264.id, w: Int = 1920, h: Int = 1080): ByteArray =
        byteArrayOf(0) + "Pixel".toByteArray().copyOf(64) + be(codecId) + sessionPacket(w, h)

    /** Records FrameSink calls (called from the video thread). */
    private class RecordingSink : FrameSink {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onHeader(h: StreamHeader) { calls += "header ${h.width}x${h.height}" }
        override fun onSession(w: Int, h: Int) { calls += "session ${w}x$h" }
        override fun onPacket(p: VideoPacket) { calls += "packet ${p.data.size}" }
        override fun release() { calls += "release" }
        override fun abort() { calls += "abort" }
    }

    private fun size(w: Int, h: Int, dw: Int = w, dh: Int = h) = SessionEvent.VideoSize(w, h, dw, dh)

    /** Device-side script helpers on top of [FakeAdbd]. */
    private class Device(val peer: FakeAdbd) {
        private var nextRemote = 100

        /** Next client message matching [pred]; other messages (OKAY acks, CLSE, ...) are skipped. */
        suspend fun next(pred: (AdbMessage) -> Boolean): AdbMessage {
            while (true) {
                val m = peer.read()
                if (pred(m)) return m
            }
        }

        suspend fun nextOpen(): Pair<String, Int> {
            val m = next { it.command == Adb.OPEN }
            return String(m.payload).trimEnd('\u0000') to m.arg0
        }

        /** Accepts the next OPEN, which must be [service]. Returns (clientLocal, deviceRemote). */
        suspend fun accept(service: String): Pair<Int, Int> {
            val (s, local) = nextOpen()
            assertEquals(service, s)
            val remote = nextRemote++
            peer.send(Adb.OKAY, remote, local)
            return local to remote
        }

        suspend fun refuse(service: String) {
            val (s, local) = nextOpen()
            assertEquals(service, s)
            peer.send(Adb.CLSE, 0, local)
        }

        /** Collects client WRTE payloads on stream [local] (answering OKAY) until [size] bytes arrived. */
        suspend fun data(local: Int, remote: Int, size: Int): ByteArray {
            var out = ByteArray(0)
            while (out.size < size) {
                out += next { it.command == Adb.WRTE && it.arg0 == local }.payload
                peer.send(Adb.OKAY, remote, local)
            }
            return out
        }

        fun write(local: Int, remote: Int, bytes: ByteArray) = peer.send(Adb.WRTE, remote, local, bytes)
    }

    private suspend fun TestScope.connected(): Pair<Device, AdbConnection> {
        val peer = FakeAdbd()
        val c = async { AdbConnection.connect(peer.transport, key, backgroundScope) }
        peer.acceptConnect()
        return Device(peer) to c.await()
    }

    private fun TestScope.session(conn: AdbConnection, o: ScrcpyOptions = ScrcpyOptions()) =
        ScrcpySession(conn, o, version, { server }, backgroundScope, scid = scid)

    private suspend fun Device.serveStat(mode: Int, size: Int, mtime: Int) {
        val (l, r) = accept("sync:")
        val req = data(l, r, 8 + ServerCommand.REMOTE_PATH.length)
        assertArrayEquals("STAT".toByteArray() + le(ServerCommand.REMOTE_PATH.length) + ServerCommand.REMOTE_PATH.toByteArray(), req)
        write(l, r, "STAT".toByteArray() + le(mode, size, mtime))
    }

    private suspend fun Device.serveUpToDateStat() =
        serveStat(0x81A4, server.size, ScrcpySession.serverMtime(version))

    private suspend fun Device.serveShell(o: ScrcpyOptions = ScrcpyOptions()) =
        accept("shell:" + ServerCommand.build(version, scid, o))

    /** Video socket (first refused), header, then control socket. Returns control (local, remote). */
    private suspend fun Device.serveSockets(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        refuse(socket)
        val video = accept(socket)
        write(video.first, video.second, videoHeader())
        val control = accept(socket)
        return video to control
    }

    @Test
    fun skipsPushWhenSizeAndMtimeMatch() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveUpToDateStat()
        // The next OPEN must be the shell, not another sync: (no SEND).
        dev.serveShell()
        dev.serveSockets()
        started.await()
        s.stop()
    }

    @Test
    fun pushesWhenMissing() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveStat(0, 0, 0)
        val (l, r) = dev.accept("sync:")
        val spec = "/data/local/tmp/scrcpy-server.jar,33188".toByteArray()
        val total = 8 + spec.size + 8 + server.size + 8
        val got = dev.data(l, r, total)
        val expected = "SEND".toByteArray() + le(spec.size) + spec +
            "DATA".toByteArray() + le(server.size) + server +
            "DONE".toByteArray() + le(ScrcpySession.serverMtime(version))
        assertArrayEquals(expected, got)
        dev.write(l, r, "OKAY".toByteArray() + le(0))
        dev.serveShell()
        dev.serveSockets()
        started.await()
        s.stop()
    }

    @Test
    fun retriesUntilSocketReady() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val sink = RecordingSink()
        val started = async { s.start { _, _ -> sink } }
        dev.serveUpToDateStat()
        dev.serveShell()
        dev.serveSockets() // first open refused, second = video, third = control
        started.await()
        assertEquals(size(1920, 1080), s.events.first())
        s.stop()
    }

    @Test
    fun turnScreenOffSendsDisplayPower() = runTest {
        val (dev, conn) = connected()
        val o = ScrcpyOptions(turnScreenOff = true)
        val s = session(conn, o)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveUpToDateStat()
        dev.serveShell(o)
        val (_, control) = dev.serveSockets()
        val first = dev.data(control.first, control.second, 2)
        assertArrayEquals(ControlMessages.setDisplayPower(false), first)
        started.await()
        s.stop()
    }

    @Test
    fun serverExitEmitsEndedWithLog() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveUpToDateStat()
        val (l, r) = dev.serveShell()
        dev.write(l, r, "ERROR: xyz\n".toByteArray())
        dev.peer.send(Adb.CLSE, r, l)
        val ended = s.events.first { it is SessionEvent.Ended } as SessionEvent.Ended
        assertEquals("server exited", ended.reason)
        assertTrue(ended.serverLog, ended.serverLog.contains("ERROR: xyz"))
        started.await()
        s.awaitReleased() // video thread never started
    }

    @Test
    fun midStreamSessionPacketReachesSinkAndEmitsVideoSize() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val sink = RecordingSink()
        val started = async { s.start { _, _ -> sink } }
        dev.serveUpToDateStat()
        dev.serveShell()
        val (video, _) = dev.serveSockets()
        started.await()
        dev.write(video.first, video.second, sessionPacket(1080, 1920))
        s.events.first { it == size(1080, 1920) }
        assertEquals(listOf("header 1920x1080", "session 1080x1920"), sink.calls.toList())
        s.stop()
    }

    @Test
    fun codecMismatchEndsSession() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveUpToDateStat()
        dev.serveShell()
        val video = dev.accept(socket)
        dev.write(video.first, video.second, videoHeader(codecId = VideoCodec.H265.id))
        val ended = s.events.first { it is SessionEvent.Ended } as SessionEvent.Ended
        assertTrue(ended.reason, ended.reason.startsWith("unexpected codec"))
        started.await()
    }

    @Test
    fun stopIsIdempotentAndEndsOnce() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val started = async { s.start { _, _ -> RecordingSink() } }
        dev.serveUpToDateStat()
        dev.serveShell()
        dev.serveSockets()
        started.await()
        val ends = mutableListOf<SessionEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            s.events.collect { if (it is SessionEvent.Ended) ends += it }
        }
        s.stop()
        s.stop()
        s.send(byteArrayOf(1)) // dropped
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<SessionEvent>(SessionEvent.Ended("stopped", "")), ends)
        collector.cancel()
    }

    @Test
    fun decoderSizeChangesDisplayOnlyAndDuplicatesAreDropped() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        var decoded: ((Int, Int) -> Unit)? = null
        val started = async { s.start { _, cb -> decoded = cb; RecordingSink() } }
        dev.serveUpToDateStat()
        dev.serveShell()
        val (video, _) = dev.serveSockets()
        started.await()
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            s.events.collect { events += it }
        }
        // The video thread created the sink (and so set the callback) before reading the session packet.
        dev.write(video.first, video.second, sessionPacket(1920, 1080)) // same as header -> deduped
        dev.write(video.first, video.second, sessionPacket(1080, 1920))
        s.events.first { it == size(1080, 1920) }
        decoded!!(1080, 1904)
        decoded!!(1080, 1904) // duplicate
        dev.write(video.first, video.second, sessionPacket(1080, 1920)) // resets display to session size
        dev.write(video.first, video.second, sessionPacket(1080, 1920)) // duplicate
        dev.write(video.first, video.second, sessionPacket(500, 1000))
        s.events.first { it == size(500, 1000) }
        assertEquals(
            listOf(
                size(1920, 1080),
                size(1080, 1920),
                size(1080, 1920, 1080, 1904),
                size(1080, 1920),
                size(500, 1000),
            ),
            events.toList(),
        )
        collector.cancel()
        s.stop()
    }

    @Test
    fun nothingIsEmittedAfterEndedAndReleaseIsAwaitable() = runTest {
        val (dev, conn) = connected()
        val s = session(conn)
        val sink = RecordingSink()
        var decoded: ((Int, Int) -> Unit)? = null
        val started = async { s.start { _, cb -> decoded = cb; sink } }
        dev.serveUpToDateStat()
        dev.serveShell()
        val (video, _) = dev.serveSockets()
        started.await()
        dev.write(video.first, video.second, sessionPacket(1080, 1920))
        s.events.first { it == size(1080, 1920) } // sink exists, callback captured
        s.stop()
        decoded!!(640, 480) // late decoder callback must not overwrite Ended
        assertEquals(listOf<SessionEvent>(SessionEvent.Ended("stopped", "")), s.events.replayCache)
        s.awaitReleased()
        assertEquals("release", sink.calls.last())
        assertTrue(sink.calls.toString(), sink.calls.contains("abort"))
        assertEquals(listOf<SessionEvent>(SessionEvent.Ended("stopped", "")), s.events.replayCache)
    }

    @Test
    fun stopBeforeStartReleasesAndStartIsNoOp() = runTest {
        val (_, conn) = connected()
        val s = session(conn)
        s.stop()
        s.awaitReleased()
        s.start { _, _ -> error("must not create a sink") }
        assertEquals(listOf<SessionEvent>(SessionEvent.Ended("stopped", "")), s.events.replayCache)
    }

    @Test
    fun scopeCancelledBeforeStartRunsStillReleases() = runTest {
        val (_, conn) = connected()
        val sessionScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val s = ScrcpySession(conn, ScrcpyOptions(), version, { server }, sessionScope, scid = scid)
        // Runs until start() suspends in join(): its launched job is queued but has not run yet.
        val started = async(start = CoroutineStart.UNDISPATCHED) { s.start { _, _ -> error("no sink expected") } }
        sessionScope.cancel()
        withTimeout(5_000) { s.awaitReleased() }
        started.await()
        val ended = s.events.replayCache.single() as SessionEvent.Ended
        assertEquals("cancelled", ended.reason)
    }
}
