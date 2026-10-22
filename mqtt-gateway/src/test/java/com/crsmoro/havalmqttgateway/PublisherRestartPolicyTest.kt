package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherRestartPolicyTest {
    @Test
    fun explicitSaveAndStartReplacesAnExistingPublisher() {
        assertTrue(PublisherRestartPolicy.shouldRestart(GatewayService.ACTION_START, publisherExists = true))
    }

    @Test
    fun ordinaryRetriesDoNotReplaceAnExistingPublisher() {
        assertFalse(PublisherRestartPolicy.shouldRestart(null, publisherExists = true))
        assertFalse(PublisherRestartPolicy.shouldRestart(GatewayService.ACTION_START, publisherExists = false))
    }

    @Test
    fun doesNotCreateAnotherClientWhileOldClientIsClosing() {
        assertFalse(PublisherRestartPolicy.shouldCreate(publisherExists = false, reconfiguring = true))
        assertTrue(PublisherRestartPolicy.shouldCreate(publisherExists = false, reconfiguring = false))
    }
}
