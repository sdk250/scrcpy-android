package dev.scrcpyandroid.devices

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import dev.scrcpyandroid.adb.UsbAdb

/** Thin glue between USB broadcasts and [DeviceRegistry]; the state machine lives in the registry. */
class UsbMonitor(context: Context, private val registry: DeviceRegistry) {
    private val context = context.applicationContext
    private val usb = this.context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Registry id per attached device name, so detach can remove it even after serialNumber becomes unreadable.
    private val ids = HashMap<String, String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    onPermitted(device)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usb.deviceList.values.forEach(::onAttached)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    private fun onAttached(device: UsbDevice) {
        if (UsbAdb.findAdbInterface(device) == null) return
        if (usb.hasPermission(device)) onPermitted(device) else requestPermission(device)
    }

    private fun onPermitted(device: UsbDevice) {
        val id = ids.getOrPut(device.deviceName) {
            try {
                device.serialNumber
            } catch (_: SecurityException) {
                null
            } ?: device.deviceName
        }
        // A SecurityException from openDevice surfaces as an OFFLINE entry with an error.
        registry.attach(id, LinkKind.USB) { UsbAdb.open(usb, device) }
    }

    private fun onDetached(device: UsbDevice) {
        val id = ids.remove(device.deviceName) ?: return
        registry.detach(id)
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(device, pi)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_PERMISSION = "dev.scrcpyandroid.USB_PERMISSION"
    }
}
