package dev.scrcpyandroid.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

interface AdbTransport : Closeable {
    suspend fun write(bytes: ByteArray)

    /** Reads exactly [n] bytes; throws [EOFException] when the peer closed before that. */
    suspend fun readFully(n: Int): ByteArray

    val description: String
}

class TcpTransport private constructor(
    private val socket: Socket,
    override val description: String,
) : AdbTransport {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    override suspend fun readFully(n: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(n)
        input.readFully(buf)
        buf
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        suspend fun open(host: String, port: Int, timeoutMs: Int = 5000): TcpTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                } catch (e: Throwable) {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    throw e
                }
                TcpTransport(socket, "tcp:$host:$port")
            }
    }
}
