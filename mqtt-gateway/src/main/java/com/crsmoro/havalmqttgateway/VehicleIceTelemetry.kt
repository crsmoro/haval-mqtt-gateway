package com.crsmoro.havalmqttgateway

/** Inferred combustion activity from engine speed; not a direct engine measurement. */
internal class VehicleIceTelemetry {
    private var running: Boolean? = null

    /** Returns null until the input is valid and determines a state. */
    fun observe(key: String, rawValue: String): String? {
        if (key != "car.basic.engine_speed") return null
        val rpm = rawValue.toDoubleOrNull()
        if (rpm == null || !rpm.isFinite() || rpm < 0 || rpm > 8000 ||
            kotlin.math.round(rpm) in setOf(65535.0, 1023.0, 2047.0)) {
            running = null
            return null
        }
        running = when {
            rpm >= 400.0 -> true
            rpm < 200.0 -> false
            else -> running
        }
        return running?.let { if (it) "1" else "0" }
    }

    fun clear() { running = null }
}
