package com.crsmoro.havalmqttgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTelemetryBudgetTest {
    @Test
    fun coalescesPerKeyAndEvictsOldestWhenByteBudgetIsExceeded() {
        val budget = PendingTelemetryBudget(maxKeys = 4, maxBytes = 1_300)

        assertTrue(budget.put("a", "x".repeat(100)).isEmpty())
        assertTrue(budget.put("a", "y".repeat(100)).isEmpty())
        val evicted = budget.put("b", "z".repeat(100))

        assertEquals(listOf("a"), evicted)
        assertEquals(1, budget.size)
        assertTrue(budget.currentBytes <= 1_300)
    }

    @Test
    fun accountsForWorstCaseJsonEscapingOfControlCharacters() {
        val budget = PendingTelemetryBudget(maxKeys = 10, maxBytes = 700)
        val controlValue = String(CharArray(100) { 0.toChar() })

        val evicted = budget.put("a", controlValue)

        assertEquals(listOf("a"), evicted)
        assertEquals(0, budget.size)
    }

    @Test
    fun enforcesKeyCountAndCanRemoveOrClearAccounting() {
        val budget = PendingTelemetryBudget(maxKeys = 2, maxBytes = 10_000)
        budget.put("a", "1")
        budget.put("b", "2")
        assertEquals(listOf("a"), budget.put("c", "3"))

        assertTrue(budget.remove("b"))
        budget.clear()
        assertEquals(0, budget.size)
        assertEquals(0L, budget.currentBytes)
    }
}
