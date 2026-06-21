package com.viel.aplayer.work

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkSchedulingPolicyTest {
    @Test
    fun `cold start library sync keeps debounce queue while user sync replaces stale work`() {
        val coldStart = WorkSchedulingPolicy.librarySync(
            trigger = AudiobookSchema.ScanTrigger.COLD_START,
            requiresNetwork = false
        )
        val user = WorkSchedulingPolicy.librarySync(
            trigger = AudiobookSchema.ScanTrigger.USER,
            requiresNetwork = false
        )

        // Library Queue Replacement Policy (Separates boot debounce from user intent)
        // Cold-start work keeps the first queued scan, while user-triggered work replaces stale inputs.
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

        // Remote Library Constraint Policy (Pins WebDAV-root edit rescans to connected execution)
        // The scheduler should not launch network-required root scans until WorkManager observes connectivity.
        assertEquals(NetworkType.CONNECTED, policy.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, policy.backoffPolicy)
        assertEquals(10L, policy.backoffDelay)
        assertEquals(TimeUnit.SECONDS, policy.backoffTimeUnit)
    }

    @Test
    fun `abs sync is root scoped replaceable and always network constrained`() {
        val policy = WorkSchedulingPolicy.absRootSync("root-1")

        // ABS Work Queue Policy (Protects manual remote refreshes from stale queued catalog jobs)
        // ABS synchronization is always remote, so it must be root-scoped, replaceable, connected, and retryable.
        assertEquals("abs-sync:root-1", policy.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.existingWorkPolicy)
        assertEquals(NetworkType.CONNECTED, policy.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, policy.backoffPolicy)
    }
}
