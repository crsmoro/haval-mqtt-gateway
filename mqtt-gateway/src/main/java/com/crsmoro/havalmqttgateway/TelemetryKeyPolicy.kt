package com.crsmoro.havalmqttgateway

/** Read-only export policy for the public Impulse telemetry bus. */
object TelemetryKeyPolicy {
    const val MAX_VALUE_CHARS = 16_384
    private val safeKey = Regex("^[a-z0-9_.-]{1,160}$")
    private val commandSuffixes = listOf("_action", "_command", "_request", "_oneshot")
    private val commandOnlyKeys = setOf(
        "car.beantechs.sota_start",
        "car.configure.catapult_start",
        "car.ipk_setting.drive_info_reset"
    )
    private val commandOnlyPrefixes = listOf("car.command_parking_setting.")

    fun isPublishable(rawKey: String?): Boolean {
        val key = rawKey?.trim()?.lowercase() ?: return false
        if (rawKey != key) return false
        if (!safeKey.matches(key) || key.endsWith(".") || ".." in key) return false
        if (key == "car.basic.vin_code") return false
        if (key.startsWith("car.dms.") || key.startsWith("car.frs_setting.") || key.startsWith("car.oms.")) return false
        if (key in commandOnlyKeys || commandOnlyPrefixes.any(key::startsWith)) return false
        if (commandSuffixes.any(key::endsWith) || ".request_" in key) return false
        if (key.startsWith("car.")) return true
        return key.startsWith("haval.power.")
    }

    fun isPublishableValue(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= MAX_VALUE_CHARS
}
