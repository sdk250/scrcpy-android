package dev.scrcpyandroid.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyFingerprintTest {
    private val key = AdbKeyPair.generate()

    @Test
    fun formatIs16LowercaseHexPairsWith15Colons() {
        val fp = key.fingerprint()
        assertEquals(47, fp.length)
        assertEquals(15, fp.count { it == ':' })
        assertTrue(Regex("([0-9a-f]{2}:){15}[0-9a-f]{2}").matches(fp))
    }

    @Test
    fun equalKeysGiveEqualFingerprintsRegardlessOfComment() {
        val again = AdbKeyPair.importPem(key.exportPrivatePem(), "other")
        assertEquals(key.fingerprint(), again.fingerprint())
        assertEquals(key.fingerprint(), key.withComment("x").fingerprint())
    }

    @Test
    fun differentKeysDiffer() {
        assertTrue(key.fingerprint() != AdbKeyPair.generate().fingerprint())
    }
}

class SanitizeCommentTest {
    @Test
    fun stripsNewlinesAndTrims() {
        assertEquals("a b", sanitizeComment("  a\r\n b \n", "old"))
    }

    @Test
    fun blankKeepsPrevious() {
        assertEquals("old", sanitizeComment(" \r\n ", "old"))
    }
}
