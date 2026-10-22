package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherGenerationTest {
    @Test
    fun replacementInvalidatesCallbacksFromPreviousPublisher() {
        val gate = PublisherGeneration()
        val oldPublisher = gate.next()
        val newPublisher = gate.next()

        assertFalse(gate.isCurrent(oldPublisher))
        assertTrue(gate.isCurrent(newPublisher))
    }

    @Test
    fun stopInvalidatesTheCurrentPublisher() {
        val gate = PublisherGeneration()
        val publisher = gate.next()

        gate.invalidate()

        assertFalse(gate.isCurrent(publisher))
    }
}
