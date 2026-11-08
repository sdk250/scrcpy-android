package dev.scrcpyandroid.adb

import kotlinx.coroutines.channels.Channel
import java.io.EOFException
import java.io.IOException

/**
 * In-memory [AdbTransport] for tests. The client side is what the code under test uses;
 * the device side is scripted through [FakeAdbd].
 */
class FakeTransport(override val description: String = "fake") : AdbTransport {
    /** Bytes the client wrote (client -> device). */
    internal val toDevice = Channel<ByteArray>(Channel.UNLIMITED)

    /** Bytes the device sends (device -> client). */
    internal val toClient = Channel<ByteArray>(Channel.UNLIMITED)

    private var pending = ByteArray(0)

    override suspend fun write(bytes: ByteArray) {
        if (toDevice.trySend(bytes).isFailure) throw IOException("FakeTransport closed")
    }

    override suspend fun readFully(n: Int): ByteArray {
        while (pending.size < n) {
            val chunk = toClient.receiveCatching().getOrNull() ?: throw EOFException("FakeTransport EOF")
            pending += chunk
        }
        val out = pending.copyOfRange(0, n)
        pending = pending.copyOfRange(n, pending.size)
        return out
    }

    /** Client-side close: drops everything and wakes blocked readers with EOF. */
    override fun close() {
        toDevice.close()
        toClient.cancel()
    }
}

/**
 * Scripted adbd peer on the device side of a [FakeTransport]:
 * `read()` the next message the client sent, `send()` one to the client, `close()` the link.
 */
class FakeAdbd(val transport: FakeTransport = FakeTransport()) {
    private var buf = ByteArray(0)

    private suspend fun need(n: Int) {
        while (buf.size < n) {
            val chunk = transport.toDevice.receiveCatching().getOrNull() ?: throw EOFException("client closed")
            buf += chunk
        }
    }

    private fun take(n: Int): ByteArray {
        val out = buf.copyOfRange(0, n)
        buf = buf.copyOfRange(n, buf.size)
        return out
    }

    /** Suspends until the client has sent a full message. */
    suspend fun read(): AdbMessage {
        need(Adb.HEADER_SIZE)
        val h = decodeHeader(buf.copyOfRange(0, Adb.HEADER_SIZE))
        need(Adb.HEADER_SIZE + h.length)
        take(Adb.HEADER_SIZE)
        return AdbMessage(h.command, h.arg0, h.arg1, take(h.length))
    }

    /** Reads the next message and asserts its command. */
    suspend fun expect(command: Int): AdbMessage {
        val m = read()
        check(m.command == command) { "expected 0x${command.toString(16)} but got 0x${m.command.toString(16)}" }
        return m
    }

    /** True if some bytes from the client are already queued (does not suspend). */
    fun hasPendingBytes(): Boolean {
        if (buf.isNotEmpty()) return true
        val r = transport.toDevice.tryReceive()
        if (r.isSuccess) {
            buf += r.getOrThrow()
            return true
        }
        return false
    }

    fun send(msg: AdbMessage) {
        transport.toClient.trySend(msg.encode())
    }

    fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) =
        send(AdbMessage(command, arg0, arg1, payload))

    /** Sends raw bytes, e.g. a malformed header. */
    fun sendRaw(bytes: ByteArray) {
        transport.toClient.trySend(bytes)
    }

    /** Device-side close: the client sees EOF after already-sent data. */
    fun close() {
        transport.toClient.close()
    }

    /** Reads the client's CNXN and answers with ours (no auth). */
    suspend fun acceptConnect(banner: String = "device::ro.product.model=Fake;", maxPayload: Int = 4096) {
        expect(Adb.CNXN)
        send(Adb.CNXN, Adb.VERSION, maxPayload, banner.toByteArray() + 0.toByte())
    }
}
