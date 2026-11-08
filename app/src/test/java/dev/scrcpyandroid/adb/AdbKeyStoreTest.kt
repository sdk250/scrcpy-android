package dev.scrcpyandroid.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbKeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun storeCreatesOnceAndPersistsComment() {
        val dir = tmp.newFolder("keys")
        val a = AdbKeyStore(dir).loadOrCreate()
        val b = AdbKeyStore(dir).loadOrCreate()
        assertEquals(a.public.modulus, b.public.modulus)
        assertEquals(a.comment, b.comment)
        val store = AdbKeyStore(dir)
        store.save(a.withComment("me@pc"))
        val c = store.loadOrCreate()
        assertEquals("me@pc", c.comment)
        assertEquals(a.public.modulus, c.public.modulus)
    }

    @Test
    fun importGarbageThrowsAndKeepsExisting() {
        val store = AdbKeyStore(File(tmp.root, "k2"))
        val k1 = AdbKeyPair.generate()
        store.save(k1)
        assertThrows(InvalidKeyFormatException::class.java) { AdbKeyPair.importPem("hello", "x") }
        assertEquals(k1.public.modulus, store.loadOrCreate().public.modulus)
    }

    @Test
    fun corruptKeyIsPreservedAndReplaced() {
        val dir = tmp.newFolder("k3")
        File(dir, "adbkey").writeText("garbage")
        val k = AdbKeyStore(dir).loadOrCreate()
        assertEquals(2048, k.public.modulus.bitLength())
        assertEquals("garbage", File(dir, "adbkey.corrupt").readText())
        assertEquals(k.public.modulus, AdbKeyStore(dir).loadOrCreate().public.modulus)
    }
}
