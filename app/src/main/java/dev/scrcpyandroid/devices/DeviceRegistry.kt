package dev.scrcpyandroid.devices

import dev.scrcpyandroid.adb.AdbConnection
import dev.scrcpyandroid.adb.AdbKeyPair
import dev.scrcpyandroid.adb.AdbTransport
import dev.scrcpyandroid.adb.TcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class DeviceState { CONNECTING, UNAUTHORIZED, DEVICE, OFFLINE }
enum class LinkKind { USB, TCP }

data class DeviceEntry(
    val id: String,
    val kind: LinkKind,
    val state: DeviceState,
    val model: String? = null,
    val error: String? = null,
)

/** In-app "adb devices": USB first, then TCP in insertion order. */
class DeviceRegistry(
    private val scope: CoroutineScope,
    private val keys: () -> AdbKeyPair,
    private val store: AddressStore,
    private val tcpFactory: suspend (HostPort) -> AdbTransport = { TcpTransport.open(it.host, it.port) },
) {
    private class Slot(var entry: DeviceEntry) {
        var job: Job? = null
        var conn: AdbConnection? = null
    }

    private val lock = Any()
    private val usb = LinkedHashMap<String, Slot>()
    private val tcp = LinkedHashMap<String, Slot>()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices

    init {
        synchronized(lock) {
            for (a in store.load()) tcp.getOrPut(a) { Slot(DeviceEntry(a, LinkKind.TCP, DeviceState.OFFLINE)) }
            publish()
        }
    }

    fun connectTcp(addr: HostPort) {
        val id = addr.toString()
        synchronized(lock) {
            if (id !in tcp) {
                tcp[id] = Slot(DeviceEntry(id, LinkKind.TCP, DeviceState.OFFLINE))
                store.save(tcp.keys.toList())
            }
        }
        start(id, LinkKind.TCP) { tcpFactory(addr) }
    }

    fun attach(id: String, kind: LinkKind, transport: suspend () -> AdbTransport) {
        synchronized(lock) {
            val map = if (kind == LinkKind.USB) usb else tcp
            if (id !in map) map[id] = Slot(DeviceEntry(id, kind, DeviceState.OFFLINE))
        }
        start(id, kind, transport)
    }

    /** USB unplugged: stop and remove. */
    fun detach(id: String) {
        synchronized(lock) {
            val slot = usb.remove(id) ?: return
            stop(slot)
            publish()
        }
    }

    fun disconnect(id: String) {
        synchronized(lock) {
            val slot = usb[id]
            if (slot != null) {
                usb.remove(id)
                stop(slot)
            } else {
                val t = tcp[id] ?: return
                stop(t)
                t.entry = t.entry.copy(state = DeviceState.OFFLINE, model = null, error = null)
            }
            publish()
        }
    }

    fun forget(id: String) {
        synchronized(lock) {
            (usb.remove(id) ?: tcp.remove(id))?.let { stop(it) }
            store.save(tcp.keys.toList())
            publish()
        }
    }

    fun connection(id: String): AdbConnection? = synchronized(lock) { (usb[id] ?: tcp[id])?.conn }

    // Callers hold the lock.
    private fun stop(slot: Slot) {
        slot.job?.cancel()
        slot.job = null
        slot.conn?.close()
        slot.conn = null
    }

    private fun publish() {
        _devices.value = (usb.values + tcp.values).map { it.entry }
    }

    private fun start(id: String, kind: LinkKind, transport: suspend () -> AdbTransport) {
        synchronized(lock) {
            val slot = (if (kind == LinkKind.USB) usb[id] else tcp[id]) ?: return
            if (slot.entry.state != DeviceState.OFFLINE) return // already connecting / connected
            slot.entry = DeviceEntry(id, kind, DeviceState.CONNECTING)
            // LAZY so `slot.job` is assigned before the body can observe it.
            val job = scope.launch(start = CoroutineStart.LAZY) { run(slot, id, kind, transport) }
            slot.job = job
            publish()
            job.start()
        }
    }

    private fun update(slot: Slot, expected: Job?, change: (DeviceEntry) -> DeviceEntry, conn: AdbConnection? = null) {
        synchronized(lock) {
            if (slot.job !== expected) return // superseded by disconnect/forget/reconnect
            if (conn != null) slot.conn = conn
            slot.entry = change(slot.entry)
            publish()
        }
    }

    private suspend fun run(slot: Slot, id: String, kind: LinkKind, transport: suspend () -> AdbTransport) {
        val me = synchronized(lock) { slot.job }
        var conn: AdbConnection? = null
        try {
            val t = transport()
            conn = AdbConnection.connect(t, keys(), scope) {
                update(slot, me, { it.copy(state = DeviceState.UNAUTHORIZED) })
            }
            val c = conn
            update(slot, me, { it.copy(state = DeviceState.DEVICE, model = c.model, error = null) }, c)
            c.closed.first { it }
            finishClosed(slot, me, id, kind, "Connection closed")
        } catch (e: CancellationException) {
            conn?.close()
            throw e
        } catch (e: Throwable) {
            conn?.close()
            finishClosed(slot, me, id, kind, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun finishClosed(slot: Slot, me: Job?, id: String, kind: LinkKind, error: String) {
        synchronized(lock) {
            if (slot.job !== me) return
            slot.conn = null
            slot.job = null
            if (kind == LinkKind.USB && error == "Connection closed") {
                usb.remove(id)
            } else {
                slot.entry = slot.entry.copy(state = DeviceState.OFFLINE, model = null, error = error)
            }
            publish()
        }
    }
}
