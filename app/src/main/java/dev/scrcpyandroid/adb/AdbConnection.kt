package dev.scrcpyandroid.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AdbStreamRefusedException(service: String) : IOException(service)

class AdbConnection private constructor(
    private val transport: AdbTransport,
    private val scope: CoroutineScope,
    val maxPayload: Int,
    val banner: String,
) : Closeable {

    val model: String? = MODEL_REGEX.find(banner)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed

    private val writeMutex = Mutex()
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    private var readJob: Job? = null

    suspend fun open(service: String): AdbStream {
        if (_closed.value) throw IOException("ADB connection closed")
        val stream = AdbStream(this, nextId.getAndIncrement(), service)
        streams[stream.localId] = stream
        // Re-check after registering so a concurrent shutdown cannot leave this stream orphaned.
        if (_closed.value) {
            streams.remove(stream.localId)
            stream.terminate(IOException("ADB connection closed"))
            throw IOException("ADB connection closed")
        }
        try {
            send(AdbMessage(Adb.OPEN, stream.localId, 0, service.toByteArray(Charsets.UTF_8) + 0.toByte()))
            stream.awaitOpened()
        } catch (e: Throwable) {
            streams.remove(stream.localId)
            stream.terminate(e)
            throw e
        }
        return stream
    }

    internal suspend fun send(msg: AdbMessage) {
        val bytes = msg.encode() // header + payload in a single transport write
        try {
            writeMutex.withLock { transport.write(bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            shutdown(e)
            throw e
        }
    }

    internal fun forget(stream: AdbStream) {
        streams.remove(stream.localId)
    }

    /** Fire-and-forget send used from non-suspending contexts (stream close). */
    internal fun sendAsync(msg: AdbMessage) {
        if (_closed.value) return
        scope.launch {
            try {
                send(msg)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val msg = readMessage(transport)
                dispatch(msg)
            }
        } catch (e: Throwable) {
            cause = e
        } finally {
            shutdown(cause)
        }
    }

    private suspend fun dispatch(msg: AdbMessage) {
        val stream = streams[msg.arg1] ?: return
        when (msg.command) {
            Adb.OKAY -> stream.onOkay(msg.arg0)
            Adb.WRTE -> {
                stream.onData(msg.payload)
                send(AdbMessage(Adb.OKAY, stream.localId, msg.arg0))
            }
            Adb.CLSE -> {
                streams.remove(stream.localId)
                val remote = stream.onPeerClose()
                if (remote != 0) send(AdbMessage(Adb.CLSE, stream.localId, remote))
            }
        }
    }

    private fun shutdown(cause: Throwable?) {
        if (!_closed.compareAndSet(false, true)) return
        try {
            transport.close()
        } catch (_: Exception) {
        }
        val err = cause ?: IOException("ADB connection closed")
        for (s in streams.values.toList()) s.terminate(err)
        streams.clear()
        readJob?.cancel()
    }

    override fun close() = shutdown(null)

    companion object {
        private val MODEL_REGEX = Regex("""ro\.product\.model=([^;]*)""")

        suspend fun connect(
            transport: AdbTransport,
            key: AdbKeyPair,
            scope: CoroutineScope,
            onAuthPending: () -> Unit = {},
        ): AdbConnection {
            try {
                transport.write(
                    AdbMessage(Adb.CNXN, Adb.VERSION, Adb.MAX_PAYLOAD, "host::\u0000".toByteArray()).encode(),
                )
                var tokens = 0
                // No timeout here on purpose: the user may take minutes to accept the key on the device.
                while (true) {
                    val msg = readMessage(transport)
                    when (msg.command) {
                        Adb.AUTH -> {
                            if (msg.arg0 != Adb.AUTH_TOKEN) throw AdbProtocolException("Unexpected AUTH type ${msg.arg0}")
                            tokens++
                            when (tokens) {
                                1 -> transport.write(
                                    AdbMessage(Adb.AUTH, Adb.AUTH_SIGNATURE, 0, key.signToken(msg.payload)).encode(),
                                )
                                2 -> {
                                    transport.write(
                                        AdbMessage(Adb.AUTH, Adb.AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload()).encode(),
                                    )
                                    onAuthPending()
                                }
                                else -> throw AdbProtocolException("Authentication rejected by device")
                            }
                        }
                        Adb.CNXN -> {
                            val banner = String(msg.payload, Charsets.UTF_8).trimEnd('\u0000')
                            val conn = AdbConnection(transport, scope, minOf(msg.arg1, Adb.MAX_PAYLOAD), banner)
                            conn.readJob = scope.launch { conn.readLoop() }
                            return conn
                        }
                        Adb.STLS -> throw IOException("不支持 TLS 连接（无线调试配对端口）")
                        else -> throw AdbProtocolException("Unexpected command 0x${msg.command.toString(16)} during handshake")
                    }
                }
            } catch (e: Throwable) {
                try {
                    transport.close()
                } catch (_: Exception) {
                }
                throw e
            }
        }

        internal suspend fun readMessage(transport: AdbTransport): AdbMessage {
            val h = decodeHeader(transport.readFully(Adb.HEADER_SIZE))
            if (h.length < 0 || h.length > Adb.MAX_PAYLOAD) {
                throw AdbProtocolException("Invalid ADB payload length ${h.length}")
            }
            val payload = if (h.length == 0) ByteArray(0) else transport.readFully(h.length)
            return AdbMessage(h.command, h.arg0, h.arg1, payload)
        }
    }
}
