package com.crsmoro.havalmqttgateway

import org.junit.Assert.*
import org.junit.Test

class HomeAssistantReplayPolicyTest {
    @Test fun replayRequiresEnabledDiscoveryConnectedSourceAndValidVin() {
        val type = try { Class.forName("com.crsmoro.havalmqttgateway.HomeAssistantReplayPolicy") } catch (_: ClassNotFoundException) { null }
        assertNotNull("Replay policy is required for discovery subscription race", type)
        val instance = type!!.getField("INSTANCE").get(null)
        val method = type.getMethod("shouldReplay", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
        val vin = "1HGCM82633A004352"
        fun check(a: Boolean, b: Boolean, c: Boolean, d: String?) = method.invoke(instance, a,b,c,d) as Boolean
        assertTrue(check(true,true,true,vin))
        assertFalse(check(false,true,true,vin))
        assertFalse(check(true,false,true,vin))
        assertFalse(check(true,true,false,vin))
        assertFalse(check(true,true,true,null))
        assertFalse(check(true,true,true,"bad"))
    }
}
