package com.crsmoro.havalmqttgateway

/** Inferred display-compatible power flow; absent or unknown inputs are not invented. */
internal class VehiclePowerFlowTelemetry {
    private val ice = VehicleIceTelemetry()
    private var iceValue: String? = null
    private var charging: Boolean? = null
    private var driveState: Int? = null
    private var lastSent: String? = null
    var available: Boolean = false
        private set

    fun observe(key: String, value: String): String? {
        when (key) {
            "car.basic.engine_speed" -> {
                iceValue = ice.observe(key, value)
                if (iceValue == null) ice.clear()
            }
            "car.ev_info.charging_state" -> charging = when (value.trim()) {
                "0" -> false
                "1" -> true
                else -> null
            }
            "car.ev_info.energy_drive_state" -> driveState = value.trim().toIntOrNull()
            else -> return null
        }
        val engine = iceValue
        val isCharging = charging
        val descriptor = if (isCharging == true) "charge|0|0" else driveState?.let(::describe)
        if (engine == null || isCharging == null || descriptor == null) {
            available = false
            lastSent = null
            return null
        }
        available = true
        val parts = descriptor.split('|')
        val result = "v1|${parts[0]}|$engine|${parts[1]}|${parts[2]}"
        if (result == lastSent) return null
        lastSent = result
        return result
    }

    fun clear() {
        ice.clear()
        iceValue = null
        charging = null
        driveState = null
        lastSent = null
        available = false
    }

    private fun describe(n: Int): String? = when (n) {
        0, 10, 11, 21 -> "idle|0|0"
        14, 22, 42 -> "ev|1|0"
        40 -> "ev|1|1"
        41 -> "ev|0|1"
        6, 13, 38 -> "regen|-1|0"
        39 -> "regen|0|-1"
        15, 16, 17, 20, 36 -> "hybrid|1|0"
        31, 35, 37 -> "hybrid|1|1"
        34 -> "hybrid|0|1"
        3 -> "ice|0|0"
        1, 32 -> "charge|1|0"
        30 -> "charge|1|1"
        33 -> "charge|0|1"
        12, 18, 23, 45, 46 -> "charge|0|0"
        else -> null
    }
}
