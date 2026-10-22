package com.crsmoro.havalmqttgateway

internal class PublishRetryTracker(private val maxAttempts: Int) {
    private var sequence: Long? = null
    private var failures = 0

    init {
        require(maxAttempts > 0)
    }

    @Synchronized
    fun recordFailure(messageSequence: Long): Boolean {
        if (sequence != messageSequence) {
            sequence = messageSequence
            failures = 0
        }
        failures++
        if (failures < maxAttempts) return false
        resetState()
        return true
    }

    @Synchronized
    fun recordSuccess(messageSequence: Long) {
        if (sequence == messageSequence) resetState()
    }

    private fun resetState() {
        sequence = null
        failures = 0
    }
}
