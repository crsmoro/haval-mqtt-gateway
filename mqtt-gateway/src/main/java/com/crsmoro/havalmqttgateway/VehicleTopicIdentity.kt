package com.crsmoro.havalmqttgateway

/** Pure helpers for the configured MQTT topic identity. */
object VehicleTopicIdentity {
    const val DEFAULT_PREFIX = "gwm"
    private const val LEGACY_PREFIX = "haval/telemetry"

    private val vinPattern = Regex("[A-HJ-NPR-Za-hj-npr-z0-9]{17}")

    fun isValidVin(vin: String?): Boolean =
        vin != null && vinPattern.matches(vin) && vin.any { !it.equals(vin[0], ignoreCase = true) }

    /** Returns null until the VIN, prefix and telemetry key are all safe to publish. */
    fun build(prefix: String, vin: String?, key: String): String? {
        if (!isValidVin(vin)) return null
        val normalizedPrefix = MqttTopicBuilder.normalizePrefix(prefix) ?: return null
        return MqttTopicBuilder.build("$normalizedPrefix/haval/$vin", key)
    }

    fun migratePrefix(prefix: String): String =
        if (prefix == LEGACY_PREFIX) DEFAULT_PREFIX else prefix
}
