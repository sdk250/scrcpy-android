package dev.scrcpyandroid.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** Blocking bulk-transfer view of a claimed ADB USB interface. Negative return means failure. */
interface UsbIo : Closeable {
    fun bulkOut(buf: ByteArray, off: Int, len: Int): Int
    fun bulkIn(buf: ByteArray, len: Int): Int
    val maxPacketSize: Int
}

class UsbTransport(
    private val io: UsbIo,
    override val description: String,
) : AdbTransport {
    private val inBuf = ByteArray(maxOf(READ_BUF_SIZE / io.maxPacketSize, 1) * io.maxPacketSize)
    private var cache = ByteArray(0)
    private var cachePos = 0

    @Volatile
    private var closed = false

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            var off = 0
            while (off < bytes.size) {
                if (closed) throw IOException("USB transport closed")
                val len = minOf(WRITE_SEGMENT, bytes.size - off)
                if (io.bulkOut(bytes, off, len) < 0) throw IOException("USB transfer failed")
                off += len
            }
        }
    }

    override suspend fun readFully(n: Int): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val avail = cache.size - cachePos
            if (avail > 0) {
                val c = minOf(avail, n - got)
                System.arraycopy(cache, cachePos, out, got, c)
                cachePos += c
                got += c
                continue
            }
            if (closed) throw IOException("USB transport closed")
            val r = io.bulkIn(inBuf, inBuf.size)
            if (r < 0) throw IOException("USB transfer failed")
            if (r == 0) continue
            val c = minOf(r, n - got)
            System.arraycopy(inBuf, 0, out, got, c)
            got += c
            if (r > c) {
                cache = inBuf.copyOfRange(c, r)
                cachePos = 0
            }
        }
        out
    }

    override fun close() {
        closed = true
        try {
            io.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val WRITE_SEGMENT = 16384
        const val READ_BUF_SIZE = 16384
    }
}

object UsbAdb {
    private const val ADB_CLASS = 255
    private const val ADB_SUBCLASS = 66
    private const val ADB_PROTOCOL = 1
    private const val OUT_TIMEOUT_MS = 5000

    fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == ADB_CLASS &&
                intf.interfaceSubclass == ADB_SUBCLASS &&
                intf.interfaceProtocol == ADB_PROTOCOL
            ) {
                return intf
            }
        }
        return null
    }

    fun open(manager: UsbManager, device: UsbDevice): UsbTransport {
        val intf = findAdbInterface(device) ?: throw IOException("No ADB interface on USB device ${device.deviceName}")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IOException("ADB interface has no bulk IN/OUT endpoints")
        val conn = manager.openDevice(device) ?: throw IOException("Cannot open USB device ${device.deviceName}")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("Cannot claim ADB USB interface")
        }
        return UsbTransport(RealUsbIo(conn, intf, inEp, outEp), "usb:${device.deviceName}")
    }

    private class RealUsbIo(
        private val conn: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val inEp: UsbEndpoint,
        private val outEp: UsbEndpoint,
    ) : UsbIo {
        override val maxPacketSize: Int = inEp.maxPacketSize.coerceAtLeast(64)

        override fun bulkOut(buf: ByteArray, off: Int, len: Int): Int =
            conn.bulkTransfer(outEp, buf, off, len, OUT_TIMEOUT_MS)

        override fun bulkIn(buf: ByteArray, len: Int): Int = conn.bulkTransfer(inEp, buf, len, 0)

        override fun close() {
            try {
                conn.releaseInterface(intf)
            } finally {
                conn.close()
            }
        }
    }
}
