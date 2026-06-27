package com.viel.oto.abs.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsSyncPlanTest {

    @Test
    fun `large library plan should require confirmation only above ten thousand`() {
        val small = AbsSyncPlan(totalItems = 3000, batchSize = 20, requiresConfirmation = false)
        val medium = AbsSyncPlan(totalItems = 10000, batchSize = 20, requiresConfirmation = false)
        val large = AbsSyncPlan(totalItems = 10001, batchSize = 20, requiresConfirmation = true)

        assertFalse(small.requiresConfirmation)
        assertFalse(medium.requiresConfirmation)
        assertTrue(large.requiresConfirmation)
        assertEquals(20, large.batchSize)
    }
}
