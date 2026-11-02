package com.crsmoro.havalmqttgateway

import com.google.gson.Gson
import java.time.Instant

data class TelemetryEnvelope(
    val topicPrefix: String,
    val key: String,
    val rawValue: String,
    val observedAt: Instant,
    val sequence: Long,
    val apiVersion: Int,
    val source: String = "event_changed",
    val sourceTrust: String = SOURCE_TRUST,
    val cached: Boolean = false
) {
    fun toJson(): String = gson.toJson(
        linkedMapOf(
            "schema_version" to SCHEMA_VERSION,
            "vehicle_id" to topicPrefix,
            "observed_at" to observedAt.toString(),
            "sequence" to sequence,
            "app_api_version" to apiVersion,
            "key" to key,
            "raw_value" to rawValue,
            "source" to source,
            "source_trust" to sourceTrust,
            "cached" to cached
        )
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val SOURCE_TRUST = "unauthenticated_local_broadcast"
        private val gson = Gson()
    }
}
