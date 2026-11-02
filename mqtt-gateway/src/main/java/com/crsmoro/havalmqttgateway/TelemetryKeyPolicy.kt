package com.crsmoro.havalmqttgateway

/** Validates read-only vehicle telemetry topic keys; acquisition policy is separate. */
object TelemetryKeyPolicy {
    const val MAX_VALUE_CHARS = 16_384
    private val safeKey = Regex("^[A-Za-z0-9_.-]{1,160}$")
    private val commandSuffixes = listOf("_action", "_command", "_request", "_oneshot")
    private val commandOnlyKeys = setOf(
        "car.beantechs.sota_start",
        "car.configure.catapult_start",
        "car.ipk_setting.drive_info_reset"
    )
    private val commandOnlyPrefixes = listOf("car.command_parking_setting.")

    fun isPublishable(rawKey: String?): Boolean {
        val key = rawKey ?: return false
        if (key.trim() != key) return false
        if (!safeKey.matches(key) || key.endsWith(".") || ".." in key) return false
        val normalized = key.lowercase()
        if (normalized in commandOnlyKeys || commandOnlyPrefixes.any(normalized::startsWith)) return false
        if (commandSuffixes.any(normalized::endsWith) || ".request_" in normalized) return false
        if (key.startsWith("car.")) return true
        return key.startsWith("haval.power.")
    }

    fun isPublishableValue(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= MAX_VALUE_CHARS
}
