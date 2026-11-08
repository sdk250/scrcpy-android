package dev.scrcpyandroid.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPortTest {
    @Test
    fun parseHostPortCases() {
        assertEquals(HostPort("192.168.1.5", 5555), parseHostPort("192.168.1.5"))
        assertEquals(HostPort("192.168.1.5", 5556), parseHostPort(" 192.168.1.5:5556 "))
        assertNull(parseHostPort("host:abc"))
        assertNull(parseHostPort(""))
        assertNull(parseHostPort("   "))
        assertNull(parseHostPort("h:0"))
        assertNull(parseHostPort("h:70000"))
        assertNull(parseHostPort(":5555"))
        assertEquals(HostPort("::1", 5555), parseHostPort("[::1]:5555"))
        assertEquals(HostPort("::1", 5555), parseHostPort("[::1]"))
        assertEquals("192.168.1.5:5555", HostPort("192.168.1.5", 5555).toString())
    }
}
