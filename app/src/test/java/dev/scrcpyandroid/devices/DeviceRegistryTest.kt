package dev.scrcpyandroid.devices

import dev.scrcpyandroid.adb.Adb
import dev.scrcpyandroid.adb.AdbKeyPair
import dev.scrcpyandroid.adb.AdbTransport
import dev.scrcpyandroid.adb.FakeAdbd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistryTest {
    private val key = AdbKeyPair.generate()
    private val addr = HostPort("10.0.0.2", 5555)
    private val token = ByteArray(20) { it.toByte() }

    private class MemStore(var list: List<String> = emptyList()) : AddressStore {
        override fun load() = list
        override fun save(list: List<String>) {
            this.list = list
        }
    }

    private fun TestScope.registry(
        store: AddressStore = MemStore(),
        factory: suspend (HostPort) -> AdbTransport,
    ) = DeviceRegistry(backgroundScope as CoroutineScope, { key }, store, factory)

    private fun cnxn(peer: FakeAdbd) = peer.send(Adb.CNXN, Adb.VERSION, 4096, "device::ro.product.model=Pixel;".toByteArray())

    @Test
    fun tcpConnectLifecycle() = runTest {
        val store = MemStore()
        val peer = FakeAdbd()
        val reg = registry(store) { peer.transport }
        val seen = mutableListOf<DeviceState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reg.devices.collect { l -> l.firstOrNull()?.let { if (seen.lastOrNull() != it.state) seen += it.state } }
        }
        reg.connectTcp(addr)
        runCurrent()
        assertEquals(DeviceState.CONNECTING, reg.devices.value.single().state)
        assertTrue(store.load().contains("10.0.0.2:5555"))
        peer.expect(Adb.CNXN)
        cnxn(peer)
        runCurrent()
        val e = reg.devices.value.single()
        assertEquals(listOf(DeviceState.CONNECTING, DeviceState.DEVICE), seen)
        assertEquals(DeviceState.DEVICE, e.state)
        assertEquals("Pixel", e.model)
        assertEquals("10.0.0.2:5555", e.id)
        assertEquals(LinkKind.TCP, e.kind)
        assertNotNull(reg.connection(e.id))
    }

    @Test
    fun connectOnActiveEntryIsNoOp() = runTest {
        val peer = FakeAdbd()
        var calls = 0
        val reg = registry { calls++; peer.transport }
        reg.connectTcp(addr)
        runCurrent()
        reg.connectTcp(addr)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, reg.devices.value.size)
    }

    @Test
    fun tcpConnectFailureGoesOffline() = runTest {
        val reg = registry { throw ConnectException("refused") }
        reg.connectTcp(addr)
        runCurrent()
        val e = reg.devices.value.single()
        assertEquals(DeviceState.OFFLINE, e.state)
        assertTrue(e.error!!.contains("refused"))
    }

    @Test
    fun reconnectOfflineEntryReusesEntry() = runTest {
        var fail = true
        val peer = FakeAdbd()
        val reg = registry { if (fail) throw ConnectException("refused") else peer.transport }
        reg.connectTcp(addr)
        runCurrent()
        fail = false
        reg.connectTcp(addr)
        runCurrent()
        assertEquals(1, reg.devices.value.size)
        assertEquals(DeviceState.CONNECTING, reg.devices.value.single().state)
        assertNull(reg.devices.value.single().error)
    }

    @Test
    fun unauthorizedState() = runTest {
        val peer = FakeAdbd()
        val reg = registry { peer.transport }
        reg.connectTcp(addr)
        runCurrent()
        peer.expect(Adb.CNXN)
        peer.send(Adb.AUTH, Adb.AUTH_TOKEN, 0, token)
        peer.expect(Adb.AUTH)
        peer.send(Adb.AUTH, Adb.AUTH_TOKEN, 0, token)
        peer.expect(Adb.AUTH)
        runCurrent()
        assertEquals(DeviceState.UNAUTHORIZED, reg.devices.value.single().state)
        cnxn(peer)
        runCurrent()
        assertEquals(DeviceState.DEVICE, reg.devices.value.single().state)
    }

    @Test
    fun transportDropGoesOffline() = runTest {
        val peer = FakeAdbd()
        val reg = registry { peer.transport }
        reg.connectTcp(addr)
        runCurrent()
        peer.expect(Adb.CNXN)
        cnxn(peer)
        runCurrent()
        assertEquals(DeviceState.DEVICE, reg.devices.value.single().state)
        peer.transport.close()
        runCurrent()
        assertEquals(DeviceState.OFFLINE, reg.devices.value.single().state)
        assertNull(reg.connection(addr.toString()))
    }

    @Test
    fun disconnectTcpGoesOffline() = runTest {
        val peer = FakeAdbd()
        val reg = registry { peer.transport }
        reg.connectTcp(addr)
        runCurrent()
        peer.expect(Adb.CNXN)
        cnxn(peer)
        runCurrent()
        reg.disconnect(addr.toString())
        runCurrent()
        assertEquals(DeviceState.OFFLINE, reg.devices.value.single().state)
        assertNull(reg.connection(addr.toString()))
    }

    @Test
    fun startupShowsRememberedAddressesOffline() = runTest {
        val reg = registry(MemStore(listOf("a:5555", "b:5556"))) { error("unused") }
        assertEquals(listOf("a:5555", "b:5556"), reg.devices.value.map { it.id })
        assertTrue(reg.devices.value.all { it.state == DeviceState.OFFLINE && it.kind == LinkKind.TCP })
    }

    @Test
    fun forgetRemovesFromStore() = runTest {
        val store = MemStore(listOf("a:5555", "b:5556"))
        val reg = registry(store) { error("unused") }
        reg.forget("a:5555")
        assertEquals(listOf("b:5556"), store.load())
        assertEquals(listOf("b:5556"), reg.devices.value.map { it.id })
    }

    @Test
    fun usbAttachDetachAndOrdering() = runTest {
        val peer = FakeAdbd()
        val reg = registry(MemStore(listOf("a:5555"))) { error("unused") }
        reg.attach("SER1", LinkKind.USB) { peer.transport }
        runCurrent()
        assertEquals(listOf("SER1", "a:5555"), reg.devices.value.map { it.id })
        peer.expect(Adb.CNXN)
        cnxn(peer)
        runCurrent()
        assertEquals(DeviceState.DEVICE, reg.devices.value.first().state)
        reg.detach("SER1")
        runCurrent()
        assertEquals(listOf("a:5555"), reg.devices.value.map { it.id })
    }

    @Test
    fun usbOpenFailureGoesOfflineWithError() = runTest {
        val reg = registry { error("unused") }
        reg.attach("SER1", LinkKind.USB) { throw SecurityException("no permission") }
        runCurrent()
        val e = reg.devices.value.single()
        assertEquals(DeviceState.OFFLINE, e.state)
        assertTrue(e.error!!.contains("no permission"))
    }
}
