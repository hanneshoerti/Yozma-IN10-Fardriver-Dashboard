package com.fardriver.dash.ble

import android.content.Context

/**
 * Speichert die Kalibrierwerte fuer die Geschwindigkeitsberechnung (Motor-Polpaare und
 * Radumfang), damit Anpassungen im Einstellungsmenue auch nach einem Neustart erhalten
 * bleiben.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("fardriver_dash_prefs", Context.MODE_PRIVATE)

    fun getMotorPolePairs(default: Int): Int = prefs.getInt(KEY_POLE_PAIRS, default)
    fun getWheelCircumferenceM(default: Double): Double =
        prefs.getFloat(KEY_WHEEL_CIRC, default.toFloat()).toDouble()

    fun save(motorPolePairs: Int, wheelCircumferenceM: Double) {
        prefs.edit()
            .putInt(KEY_POLE_PAIRS, motorPolePairs)
            .putFloat(KEY_WHEEL_CIRC, wheelCircumferenceM.toFloat())
            .apply()
    }

    companion object {
        private const val KEY_POLE_PAIRS = "motor_pole_pairs"
        private const val KEY_WHEEL_CIRC = "wheel_circumference_m"
    }
}
