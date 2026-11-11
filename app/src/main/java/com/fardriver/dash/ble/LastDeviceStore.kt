package com.fardriver.dash.ble

import android.content.Context

/**
 * Merkt sich die BLE-Adresse (und den Namen, nur fuer die Anzeige) des zuletzt
 * verbundenen Fardriver-Dongles, damit die App beim naechsten Start automatisch
 * versuchen kann, sich direkt wieder zu verbinden - ohne erneuten manuellen Scan.
 */
class LastDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("fardriver_dash_prefs", Context.MODE_PRIVATE)

    fun save(address: String, name: String?) {
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun getSavedAddress(): String? = prefs.getString(KEY_ADDRESS, null)
    fun getSavedName(): String? = prefs.getString(KEY_NAME, null)

    fun clear() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }

    companion object {
        private const val KEY_ADDRESS = "last_device_address"
        private const val KEY_NAME = "last_device_name"
    }
}
