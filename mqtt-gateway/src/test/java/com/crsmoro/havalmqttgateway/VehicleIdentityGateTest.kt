package com.crsmoro.havalmqttgateway

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VehicleIdentityGateTest {
    @Test fun bufferedObservationsCannotBePublishedUntilVinIsValid() {
        val state = IndependentVehicleState()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        state.observe("car.basic.vehicle_speed", "42", "gwm", time, 1)
        val method = state.javaClass.methods.firstOrNull { it.name == "effectiveTopicPrefix" }
        assertNotNull("VIN gate must expose effectiveTopicPrefix", method)
        assertNull(method!!.invoke(state, "gwm"))
        state.observe("car.basic.vin_code", "invalid", "gwm", time, 2)
        assertNull(method.invoke(state, "gwm"))
        state.observe("car.basic.vin_code", "1HGCM82633A004352", "gwm", time, 3)
        assertEquals("gwm/haval/1HGCM82633A004352", method.invoke(state, "gwm"))
        assertEquals("42", state.snapshot()["car.basic.vehicle_speed"])
    }

    @Test fun disconnectClearsVinAndDerivedStateUntilFreshVinArrives() {
        val state = IndependentVehicleState()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        state.observe("car.basic.vin_code", "TESTAAA123456789A", "gwm", now, 1)
        state.observe("car.basic.vehicle_speed", "42", "gwm", now, 2)
        state.observeDerived("car.basic.engine_speed", "900", "gwm", now, 3)
        val method = state.javaClass.methods.firstOrNull { it.name == "onSourceStatus" }
        assertNotNull("source disconnect must invalidate identity and observations", method)
        method!!.invoke(state, VehicleSource.Status.SERVICE_UNAVAILABLE)
        assertTrue(state.snapshot().isEmpty())
        state.observe("car.basic.vehicle_speed", "43", "gwm", now, 4)
        assertNull(state.effectiveTopicPrefix("gwm"))
        assertNull(state.observeDerived("car.basic.engine_speed", "300", "gwm", now, 5))
        state.observe("car.basic.vin_code", "TESTAAA123456789B", "gwm", now, 6)
        assertNotNull(state.effectiveTopicPrefix("gwm"))
    }

    @Test fun invalidVinSignalDiscardsOldObservations() {
        val state = IndependentVehicleState()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        state.observe("car.basic.vin_code", "TESTAAA123456789A", "gwm", now, 1)
        state.observe("car.basic.vehicle_speed", "42", "gwm", now, 2)
        state.invalidateValue("car.basic.vin_code")
        assertTrue(state.snapshot().isEmpty())
        assertNull(state.effectiveTopicPrefix("gwm"))
    }

    @Test fun invalidEngineInputInvalidatesCachedDerivedValues() {
        val state = IndependentVehicleState()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        state.observeDerived("car.basic.engine_speed", "900", "gwm", now, 1)
        state.observeFlow("car.basic.engine_speed", "900", "gwm", now, 2)
        state.observeFlow("car.ev_info.charging_state", "0", "gwm", now, 3)
        state.observeFlow("car.ev_info.energy_drive_state", "14", "gwm", now, 4)
        assertTrue(state.snapshot().containsKey("haval.power.ice"))
        assertTrue(state.snapshot().containsKey("haval.power.flow"))
        val method = state.javaClass.methods.firstOrNull { it.name == "invalidateValue" }
        assertNotNull("invalid callback must invalidate its dependent state", method)
        method!!.invoke(state, "car.basic.engine_speed")
        assertFalse(state.snapshot().containsKey("haval.power.ice"))
        assertFalse(state.snapshot().containsKey("haval.power.flow"))
    }

    @Test fun invalidVinDoesNotLeaveThePreviousVehicleIdentityActive() {
        val state = IndependentVehicleState()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        state.observe("car.basic.vin_code", "1HGCM82633A004352", "gwm", now, 1)
        assertNotNull(state.effectiveTopicPrefix("gwm"))
        state.observe("car.basic.vin_code", "", "gwm", now, 2)
        assertNull(state.effectiveTopicPrefix("gwm"))
    }
}
