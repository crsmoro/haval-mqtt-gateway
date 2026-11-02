package com.crsmoro.havalmqttgateway

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class DiscoveryOptOutGateTest {
    @Test fun optOutAndDispatchAreSerializedAcrossThreads() {
        val type = try { Class.forName("com.crsmoro.havalmqttgateway.DiscoveryDispatchGate") } catch (_: ClassNotFoundException) { null }
        assertNotNull("Discovery opt-out needs a shared dispatch gate", type)
        val gate = type!!.getField("INSTANCE").get(null)
        val dispatch = type.methods.single { it.name == "dispatch" }
        val change = type.methods.single { it.name == "change" }
        val enabled = AtomicBoolean(true)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        val sender = Thread {
            dispatch.invoke(gate, { enabled.get() }, { inside.countDown(); release.await(5, TimeUnit.SECONDS); dispatched.set(true); Unit })
        }
        val optOut = Thread { change.invoke(gate, { enabled.set(false); Unit }); changed.countDown() }
        try {
            sender.start(); assertTrue(inside.await(5, TimeUnit.SECONDS)); optOut.start()
            assertFalse("opt-out must wait for a dispatch already submitted", changed.await(200, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); sender.join(5000); optOut.join(5000) }
        assertTrue(dispatched.get()); assertEquals(0L, changed.count)
        val late = AtomicBoolean(false)
        assertEquals(false, dispatch.invoke(gate, { enabled.get() }, { late.set(true); Unit }))
        assertFalse("no dispatch may begin after opt-out completes", late.get())
    }
}
