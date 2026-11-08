package dev.scrcpyandroid.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdbStream internal constructor(
    private val conn: AdbConnection,
    internal val localId: Int,
    private val service: String,
) {
    private val opened = CompletableDeferred<Int>() // remote id
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val okays = Channel<Unit>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private val closedLocally = AtomicBoolean(false)

    @Volatile
    private var remoteId = 0

    @Volatile
    private var finished = false

    internal suspend fun awaitOpened() {
        remoteId = opened.await()
    }

    // --- called by the connection's read loop ---

    internal fun onOkay(remote: Int) {
        if (!opened.isCompleted) opened.complete(remote) else okays.trySend(Unit)
    }

    internal fun onData(data: ByteArray) {
        incoming.trySend(data)
    }

    /** Peer closed. Returns the remote id to answer with CLSE, or 0 if no reply is due. */
    internal fun onPeerClose(): Int {
        val wasOpen = opened.isCompleted
        if (!wasOpen) opened.completeExceptionally(AdbStreamRefusedException(service))
        finish()
        return if (wasOpen && !opened.isCancelled && !closedLocally.get()) opened.getCompleted() else 0
    }

    /** Connection died or open failed: end everything. */
    internal fun terminate(cause: Throwable) {
        opened.completeExceptionally(cause)
        finish()
    }

    private fun finish() {
        finished = true
        incoming.close()
        okays.close()
    }

    // --- public API ---

    suspend fun write(data: ByteArray) {
        writeLock.withLock {
            var off = 0
            while (off < data.size) {
                if (finished || closedLocally.get()) throw IOException("ADB stream closed")
                val n = minOf(conn.maxPayload, data.size - off)
                conn.send(AdbMessage(Adb.WRTE, localId, remoteId, data.copyOfRange(off, off + n)))
                okays.receiveCatching().getOrNull() ?: throw IOException("ADB stream closed")
                off += n
            }
        }
    }

    /** Next chunk of data, or null once the peer closed the stream (or the connection died). */
    suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()?.also { acknowledge() }

    /** Releases the peer to send its next WRTE; skipped once the stream is closed. */
    private fun acknowledge() {
        if (finished || !opened.isCompleted || opened.isCancelled) return
        conn.sendAsync(AdbMessage(Adb.OKAY, localId, opened.getCompleted()))
    }

    fun asInputStream(): InputStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (eof) return -1
            var c = cur
            if (c == null || pos >= c.size) {
                c = nextChunk()
                if (c == null) {
                    eof = true
                    return -1
                }
                cur = c
                pos = 0
            }
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = cur?.let { it.size - pos } ?: 0

        override fun close() {
            eof = true
            this@AdbStream.close()
        }

        private fun nextChunk(): ByteArray? {
            while (true) {
                try {
                    val r = runBlocking { incoming.receiveCatching() }
                    val v = r.getOrNull() ?: return null
                    acknowledge()
                    if (v.isNotEmpty()) return v
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted while reading ADB stream")
                }
            }
        }
    }

    fun close() {
        if (!closedLocally.compareAndSet(false, true)) return
        val wasFinished = finished
        conn.forget(this)
        finish()
        if (!wasFinished && opened.isCompleted && !opened.isCancelled) {
            conn.sendAsync(AdbMessage(Adb.CLSE, localId, opened.getCompleted()))
        }
    }
}
