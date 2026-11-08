package dev.scrcpyandroid.adb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class AdbSyncTest {
    private val key = AdbKeyPair.generate()

    private fun le(vararg v: Int): ByteArray =
        ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

    private suspend fun TestScope.connected(): Pair<FakeAdbd, AdbConnection> {
        val peer = FakeAdbd()
        val c = async { AdbConnection.connect(peer.transport, key, backgroundScope) }
        peer.acceptConnect()
        return peer to c.await()
    }

    @Test
    fun statParsesReply() = runTest {
        val (peer, conn) = connected()
        val r = async { conn.stat("/sdcard/a") }
        val (local, remote) = peer.acceptOpen("sync:")
        val req = peer.readStreamData(local, remote, 8 + 9)
        assertArrayEquals("STAT".toByteArray() + le(9) + "/sdcard/a".toByteArray(), req)
        val reply = "STAT".toByteArray() + le(0x81A4, 1234, 99)
        // deliver in awkward chunks
        peer.send(Adb.WRTE, remote, local, reply.copyOfRange(0, 5))
        peer.expect(Adb.OKAY)
        peer.send(Adb.WRTE, remote, local, reply.copyOfRange(5, reply.size))
        assertEquals(RemoteStat(0x81A4, 1234, 99), r.await())
    }

    @Test
    fun pushSendsSendDataDone() = runTest {
        val (peer, conn) = connected()
        val data = ByteArray(70000) { it.toByte() }
        val r = async { conn.push(data, "/data/local/tmp/x", mtime = 555) }
        val (local, remote) = peer.acceptOpen("sync:")
        val spec = "/data/local/tmp/x,33188".toByteArray()
        val total = 8 + spec.size + 8 + 65536 + 8 + 4464 + 8
        val got = peer.readStreamData(local, remote, total)
        val expected = "SEND".toByteArray() + le(spec.size) + spec +
            "DATA".toByteArray() + le(65536) + data.copyOfRange(0, 65536) +
            "DATA".toByteArray() + le(4464) + data.copyOfRange(65536, 70000) +
            "DONE".toByteArray() + le(555)
        assertEquals(expected.size, got.size)
        assertArrayEquals(expected, got)
        peer.send(Adb.WRTE, remote, local, "OKAY".toByteArray() + le(0))
        r.await()
    }

    @Test
    fun pushFailRaisesMessage() = runTest {
        val (peer, conn) = connected()
        val r = async { runCatching { conn.push(ByteArray(10), "/x", mtime = 1) } }
        val (local, remote) = peer.acceptOpen("sync:")
        peer.readStreamData(local, remote, 8 + "/x,33188".length + 8 + 10 + 8)
        val msg = "Permission denied".toByteArray()
        peer.send(Adb.WRTE, remote, local, "FAIL".toByteArray() + le(msg.size) + msg)
        val e = r.await().exceptionOrNull()
        assertTrue(e is AdbSyncException)
        assertTrue(e!!.message!!.contains("Permission denied"))
    }

    @Test
    fun truncatedReplyThrowsSyncException() = runTest {
        val (peer, conn) = connected()
        val r = async { runCatching { conn.stat("/a") } }
        val (local, remote) = peer.acceptOpen("sync:")
        peer.readStreamData(local, remote, 10)
        peer.send(Adb.WRTE, remote, local, "STAT".toByteArray())
        peer.send(Adb.CLSE, remote, local)
        assertTrue(r.await().exceptionOrNull() is AdbSyncException)
    }

    @Test
    fun shellOpensShellService() = runTest {
        val (peer, conn) = connected()
        val r = async { conn.shell("ls") }
        peer.acceptOpen("shell:ls")
        r.await()
    }
}
