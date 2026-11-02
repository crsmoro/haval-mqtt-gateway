package com.crsmoro.havalmqttgateway

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VehicleGatewayIntegrationTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")

    @Test fun cachesOnlyCuratedVehicleValuesForMqttReconnect() {
        val state = IndependentVehicleState()
        assertNotNull(state.observe("car.basic.vin_code", "VIN-value", "haval", time, 1))
        assertNotNull(state.observe("car.basic.vin_code", "VIN-new", "haval", time, 2))
        assertNull(state.observe("car.navigation.location", "not vehicle telemetry", "haval", time, 3))
        assertEquals(mapOf("car.basic.vin_code" to "VIN-new"), state.snapshot())
    }

    @Test fun replayPreservesObservationTimeAndUsesIndependentSourceMetadata() {
        val state = IndependentVehicleState()
        val envelope = state.observe("car.basic.vin_code", "VIN", "haval", time, 1)!!
        assertEquals("vehicle_oem_binder", envelope.source)
        assertEquals("shizuku_oem_binder", envelope.sourceTrust)
        assertEquals(0, envelope.apiVersion)
        val copy = state.replay("new-prefix", 2).single()
        assertEquals(time, copy.observedAt)
        assertEquals("new-prefix", copy.topicPrefix)
        assertEquals("VIN", copy.rawValue)
        assertEquals(2L, copy.sequence)
        assertTrue(com.google.gson.JsonParser.parseString(copy.toJson()).asJsonObject.get("cached").asBoolean)
        assertFalse(com.google.gson.JsonParser.parseString(envelope.toJson()).asJsonObject.get("cached").asBoolean)
    }

    @Test fun selectsAllDefaultAndSensitiveVehiclePropertiesExceptAmbiguousDeletionOperations() {
        // "rst" could mean result or reset; do not issue reads/subscriptions until verified.
        val excluded = setOf("car.dms.frs.dele_dms_facedb_rst", "car.dms.frs.dele_oms_facedb_rst")
        val expected = VehicleKeyCatalog.entries.filter {
            it.source == VehicleKeyCatalog.Source.OEM_PROPERTY && (it.defaultMonitored || it.sensitive)
        }.map { it.key }.toSet() - excluded
        val actual = IndependentVehicleState.keys.toSet()
        assertTrue("Missing: ${expected - actual}", actual.containsAll(expected))
        assertTrue("Ambiguous deletion properties selected: ${actual.intersect(excluded)}", actual.intersect(excluded).isEmpty())
        assertTrue("case-sensitive OEM identifier", "car.basic.left_turn_Indicator_light_status" in actual)
    }

    @Test fun curatedKeysAreUniquePermittedOemVehicleProperties() {
        val keys = IndependentVehicleState.keys
        assertTrue(keys.isNotEmpty())
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue(key, VehicleSource.permitsKey(key))
            assertEquals(key, VehicleKeyCatalog.Source.OEM_PROPERTY, VehicleKeyCatalog.find(key)?.source)
            assertTrue(key, TelemetryKeyPolicy.isPublishable(key))
            assertEquals(key, "haval/$key", MqttTopicBuilder.build("haval", key))
        }
    }

    @Test fun derivesIceFromEngineSpeedWithoutPretendingItIsMeasured() {
        val state = IndependentVehicleState()
        val result = state.observeDerived("car.basic.engine_speed", "400", "haval", time, 3)!!
        assertEquals("haval.power.ice", result.key)
        assertEquals("1", result.rawValue)
        assertEquals("vehicle_derived", result.source)
        assertEquals("derived_from_shizuku_oem_binder", result.sourceTrust)
        assertEquals("1", state.replay("haval", 4).single().rawValue)
        state.clear()
        assertNull(state.observeDerived("car.basic.engine_speed", "300", "haval", time, 5))
    }

    @Test fun derivesPowerFlowAndCachesItForReconnect() {
        val state = IndependentVehicleState()
        assertNull(state.observeFlow("car.ev_info.energy_drive_state", "14", "haval", time, 1))
        assertNull(state.observeFlow("car.ev_info.charging_state", "0", "haval", time, 2))
        val result = state.observeFlow("car.basic.engine_speed", "0", "haval", time, 3)!!
        assertEquals("haval.power.flow", result.key)
        assertEquals("v1|ev|0|1|0", result.rawValue)
        assertEquals("vehicle_derived", result.source)
        assertEquals(result.rawValue, state.replay("haval", 4).single().rawValue)
    }

    @Test fun invalidatedDerivedKeysCanBeRemovedFromPendingQueue() {
        val state = IndependentVehicleState()
        assertEquals(setOf("haval.power.ice", "haval.power.flow"),
            state.invalidDerivedKeys(setOf("car.basic.vehicle_speed", "haval.power.ice", "haval.power.flow")))
    }

    @Test fun unknownFlowStateIsNotReplayed() {
        val state = IndependentVehicleState()
        state.observeFlow("car.ev_info.energy_drive_state", "14", "haval", time, 1)
        state.observeFlow("car.ev_info.charging_state", "0", "haval", time, 2)
        assertNotNull(state.observeFlow("car.basic.engine_speed", "0", "haval", time, 3))
        assertNull(state.observeFlow("car.ev_info.energy_drive_state", "73", "haval", time, 4))
        assertTrue(state.replay("haval", 5).isEmpty())
    }

    @Test fun sourceDisconnectionInvalidatesDerivedInputsAndCachedFlows() {
        val state = IndependentVehicleState()
        state.observeFlow("car.ev_info.energy_drive_state", "14", "haval", time, 1)
        state.observeFlow("car.ev_info.charging_state", "0", "haval", time, 2)
        assertNotNull(state.observeFlow("car.basic.engine_speed", "0", "haval", time, 3))
        state.invalidateDerived()
        assertTrue(state.replay("haval", 4).isEmpty())
        assertNull(state.observeFlow("car.ev_info.charging_state", "1", "haval", time, 5))
    }

    @Test fun blankAndOversizedValuesAreNotCachedAndClearDiscardsSnapshots() {
        val state = IndependentVehicleState()
        assertNull(state.observe("car.basic.vin_code", "  ", "haval", time, 1))
        assertNull(state.observe("car.basic.vin_code", "x".repeat(16385), "haval", time, 2))
        assertTrue(state.replay("haval", 3).isEmpty())
        state.observe("car.basic.vin_code", "VIN", "haval", time, 4)
        state.clear()
        assertTrue(state.snapshot().isEmpty())
        assertTrue(state.replay("haval", 5).isEmpty())
    }

    @Test fun sourceSnapshotFlowsIntoReconnectReplayAndClosesSubscription() {
        val state = IndependentVehicleState()
        var subscribed = emptyList<String>()
        var closed = false
        val statuses = mutableListOf<VehicleSource.Status>()
        var sequence = 0L
        val connection = object : VehicleSource.Connection {
            override fun subscribe(keys: List<String>) { subscribed = keys }
            override fun read(key: String): String? = when (key) {
                "car.basic.vin_code" -> "VIN"
                "car.basic.vehicle_speed" -> "42"
                else -> null
            }
            override fun close() { closed = true }
        }
        val source = VehicleSource(
            IndependentVehicleState.keys,
            VehicleSource.Transport { connection },
            VehicleSource.Scheduler { _, _ -> VehicleSource.Cancel {} },
            object : VehicleSource.Observer {
                override fun onStatus(status: VehicleSource.Status) { statuses.add(status) }
                override fun onValue(key: String, value: String) {
                    state.observe(key, value, "haval", time, ++sequence)
                }
            }
        )
        source.start()
        assertEquals(IndependentVehicleState.keys, subscribed)
        assertTrue(statuses.contains(VehicleSource.Status.CONNECTED))
        val replayed = state.replay("haval", 100).associateBy { it.key }
        assertEquals("VIN", replayed.getValue("car.basic.vin_code").rawValue)
        assertEquals("42", replayed.getValue("car.basic.vehicle_speed").rawValue)
        source.close()
        assertTrue(closed)
        assertEquals(VehicleSource.Status.CLOSED, statuses.last())
    }
}
