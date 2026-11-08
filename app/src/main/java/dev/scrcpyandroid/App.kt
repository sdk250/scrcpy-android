package dev.scrcpyandroid

import android.app.Application
import dev.scrcpyandroid.adb.AdbKeyStore
import dev.scrcpyandroid.devices.DeviceRegistry
import dev.scrcpyandroid.devices.PrefsAddressStore
import dev.scrcpyandroid.devices.UsbMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Exactly one AdbKeyStore: its lock is per-instance.
    lateinit var keyStore: AdbKeyStore
        private set
    lateinit var registry: DeviceRegistry
        private set
    private lateinit var usbMonitor: UsbMonitor

    // TODO(later tasks): SettingsRepository singleton goes here.

    override fun onCreate() {
        super.onCreate()
        keyStore = AdbKeyStore(File(filesDir, "adb"))
        registry = DeviceRegistry(appScope, { keyStore.loadOrCreate() }, PrefsAddressStore(this))
        usbMonitor = UsbMonitor(this, registry).also { it.start() }
    }
}
