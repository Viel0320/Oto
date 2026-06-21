package com.viel.aplayer.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.viel.aplayer.data.db.AudiobookSchema
import java.util.concurrent.TimeUnit

/**
 * Work Scheduling Policy (Centralizes WorkManager queue semantics for app background commands)
 *
 * Keeps unique-work names, replacement rules, constraints, and retry backoff in one module so manual
 * sync, root-edit sync, cold-start debounce, and remote ABS sync cannot drift independently.
 */
object WorkSchedulingPolicy {
    private const val LIBRARY_SYNC_WORK_NAME = "LibrarySyncWork"
    private const val ABS_SYNC_PREFIX = "abs-sync:"
    private const val DEFAULT_BACKOFF_DELAY_SECONDS = 10L
    private const val COLD_START_INITIAL_DELAY_SECONDS = 2L

    /**
     * Library Sync Work Policy (Differentiates cold-start debounce from user/root-edit refreshes)
     *
     * Cold-start scans keep the first queued job to avoid duplicate boot-time work, while user and
     * configuration-change scans replace stale queued inputs so the newest root settings win.
     */
    // Update WorkSchedulingPolicy to use type-safe AudiobookSchema.ScanTrigger: Replacing trigger String with ScanTrigger enum.
    fun librarySync(trigger: AudiobookSchema.ScanTrigger, requiresNetwork: Boolean): UniqueWorkSchedulingPolicy =
        UniqueWorkSchedulingPolicy(
            uniqueWorkName = LIBRARY_SYNC_WORK_NAME,
            existingWorkPolicy = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
                ExistingWorkPolicy.KEEP
            } else {
                ExistingWorkPolicy.REPLACE
            },
            constraints = if (requiresNetwork) connectedNetworkConstraints() else Constraints.NONE,
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelay = DEFAULT_BACKOFF_DELAY_SECONDS,
            backoffTimeUnit = TimeUnit.SECONDS,
            initialDelay = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
                COLD_START_INITIAL_DELAY_SECONDS
            } else {
                0L
            },
            initialDelayTimeUnit = TimeUnit.SECONDS
        )

    /**
     * ABS Root Sync Work Policy (Root-scoped remote synchronization queue)
     *
     * ABS catalog mirroring is always network-bound and root-scoped; replacing queued work keeps manual
     * refreshes or edited server coordinates from being dropped behind stale requests.
     */
    fun absRootSync(rootId: String): UniqueWorkSchedulingPolicy =
        UniqueWorkSchedulingPolicy(
            uniqueWorkName = "$ABS_SYNC_PREFIX$rootId",
            existingWorkPolicy = ExistingWorkPolicy.REPLACE,
            constraints = connectedNetworkConstraints(),
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelay = DEFAULT_BACKOFF_DELAY_SECONDS,
            backoffTimeUnit = TimeUnit.SECONDS,
            initialDelay = 0L,
            initialDelayTimeUnit = TimeUnit.SECONDS
        )

    private fun connectedNetworkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}

/**
 * Unique Work Scheduling Policy (Executable WorkManager queue configuration)
 *
 * The request builder still owns worker input data, while this value owns only the reusable scheduling
 * facts that should be unit-tested without constructing Android workers.
 */
data class UniqueWorkSchedulingPolicy(
    val uniqueWorkName: String,
    val existingWorkPolicy: ExistingWorkPolicy,
    val constraints: Constraints,
    val backoffPolicy: BackoffPolicy,
    val backoffDelay: Long,
    val backoffTimeUnit: TimeUnit,
    val initialDelay: Long,
    val initialDelayTimeUnit: TimeUnit
)
