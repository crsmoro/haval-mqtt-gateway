package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class TelemetryEnvelopeTest {
    @Test
    fun serializesRawValueAndMetadataWithoutGuessingUnits() {
        val observedAt = Instant.parse("2026-10-22T12:34:56.789Z")
        val envelope = TelemetryEnvelope(
            vehicleId = "vehicle-123",
            key = "car.basic.vehicle_speed",
            rawValue = "42.0",
            observedAt = observedAt,
            sequence = 17L,
            apiVersion = 1,
            source = "event_changed"
        )

        val json = JsonParser.parseString(envelope.toJson()).asJsonObject
        assertEquals(1, json.get("schema_version").asInt)
        assertEquals("vehicle-123", json.get("vehicle_id").asString)
        assertEquals("car.basic.vehicle_speed", json.get("key").asString)
        assertEquals("42.0", json.get("raw_value").asString)
        assertEquals(observedAt.toString(), json.get("observed_at").asString)
        assertEquals(17L, json.get("sequence").asLong)
        assertEquals(1, json.get("app_api_version").asInt)
        assertEquals("event_changed", json.get("source").asString)
        assertEquals("unauthenticated_local_broadcast", json.get("source_trust").asString)
        assertFalse(json.has("unit"))
    }
}
