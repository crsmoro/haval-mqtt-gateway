package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehiclePowerFlowTelemetryTest {
    @Test fun infersEvDriveOnlyAfterRequiredInputsArrive() {
        val flow = VehiclePowerFlowTelemetry()
        assertNull(flow.observe("car.ev_info.energy_drive_state", "14"))
        assertNull(flow.observe("car.ev_info.charging_state", "0"))
        assertEquals("v1|ev|0|1|0", flow.observe("car.basic.engine_speed", "0"))
        assertNull(flow.observe("car.basic.engine_speed", "0"))
    }

    @Test fun chargingOverridesAndEngineOnIsNotAssumed() {
        val flow = VehiclePowerFlowTelemetry()
        assertNull(flow.observe("car.ev_info.charging_state", "1"))
        assertEquals("v1|charge|0|0|0", flow.observe("car.basic.engine_speed", "0"))
    }

    @Test fun invalidRpmDoesNotProduceNewFlowUntilItRecovers() {
        val flow = VehiclePowerFlowTelemetry()
        flow.observe("car.ev_info.charging_state", "0")
        flow.observe("car.ev_info.energy_drive_state", "14")
        assertEquals("v1|ev|0|1|0", flow.observe("car.basic.engine_speed", "0"))
        assertNull(flow.observe("car.basic.engine_speed", "-1"))
        assertNull(flow.observe("car.ev_info.charging_state", "1"))
        assertEquals("v1|charge|1|0|0", flow.observe("car.basic.engine_speed", "500"))
    }

    @Test fun knownStateIsReemittedAfterUnknownState() {
        val flow = VehiclePowerFlowTelemetry()
        flow.observe("car.ev_info.charging_state", "0")
        flow.observe("car.basic.engine_speed", "0")
        assertEquals("v1|ev|0|1|0", flow.observe("car.ev_info.energy_drive_state", "14"))
        assertNull(flow.observe("car.ev_info.energy_drive_state", "73"))
        assertEquals("v1|ev|0|1|0", flow.observe("car.ev_info.energy_drive_state", "14"))
    }

    @Test fun unknownStateDoesNotFabricateAnIdleState() {
        val flow = VehiclePowerFlowTelemetry()
        assertNull(flow.observe("car.basic.engine_speed", "0"))
        assertNull(flow.observe("car.ev_info.energy_drive_state", "73"))
    }
}
