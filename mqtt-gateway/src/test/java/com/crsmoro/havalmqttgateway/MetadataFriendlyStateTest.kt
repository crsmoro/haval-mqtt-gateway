package com.crsmoro.havalmqttgateway

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataFriendlyStateTest {
    @Test fun powerFlowDisplaysOnlyDocumentedVersionedFlowNames() {
        val message = HomeAssistantDiscovery.build("VIN123", "haval.power.flow")!!
        val p = JsonParser.parseString(message.payload).asJsonObject
        assertTrue(message.topic.contains("/sensor/"))
        assertEquals("gwm/haval/VIN123/haval.power.flow", p.get("state_topic").asString)
        assertEquals("{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{% set p = (v | string).split('|') %}{{ {'idle': 'Parado', 'ev': 'Elétrico', 'regen': 'Regeneração', 'hybrid': 'Híbrido', 'ice': 'Combustão', 'charge': 'Carregamento'}.get(p[1], none) if p | length == 5 and p[0] == 'v1' else none }}", p.get("value_template").asString)
    }

    @Test fun cycleModeUsesDocumentedLabelsAndUnknownForOtherValues() {
        val message = HomeAssistantDiscovery.build("VIN123", "car.hvac.cycle_mode")!!
        val p = JsonParser.parseString(message.payload).asJsonObject
        assertTrue(message.topic.contains("/sensor/"))
        assertEquals("gwm/haval/VIN123/car.hvac.cycle_mode", p.get("state_topic").asString)
        assertEquals("{% set d = value | from_json({}) %}{% set v = d.get('raw_value') if d is mapping else none %}{{ {'0': 'Recirculação', '1': 'Ar externo'}.get(v | string, none) }}", p.get("value_template").asString)
    }
}
