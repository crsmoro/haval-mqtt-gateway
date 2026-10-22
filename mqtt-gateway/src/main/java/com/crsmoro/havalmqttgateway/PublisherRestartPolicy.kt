package com.crsmoro.havalmqttgateway

internal object PublisherRestartPolicy {
    fun shouldRestart(action: String?, publisherExists: Boolean): Boolean =
        publisherExists && action == GatewayService.ACTION_START

    fun shouldCreate(publisherExists: Boolean, reconfiguring: Boolean): Boolean =
        !publisherExists && !reconfiguring
}
