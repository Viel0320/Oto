package com.viel.oto.work

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.viel.oto.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkSchedulingPolicyTest {
    @Test
    fun `cold start library sync keeps debounce queue while fallback user sync remains replaceable`() {
        val coldStart = WorkSchedulingPolicy.librarySync(
            trigger = AudiobookSchema.ScanTrigger.COLD_START,
            requiresNetwork = false
        )
        val user = WorkSchedulingPolicy.librarySync(
            trigger = AudiobookSchema.ScanTrigger.USER,
            requiresNetwork = false
        )

        assertEquals("LibrarySyncWork", coldStart.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.KEEP, coldStart.existingWorkPolicy)
        assertEquals(ExistingWorkPolicy.REPLACE, user.existingWorkPolicy)
        assertEquals(2L, coldStart.initialDelay)
        assertEquals(TimeUnit.SECONDS, coldStart.initialDelayTimeUnit)
        assertEquals(0L, user.initialDelay)
    }

    @Test
    fun `network-bound library sync waits for connectivity and uses retry backoff`() {
        val policy = WorkSchedulingPolicy.librarySync(
            trigger = AudiobookSchema.ScanTrigger.USER,
            requiresNetwork = true
        )

        assertEquals(NetworkType.CONNECTED, policy.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, policy.backoffPolicy)
        assertEquals(10L, policy.backoffDelay)
        assertEquals(TimeUnit.SECONDS, policy.backoffTimeUnit)
    }

    @Test
    fun `abs sync is root scoped replaceable and always network constrained`() {
        val policy = WorkSchedulingPolicy.absRootSync("root-1")

        assertEquals("abs-sync:root-1", policy.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.existingWorkPolicy)
        assertEquals(NetworkType.CONNECTED, policy.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, policy.backoffPolicy)
    }
}
