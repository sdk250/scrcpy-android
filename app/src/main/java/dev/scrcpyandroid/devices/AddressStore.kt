package dev.scrcpyandroid.devices

import android.content.Context

interface AddressStore {
    fun load(): List<String>
    fun save(list: List<String>)
}

/** Remembered TCP addresses, kept in insertion order (newline-joined; addresses never contain newlines). */
class PrefsAddressStore(context: Context) : AddressStore {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    override fun load(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    override fun save(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "tcp_addresses"
    }
}
