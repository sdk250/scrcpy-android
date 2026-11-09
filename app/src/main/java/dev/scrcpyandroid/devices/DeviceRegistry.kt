package dev.scrcpyandroid.devices

import dev.scrcpyandroid.adb.AdbConnection
import dev.scrcpyandroid.adb.AdbKeyPair
import dev.scrcpyandroid.adb.AdbTlsUnsupportedException
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
    val error: DeviceError? = null,
)

/** Why an entry is OFFLINE; locale-independent, the UI turns it into text. */
sealed class DeviceError {
    data object ConnectionLost : DeviceError()
    data object UsbPermissionDenied : DeviceError()
    data object TlsUnsupported : DeviceError()
    /** Connect/handshake failure; [detail] is the exception message (system text). */
    data class Failed(val detail: String) : DeviceError()
}

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

        /** USB only: how to get this device back (ask for permission again / re-attach). */
        var retry: (() -> Unit)? = null
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

    /**
     * Connects [id] over [transport]. [retry] (USB) is the action [DeviceRegistry.retry] runs when
     * the user taps the entry after it went OFFLINE (connection lost, open failed, disconnected).
     */
    fun attach(id: String, kind: LinkKind, retry: (() -> Unit)? = null, transport: suspend () -> AdbTransport) {
        synchronized(lock) {
            val map = if (kind == LinkKind.USB) usb else tcp
            val slot = map.getOrPut(id) { Slot(DeviceEntry(id, kind, DeviceState.OFFLINE)) }
            if (retry != null) slot.retry = retry
        }
        start(id, kind, transport)
    }

    /**
     * A plugged-in USB device that cannot be used yet (e.g. USB permission denied): listed as
     * OFFLINE with [error]; tapping it runs [retry] (via [DeviceRegistry.retry]).
     */
    fun usbUnavailable(id: String, error: DeviceError, retry: () -> Unit) {
        synchronized(lock) {
            val slot = usb.getOrPut(id) { Slot(DeviceEntry(id, LinkKind.USB, DeviceState.OFFLINE)) }
            stop(slot)
            slot.retry = retry
            slot.entry = DeviceEntry(id, LinkKind.USB, DeviceState.OFFLINE, error = error)
            publish()
        }
    }

    /**
     * User tapped an OFFLINE entry. TCP: reconnect. USB: shown as CONNECTING while its retry action
     * runs (permission dialog or re-attach); the action reports back via [attach] or
     * [usbUnavailable]. No-op for entries that are not OFFLINE.
     */
    fun retry(id: String) {
        val action: () -> Unit
        synchronized(lock) {
            val u = usb[id]
            if (u == null) {
                if (tcp[id]?.entry?.state != DeviceState.OFFLINE) return
                action = { parseHostPort(id)?.let(::connectTcp) }
            } else {
                val r = u.retry
                if (u.entry.state != DeviceState.OFFLINE || u.job != null || r == null) return
                u.entry = DeviceEntry(id, LinkKind.USB, DeviceState.CONNECTING)
                publish()
                action = r
            }
        }
        action()
    }

    /** USB unplugged: stop and remove. */
    fun detach(id: String) {
        synchronized(lock) {
            val slot = usb.remove(id) ?: return
            stop(slot)
            publish()
        }
    }

    /** Closes the connection; the entry stays, OFFLINE, and can be retried (tapped) later. */
    fun disconnect(id: String) {
        synchronized(lock) {
            val slot = usb[id] ?: tcp[id] ?: return
            stop(slot)
            slot.entry = slot.entry.copy(state = DeviceState.OFFLINE, model = null, error = null)
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
            // A job means already connecting / connected. (A USB entry can be CONNECTING without
            // one while its retry action waits for the permission dialog.)
            if (slot.job != null) return
            slot.entry = DeviceEntry(id, kind, DeviceState.CONNECTING)
            // LAZY so `slot.job` is assigned before the body can observe it.
            val job = scope.launch(start = CoroutineStart.LAZY) { run(slot, transport) }
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

    private suspend fun run(slot: Slot, transport: suspend () -> AdbTransport) {
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
            finishClosed(slot, me, DeviceError.ConnectionLost)
        } catch (e: CancellationException) {
            conn?.close()
            throw e
        } catch (e: Throwable) {
            conn?.close()
            finishClosed(
                slot, me,
                if (e is AdbTlsUnsupportedException) DeviceError.TlsUnsupported
                else DeviceError.Failed(e.message ?: e.javaClass.simpleName),
            )
        }
    }

    /**
     * The connection ended or could not be made. The entry stays as OFFLINE with [error] for both
     * kinds (USB entries are removed only by [detach], i.e. when unplugged) so it can be retried.
     */
    private fun finishClosed(slot: Slot, me: Job?, error: DeviceError) {
        synchronized(lock) {
            if (slot.job !== me) return
            slot.conn = null
            slot.job = null
            slot.entry = slot.entry.copy(state = DeviceState.OFFLINE, model = null, error = error)
            publish()
        }
    }
}
