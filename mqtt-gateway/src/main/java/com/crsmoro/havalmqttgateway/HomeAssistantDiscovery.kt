package com.crsmoro.havalmqttgateway

import com.google.gson.Gson

/**
 * Constructs one Home Assistant MQTT discovery config without publishing or Android dependencies.
 *
 * [build] takes the complete data root *before* the VIN (default `gwm/haval`), not the
 * gateway's shorter configurable root; e.g. a gateway root `site` requires `site/haval`.
 * Prefixes are trimmed and stripped of surrounding slashes as in MqttTopicBuilder.
 * Invalid MQTT segments and known command-like keys return null. The command filter is
 * conservative but not exhaustive: callers must pass only independently verified read-only
 * observations. VINs are safe topic segments here, not validated as actual VINs.
 *
 * Unrecognized keys get generic sensors; known metadata requires explicit source evidence.
 * Key case is preserved in the data topic, while identifiers use reversible hex.
 * Message specifies retained QoS 1; an external publisher must actually apply those flags,
 * persist/replay discovery as needed, and retire obsolete retained configs explicitly.
 * VINs and telemetry are sensitive; restrict access to both discovery and data topics.
 */
object HomeAssistantDiscovery {
    data class Message(val topic: String, val payload: String) {
        val qos: Int get() = 1
        val retained: Boolean get() = true
    }

    fun build(vin: String, key: String, dataPrefix: String = "gwm/haval", discoveryPrefix: String = "homeassistant"): Message? {
        if (!validSegment(vin) || vin.length > 160 || !validKey(key)) return null
        val dataRoot = normalizePrefix(dataPrefix) ?: return null
        val discoveryRoot = normalizePrefix(discoveryPrefix) ?: return null
        val node = "vin_" + encode(vin)
        val objectId = "key_" + encode(key)
        val payload = linkedMapOf(
            "name" to key,
            "unique_id" to "${node}_${objectId}",
            "state_topic" to "$dataRoot/$vin/$key",
            "value_template" to "{{ value_json.raw_value }}",
            "device" to mapOf("identifiers" to listOf(node), "name" to "Haval $vin")
        )
        // Units follow the producer's explicit display/calculation contracts. No
        // state_class is assumed, including for the potentially resettable odometer.
        val metadata = when (key) {
            "car.basic.vehicle_speed" -> "speed" to "km/h"
            "car.basic.total_odometer" -> "distance" to "km"
            "car.basic.battery_voltage", "car.ev_info.power_battery_voltage" -> "voltage" to "V"
            "car.ev_info.cur_charge_current" -> "current" to "A"
            "car.basic.engine_speed" -> null to "rpm"
            else -> null
        }
        metadata?.let { (deviceClass, unit) ->
            if (deviceClass != null) payload["device_class"] = deviceClass
            payload["unit_of_measurement"] = unit
        }
        // Synthetic ICE is explicitly serialized as 1/0, not the OEM ignition enum.
        val component = if (key == "haval.power.ice") {
            payload["payload_on"] = "1"
            payload["payload_off"] = "0"
            "binary_sensor"
        } else "sensor"
        return Message("$discoveryRoot/$component/$node/$objectId/config", Gson().toJson(payload))
    }

    private val segmentPattern = Regex("[A-Za-z0-9_.-]+")
    private fun validSegment(value: String) =
        value != "." && value != ".." && segmentPattern.matches(value)

    private fun normalizePrefix(value: String): String? {
        val normalized = value.trim().trim('/')
        return normalized.takeIf { it.isNotEmpty() && it.split('/').all(::validSegment) }
    }

    private fun validKey(key: String): Boolean {
        if (key.length > 160 || !validSegment(key) || key.endsWith('.') || ".." in key) return false
        val normalized = key.lowercase(java.util.Locale.ROOT)
        return listOf("_action", "_command", "_request", "_oneshot").none(normalized::endsWith) &&
            ".request_" !in normalized &&
            normalized !in setOf("car.beantechs.sota_start", "car.configure.catapult_start", "car.ipk_setting.drive_info_reset") &&
            !normalized.startsWith("car.command_parking_setting.")
    }

    private fun encode(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
