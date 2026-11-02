package com.crsmoro.havalmqttgateway

import org.junit.Assert.*
import org.junit.Test

class DiscoveryDeliveryTrackerTest {
    @Test fun retriesFailuresAndRepublishesAfterReconnect() {
        val type = try { Class.forName("com.crsmoro.havalmqttgateway.DiscoveryDeliveryTracker") } catch (_: ClassNotFoundException) { null }
        assertNotNull("Discovery delivery tracker is required", type)
        val tracker = type!!.getDeclaredConstructor().newInstance()
        val needs = type.getMethod("needs", String::class.java, String::class.java)
        val complete = type.getMethod("complete", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val reset = type.getMethod("reset")
        assertEquals(true, needs.invoke(tracker, "topic", "payload"))
        complete.invoke(tracker, "topic", "payload", false)
        assertEquals(true, needs.invoke(tracker, "topic", "payload"))
        complete.invoke(tracker, "topic", "payload", true)
        assertEquals(false, needs.invoke(tracker, "topic", "payload"))
        assertEquals(true, needs.invoke(tracker, "topic", "new payload"))
        reset.invoke(tracker)
        assertEquals(true, needs.invoke(tracker, "topic", "payload"))
    }
}
