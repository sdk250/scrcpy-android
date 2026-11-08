package dev.scrcpyandroid.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

class InvalidKeyFormatException(msg: String) : Exception(msg)

class AdbKeyPair(
    val private: RSAPrivateCrtKey,
    val public: RSAPublicKey,
    val comment: String,
) {
    /** Signs an AUTH token: PKCS#1 v1.5 padding over SHA1 DigestInfo prefix + token (no extra hashing). */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, private)
        return cipher.doFinal(SHA1_DIGEST_INFO_PREFIX + token)
    }

    /** Payload for AUTH RSAPUBLICKEY: public key text followed by a NUL byte. */
    fun publicKeyPayload(): ByteArray = exportPublic().toByteArray(Charsets.UTF_8) + 0.toByte()

    fun exportPrivatePem(): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(private.encoded)
        return "$PKCS8_BEGIN\n$body\n$PKCS8_END\n"
    }

    /** "<base64 of Android RSAPublicKey struct> <comment>" (no trailing NUL). */
    fun exportPublic(): String {
        val b64 = Base64.getEncoder().encodeToString(androidPublicKeyStruct(public))
        return "$b64 $comment"
    }

    fun withComment(c: String): AdbKeyPair = AdbKeyPair(private, public, c)

    companion object {
        private const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
        private const val PKCS8_END = "-----END PRIVATE KEY-----"
        private const val PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"
        private const val PKCS1_END = "-----END RSA PRIVATE KEY-----"
        private const val WORDS = 64

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun generate(comment: String = "scrcpy-android@android"): AdbKeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val kp = gen.generateKeyPair()
            return AdbKeyPair(kp.private as RSAPrivateCrtKey, kp.public as RSAPublicKey, comment)
        }

        fun importPem(pem: String, comment: String): AdbKeyPair {
            try {
                val text = pem.trim()
                val pkcs8: ByteArray = when {
                    PKCS8_BEGIN in text -> pkcs8FromBody(text, PKCS8_BEGIN, PKCS8_END)
                    PKCS1_BEGIN in text -> wrapPkcs1(decodeBody(text, PKCS1_BEGIN, PKCS1_END))
                    else -> throw InvalidKeyFormatException(
                        "Not a PEM private key: expected a \"$PKCS8_BEGIN\" or \"$PKCS1_BEGIN\" block",
                    )
                }
                val priv = try {
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                } catch (e: Exception) {
                    throw InvalidKeyFormatException("Not a valid RSA private key: ${e.message ?: "unreadable key data"}")
                }
                if (priv !is RSAPrivateCrtKey) {
                    throw InvalidKeyFormatException("Not an RSA private key with CRT parameters")
                }
                val pub = KeyFactory.getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
                return AdbKeyPair(priv, pub, comment)
            } catch (e: InvalidKeyFormatException) {
                throw e
            } catch (e: Exception) {
                throw InvalidKeyFormatException("Could not read private key: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun pkcs8FromBody(text: String, begin: String, end: String) = decodeBody(text, begin, end)

        private fun decodeBody(text: String, begin: String, end: String): ByteArray {
            val start = text.indexOf(begin)
            val stop = text.indexOf(end)
            if (start < 0 || stop < 0 || stop < start) {
                throw InvalidKeyFormatException("Malformed PEM: missing \"$end\" marker")
            }
            val body = text.substring(start + begin.length, stop).filterNot { it.isWhitespace() }
            if (body.isEmpty()) throw InvalidKeyFormatException("Malformed PEM: empty key body")
            return try {
                Base64.getDecoder().decode(body)
            } catch (e: IllegalArgumentException) {
                throw InvalidKeyFormatException("Malformed PEM: key body is not valid Base64")
            }
        }

        // SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING { pkcs1 } }
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val algId = byteArrayOf(
                0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
                0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            val inner = ByteArrayOutputStream()
            inner.write(byteArrayOf(0x02, 0x01, 0x00))
            inner.write(algId)
            inner.write(0x04)
            inner.write(derLength(pkcs1.size))
            inner.write(pkcs1)
            val innerBytes = inner.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(0x30)
            out.write(derLength(innerBytes.size))
            out.write(innerBytes)
            return out.toByteArray()
        }

        private fun derLength(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
            len <= 0xFFFF -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(
                0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte(),
            )
        }

        /** Android's `RSAPublicKey` struct: len, n0inv, n[64], rr[64], exponent; little-endian u32s. */
        private fun androidPublicKeyStruct(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val two32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toLong()
            val rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n)
            val buf = ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(WORDS)
            buf.putInt(n0inv.toInt())
            putWords(buf, n)
            putWords(buf, rr)
            buf.putInt(key.publicExponent.toInt())
            return buf.array()
        }

        private fun putWords(buf: ByteBuffer, v: BigInteger) {
            val mask = BigInteger.valueOf(0xFFFFFFFFL)
            for (i in 0 until WORDS) buf.putInt(v.shiftRight(32 * i).and(mask).toLong().toInt())
        }
    }
}
