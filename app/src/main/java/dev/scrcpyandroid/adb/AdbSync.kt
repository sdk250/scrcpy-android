package dev.scrcpyandroid.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RemoteStat(val mode: Int, val size: Int, val mtime: Int)

class AdbSyncException(msg: String) : IOException(msg)

private const val SYNC_CHUNK = 64 * 1024

private fun syncRequest(id: String, len: Int, body: ByteArray = ByteArray(0)): ByteArray =
    ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByteArray(Charsets.US_ASCII)).putInt(len).put(body).array()

/** Accumulates bytes from arbitrary WRTE chunks so replies can be parsed by size. */
private class SyncReader(private val stream: AdbStream) {
    private var buf = ByteArray(0)

    suspend fun take(n: Int): ByteArray {
        while (buf.size < n) {
            val chunk = stream.read() ?: throw AdbSyncException("sync stream closed unexpectedly")
            buf += chunk
        }
        val out = buf.copyOfRange(0, n)
        buf = buf.copyOfRange(n, buf.size)
        return out
    }

    suspend fun id(): String = String(take(4), Charsets.US_ASCII)

    suspend fun u32(): Int = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).int
}

suspend fun AdbConnection.stat(path: String): RemoteStat {
    val stream = open("sync:")
    try {
        val p = path.toByteArray()
        stream.write(syncRequest("STAT", p.size, p))
        val r = SyncReader(stream)
        val id = r.id()
        if (id != "STAT") throw AdbSyncException("unexpected sync reply: $id")
        return RemoteStat(mode = r.u32(), size = r.u32(), mtime = r.u32())
    } finally {
        stream.close()
    }
}

suspend fun AdbConnection.push(data: ByteArray, remotePath: String, mode: Int = 33188, mtime: Int) {
    val stream = open("sync:")
    try {
        val spec = "$remotePath,$mode".toByteArray()
        stream.write(syncRequest("SEND", spec.size, spec))
        var off = 0
        while (off < data.size) {
            val n = minOf(SYNC_CHUNK, data.size - off)
            stream.write(syncRequest("DATA", n, data.copyOfRange(off, off + n)))
            off += n
        }
        stream.write(syncRequest("DONE", mtime))
        val r = SyncReader(stream)
        when (val id = r.id()) {
            "OKAY" -> r.u32()
            "FAIL" -> {
                val msg = String(r.take(r.u32()), Charsets.UTF_8)
                throw AdbSyncException(msg)
            }
            else -> throw AdbSyncException("unexpected sync reply: $id")
        }
    } finally {
        stream.close()
    }
}

suspend fun AdbConnection.shell(command: String): AdbStream = open("shell:$command")
