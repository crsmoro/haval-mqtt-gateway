package com.crsmoro.havalmqttgateway

/** Tags callbacks so an old MQTT client cannot mutate a replacement service session. */
internal class PublisherGeneration {
    private var value = 0

    @Synchronized
    fun next(): Int = ++value

    @Synchronized
    fun invalidate() {
        value++
    }

    @Synchronized
    fun isCurrent(generation: Int): Boolean = generation == value
}
