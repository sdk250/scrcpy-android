package dev.scrcpyandroid.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyTest {
    private val key = AdbKeyPair.generate("test@host")

    private fun words(buf: ByteBuffer, count: Int): BigInteger {
        var v = BigInteger.ZERO
        for (i in 0 until count) {
            val w = BigInteger.valueOf(buf.int.toLong() and 0xFFFFFFFFL)
            v = v.or(w.shiftLeft(32 * i))
        }
        return v
    }

    @Test
    fun publicKeyStructIsValid() {
        val text = key.exportPublic()
        assertTrue(text.endsWith(" test@host"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.int)
        val n0inv = buf.int.toLong() and 0xFFFFFFFFL
        val n = words(buf, 64)
        val rr = words(buf, 64)
        val exponent = buf.int
        assertEquals(65537, exponent)
        assertEquals(key.public.modulus, n)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        val n0 = n.toLong() and 0xFFFFFFFFL
        assertEquals(0xFFFFFFFFL, (n0inv * n0) and 0xFFFFFFFFL)
    }

    @Test
    fun payloadIsPublicTextPlusNul() {
        val p = key.publicKeyPayload()
        assertEquals(0.toByte(), p.last())
        assertArrayEquals(key.exportPublic().toByteArray(Charsets.UTF_8), p.copyOf(p.size - 1))
    }

    @Test
    fun signatureVerifiesAsDigestInfoPlusToken() {
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val sig = key.signToken(token)
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, key.public)
        val prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        assertArrayEquals(prefix + token, c.doFinal(sig))
    }

    @Test
    fun exportImportRoundTrip() {
        val pem = key.exportPrivatePem()
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"))
        val back = AdbKeyPair.importPem(pem, "x")
        assertEquals(key.public.modulus, back.public.modulus)
        assertEquals("x", back.comment)
    }

    @Test
    fun importToleratesCrlfAndWhitespace() {
        val pem = "\r\n  " + key.exportPrivatePem().replace("\n", "\r\n  ") + "\r\n\r\n"
        assertEquals(key.public.modulus, AdbKeyPair.importPem(pem, "x").public.modulus)
    }

    @Test
    fun importPkcs1Pem() {
        val pkcs1 = pkcs1FromPkcs8(key.private.encoded)
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1)
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n$b64\n-----END RSA PRIVATE KEY-----\n"
        assertEquals(key.public.modulus, AdbKeyPair.importPem(pem, "x").public.modulus)
    }

    @Test
    fun importRejectsNonRsa() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val b64 = Base64.getEncoder().encodeToString(ec.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
        assertThrows(InvalidKeyFormatException::class.java) { AdbKeyPair.importPem(pem, "x") }
    }

    @Test
    fun importRejectsBadBase64AndDer() {
        assertThrows(InvalidKeyFormatException::class.java) {
            AdbKeyPair.importPem("-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----", "x")
        }
        assertThrows(InvalidKeyFormatException::class.java) {
            AdbKeyPair.importPem("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----", "x")
        }
    }

    @Test
    fun withCommentKeepsKey() {
        val k2 = key.withComment("me")
        assertEquals("me", k2.comment)
        assertEquals(key.public.modulus, k2.public.modulus)
    }

    // SEQUENCE -> skip INTEGER version -> skip AlgorithmIdentifier SEQUENCE -> OCTET STRING contents
    private fun pkcs1FromPkcs8(der: ByteArray): ByteArray {
        var pos = 0
        fun readHeader(): Pair<Int, Int> { // tag, length; pos moves to content start
            val tag = der[pos++].toInt() and 0xFF
            var len = der[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (der[pos++].toInt() and 0xFF) }
            }
            return tag to len
        }
        assertEquals(0x30, readHeader().first)
        val (t1, l1) = readHeader(); assertEquals(0x02, t1); pos += l1
        val (t2, l2) = readHeader(); assertEquals(0x30, t2); pos += l2
        val (t3, l3) = readHeader(); assertEquals(0x04, t3)
        return der.copyOfRange(pos, pos + l3)
    }
}
