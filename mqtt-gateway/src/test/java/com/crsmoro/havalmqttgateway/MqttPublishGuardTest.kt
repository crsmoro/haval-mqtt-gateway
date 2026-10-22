package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttPublishGuardTest {
    @Test
    fun reportsSynchronousPublishExceptionInsteadOfPropagatingIt() {
        var reported: Throwable? = null

        MqttPublishGuard.execute(
            publish = { throw IllegalStateException("publish failed") },
            onFailure = { reported = it }
        )

        assertEquals("publish failed", reported?.message)
    }
}
