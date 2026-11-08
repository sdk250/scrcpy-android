package dev.scrcpyandroid.adb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AdbConnectionTest {
    private val key = AdbKeyPair.generate()
    private val token = ByteArray(20) { it.toByte() }

    private fun cnxn(payload: String = "device::ro.product.model=X;", max: Int = 4096) =
        AdbMessage(Adb.CNXN, Adb.VERSION, max, payload.toByteArray())

    @Test
    fun handshakeWithAcceptedSignature() = runTest {
        val peer = FakeAdbd()
        var pending = 0
        val c = launchConnect(peer, onPending = { pending++ })
        val hello = peer.expect(Adb.CNXN)
        assertEquals(Adb.VERSION, hello.arg0)
        assertEquals(Adb.MAX_PAYLOAD, hello.arg1)
        assertArrayEquals("host::\u0000".toByteArray(), hello.payload)
        peer.send(Adb.AUTH, Adb.AUTH_TOKEN, 0, token)
        val sig = peer.expect(Adb.AUTH)
        assertEquals(Adb.AUTH_SIGNATURE, sig.arg0)
        assertArrayEquals(key.signToken(token), sig.payload)
        peer.send(cnxn())
        val conn = c.await()
        assertEquals(4096, conn.maxPayload)
        assertEquals("X", conn.model)
        assertEquals(0, pending)
        assertFalse(conn.closed.value)
    }

    @Test
    fun rejectedSignatureSendsPublicKeyThenWaitsForCnxn() = runTest {
        val peer = FakeAdbd()
        var pending = 0
        val c = launchConnect(peer, onPending = { pending++ })
        peer.expect(Adb.CNXN)
        peer.send(Adb.AUTH, Adb.AUTH_TOKEN, 0, token)
        peer.expect(Adb.AUTH)
        peer.send(Adb.AUTH, Adb.AUTH_TOKEN, 0, token)
        val pk = peer.expect(Adb.AUTH)
        assertEquals(Adb.AUTH_RSAPUBLICKEY, pk.arg0)
        assertEquals(0.toByte(), pk.payload.last())
        runCurrent()
        assertEquals(1, pending)
        advanceTimeBy(600_000)
        runCurrent()
        assertFalse(c.isCompleted)
        peer.send(cnxn())
        val conn = c.await()
        assertEquals("X", conn.model)
        assertEquals(1, pending)
    }

    @Test
    fun openAndReadWrite() = runTest {
        val (peer, conn) = connected()
        val opening = async { conn.open("shell:echo hi") }
        val open = peer.expect(Adb.OPEN)
        assertArrayEquals("shell:echo hi\u0000".toByteArray(), open.payload)
        peer.send(Adb.OKAY, 77, open.arg0)
        val s = opening.await()
        peer.send(Adb.WRTE, 77, open.arg0, "hi\n".toByteArray())
        assertEquals("hi\n", String(s.read()!!))
        val ok = peer.expect(Adb.OKAY)
        assertEquals(open.arg0, ok.arg0)
        assertEquals(77, ok.arg1)
        peer.send(Adb.CLSE, 77, open.arg0)
        assertNull(s.read())
        val clse = peer.expect(Adb.CLSE)
        assertEquals(open.arg0, clse.arg0)
        assertEquals(77, clse.arg1)
    }

    @Test
    fun openRefused() = runTest {
        val (peer, conn) = connected()
        val opening = async { runCatching { conn.open("shell:x") } }
        val open = peer.expect(Adb.OPEN)
        peer.send(Adb.CLSE, 0, open.arg0)
        assertTrue(opening.await().exceptionOrNull() is AdbStreamRefusedException)
    }

    @Test
    fun writeChunksToMaxPayloadAndWaitsForOkay() = runTest {
        val (peer, conn) = connected()
        val s = openStream(peer, conn, 5)
        val local = 1
        val w = launch { s.write(ByteArray(10000) { it.toByte() }) }
        val m1 = peer.expect(Adb.WRTE)
        assertEquals(4096, m1.payload.size)
        assertEquals(5, m1.arg1)
        runCurrent()
        assertFalse(peer.hasPendingBytes())
        peer.send(Adb.OKAY, 5, local)
        assertEquals(4096, peer.expect(Adb.WRTE).payload.size)
        runCurrent()
        assertFalse(peer.hasPendingBytes())
        peer.send(Adb.OKAY, 5, local)
        assertEquals(1808, peer.expect(Adb.WRTE).payload.size)
        peer.send(Adb.OKAY, 5, local)
        w.join()
    }

    @Test
    fun okayForWrteIsDeferredUntilConsumerReads() = runTest {
        val (peer, conn) = connected()
        val s = openStream(peer, conn, 5)
        peer.send(Adb.WRTE, 5, 1, "abc".toByteArray())
        runCurrent()
        assertFalse(peer.hasPendingBytes())
        assertEquals("abc", String(s.read()!!))
        val ok = peer.expect(Adb.OKAY)
        assertEquals(1, ok.arg0)
        assertEquals(5, ok.arg1)
    }

    @Test
    fun transportEofClosesStreams() = runTest {
        val (peer, conn) = connected()
        val s = openStream(peer, conn, 5)
        peer.close()
        assertNull(s.read())
        runCurrent()
        assertTrue(conn.closed.value)
    }

    @Test
    fun stlsRejected() = runTest {
        val peer = FakeAdbd()
        val c = async { runCatching { AdbConnection.connect(peer.transport, key, backgroundScope) } }
        peer.expect(Adb.CNXN)
        peer.send(Adb.STLS, 1, 0)
        val e = c.await().exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("TLS"))
    }

    @Test
    fun oversizedPayloadLengthClosesConnection() = runTest {
        val (peer, conn) = connected()
        val s = openStream(peer, conn, 5)
        val bad = AdbMessage(Adb.WRTE, 5, 1).encode()
        java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(12, Adb.MAX_PAYLOAD + 1)
        peer.sendRaw(bad)
        assertNull(s.read())
        runCurrent()
        assertTrue(conn.closed.value)
    }

    @Test
    fun inputStreamReadsBlockingAndEndsOnClse() = runTest {
        val (peer, conn) = connected()
        val s = openStream(peer, conn, 5)
        peer.send(Adb.WRTE, 5, 1, "abc".toByteArray())
        peer.send(Adb.WRTE, 5, 1, "de".toByteArray())
        peer.send(Adb.CLSE, 5, 1)
        runCurrent()
        val input = s.asInputStream()
        val out = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray> { input.readBytes() }
        assertEquals("abcde", String(out.get(5, java.util.concurrent.TimeUnit.SECONDS)))
    }

    @Test
    fun closingConnectionCompletesPendingOpen() = runTest {
        val (peer, conn) = connected()
        val opening = async { runCatching { conn.open("shell:x") } }
        peer.expect(Adb.OPEN)
        conn.close()
        assertTrue(opening.await().isFailure)
        assertTrue(conn.closed.value)
    }

    // --- helpers ---

    private fun kotlinx.coroutines.test.TestScope.launchConnect(peer: FakeAdbd, onPending: () -> Unit = {}) =
        async { AdbConnection.connect(peer.transport, key, backgroundScope, onPending) }

    private suspend fun kotlinx.coroutines.test.TestScope.connected(): Pair<FakeAdbd, AdbConnection> {
        val peer = FakeAdbd()
        val c = launchConnect(peer)
        peer.acceptConnect()
        return peer to c.await()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.openStream(peer: FakeAdbd, conn: AdbConnection, remote: Int): AdbStream {
        val opening = async { conn.open("shell:x") }
        val open = peer.expect(Adb.OPEN)
        peer.send(Adb.OKAY, remote, open.arg0)
        return opening.await()
    }
}
