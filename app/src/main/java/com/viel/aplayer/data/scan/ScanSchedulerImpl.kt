package com.viel.aplayer.data.scan

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.data.cover.CoverRecoveryGateway
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.orchestrator.ScanSessionRunner
import com.viel.aplayer.library.scan.ScanCommand
import com.viel.aplayer.library.scan.ScanOutcome
import com.viel.aplayer.library.scan.ScanOutcomeKind
import com.viel.aplayer.library.scan.ScanOutcomePolicy
import com.viel.aplayer.library.scan.ScanSession
import com.viel.aplayer.library.sync.LibrarySyncWorker
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.work.WorkSchedulingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implements ScanScheduler.
 *
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Integrates with LibraryRootStore and ScanSessionRunner in the M6c phase, breaking all ties to the bloated BookLibraryRepository.
 * 2. Re-anchor Serial Scans Lock: Manages a private priority queue internally so user root scans can supersede queued or running background work.
 */
class ScanSchedulerImpl(
    context: Context,
    private val coverRecoveryGateway: CoverRecoveryGateway,
    private val vfsFileInterface: VfsFileInterface,
    private val directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache,
    private val appEventSink: AppEventSink
) : ScanScheduler, java.io.Closeable {

    private val appContext = context.applicationContext

    private val rootStore = LibraryRootStore(appContext)

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("scanService coroutine failure", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private val queueMutex = Mutex()
    private val pendingScans = mutableListOf<QueuedScan>()

    private var activeScan: QueuedScan? = null
    private var activeScanJob: Job? = null
    private var nextSequence = 0L

    override suspend fun syncLibrary(trigger: String, rootIds: Set<String>): ScanOutcome {
        val task = enqueueScan(command = buildScanCommand(trigger, rootIds))
        return awaitScanTask(task)
    }

    override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean, rootIds: Set<String>) {
        val scanTrigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)
        if (scanTrigger != AudiobookSchema.ScanTrigger.COLD_START) {
            scope.launch {
                syncLibrary(trigger = trigger, rootIds = rootIds)
            }
            return
        }
        val policy = WorkSchedulingPolicy.librarySync(trigger = scanTrigger, requiresNetwork = requiresNetwork)
        val workManager = WorkManager.getInstance(appContext)
        val requestBuilder = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString("trigger", trigger)
                    .putStringArray("rootIds", rootIds.toTypedArray())
                    .build()
            )
            .setConstraints(policy.constraints)
            .setBackoffCriteria(policy.backoffPolicy, policy.backoffDelay, policy.backoffTimeUnit)
        if (policy.initialDelay > 0L) {
            requestBuilder.setInitialDelay(policy.initialDelay, policy.initialDelayTimeUnit)
        }
        val request = requestBuilder
            .build()
        workManager.enqueueUniqueWork(
            policy.uniqueWorkName,
            policy.existingWorkPolicy,
            request
        )
    }

    /**
     * Metadata synchronization and recovery for one dequeued command.
     *
     * The caller already decided priority and root scope, so this function only builds the scan
     * adapters and lets ScanSession enforce trigger-to-rescan semantics.
     */
    private suspend fun runSyncLibrary(command: ScanCommand): ScanOutcome = withContext(Dispatchers.IO) {
        createScanSession().execute(command)
    }

    @OptIn(UnstableApi::class)
    private fun createScanSession(): ScanSession =
        ScanSession(
            rootStatusAdapter = {
                rootStore.refreshPermissionStatuses()
            },
            importAdapter = { type, targetRootIds, allowedRootIds ->
                ScanSessionRunner(
                    context = appContext,
                    vfsFileInterface = vfsFileInterface,
                    directoryListingCache = directoryListingCache,
                    triggerCoverRegeneration = coverRecoveryGateway::triggerRecovery
                ).rescan(type, targetRootIds = targetRootIds, allowedRootIds = allowedRootIds)
            },
            librarySnapshotAdapter = {
                AppDatabase.getInstance(appContext).bookDao().getAllBooksOnce().isEmpty()
            }
        )

    /**
     * Adds one command to the internal scan lane.
     *
     * User commands enter a priority lane and request preemption of the currently running older
     * command, while cold-start commands remain low-priority and keep their WorkManager delay policy.
     */
    private suspend fun enqueueScan(command: ScanCommand): QueuedScan =
        queueMutex.withLock {
            val task = QueuedScan(
                command = command,
                sequence = nextSequence++
            )
            pendingScans += task
            if (task.isUserPriority) {
                preemptActiveScanLocked(incomingTask = task)
            }
            launchNextScanLocked()
            task
        }

    /**
     * Waits for a queued command result on behalf of the public sync caller.
     *
     * If the caller's coroutine is cancelled while waiting, the matching queued or running scan is
     * cancelled rather than being left as detached background work.
     */
    private suspend fun awaitScanTask(task: QueuedScan): ScanOutcome =
        try {
            task.result.await()
        } catch (error: CancellationException) {
            cancelQueuedScan(task, error)
            throw error
        }

    /**
     * Cancels a caller-owned queued command.
     *
     * Pending commands are removed without starting; the active command is interrupted only when the
     * same caller-owned task is currently running, which keeps newer user preemption separate from
     * external coroutine cancellation.
     */
    private suspend fun cancelQueuedScan(task: QueuedScan, cause: CancellationException) {
        queueMutex.withLock {
            task.cancelRequested = true
            if (pendingScans.remove(task)) {
                task.result.cancel(cause)
                return
            }
            if (activeScan === task) {
                activeScanJob?.cancel(cause)
            }
        }
    }

    /**
     * Requests interruption of an older active command when a newer user command arrives.
     *
     * The interrupted command is not completed or dropped; runQueuedScan observes the preemption
     * flag and places it back into the priority queue behind the newer user work.
     */
    private fun preemptActiveScanLocked(incomingTask: QueuedScan) {
        val runningTask = activeScan ?: return
        if (runningTask.isUserPriority && runningTask.sequence >= incomingTask.sequence) return
        runningTask.preemptRequested = true
        activeScanJob?.cancel(CancellationException("Scan preempted by newer user command"))
    }

    /**
     * Starts the next queued command if the lane is idle.
     *
     * Selection is LIFO within the user-priority lane and lower priority for cold-start work, giving
     * repeated user actions the newest-first behavior requested without changing cold-start delays.
     */
    private fun launchNextScanLocked() {
        if (activeScanJob != null || !scope.isActive) return
        val nextTask = selectNextScanLocked() ?: return
        pendingScans.remove(nextTask)
        activeScan = nextTask
        activeScanJob = scope.launch {
            runQueuedScan(nextTask)
        }
    }

    /**
     * Selects the next command by priority and sequence.
     *
     * Higher priority ranks win first; within the same rank the newest sequence wins so repeated
     * user-triggered rescans keep inserting ahead of older waiting work.
     */
    private fun selectNextScanLocked(): QueuedScan? =
        pendingScans.maxWithOrNull(
            compareBy<QueuedScan> { task -> task.priorityRank }
                .thenBy { task -> task.sequence }
        )

    /**
     * Runs one selected command and resolves or requeues its deferred result.
     *
     * Preempted scans are returned to the queue without notifying their original caller, while real
     * cancellation and failures still complete the caller-facing deferred in the expected direction.
     */
    private suspend fun runQueuedScan(task: QueuedScan) {
        var shouldRequeue = false
        try {
            val outcome = runSyncLibrary(task.command)
            logScanOutcome(task.command.trigger.name, outcome)
            emitScanOutcomeFeedback(outcome)
            if (!task.result.isCompleted) {
                task.result.complete(outcome)
            }
        } catch (error: CancellationException) {
            shouldRequeue = task.preemptRequested &&
                !task.cancelRequested &&
                !task.result.isCancelled &&
                scope.isActive
            if (!shouldRequeue && !task.result.isCompleted) {
                task.result.cancel(error)
            }
        } catch (error: Throwable) {
            val outcome = ScanOutcomePolicy.fromFailure(error)
            logScanOutcome(task.command.trigger.name, outcome)
            emitScanOutcomeFeedback(outcome)
            if (!task.result.isCompleted) {
                task.result.complete(outcome)
            }
        } finally {
            queueMutex.withLock {
                if (activeScan === task) {
                    activeScan = null
                }
                activeScanJob = null
                if (shouldRequeue) {
                    task.preemptRequested = false
                    pendingScans += task
                }
                launchNextScanLocked()
            }
        }
    }

    /**
     * Builds the scan command from boundary strings.
     *
     * The scheduler accepts legacy trigger strings at its public boundary, but normalizes root ids
     * before the command reaches ScanSession so blank ids cannot accidentally widen the scan scope.
     */
    private fun buildScanCommand(trigger: String, rootIds: Set<String>): ScanCommand =
        ScanCommand(
            trigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
                .getOrDefault(AudiobookSchema.ScanTrigger.USER),
            targetRootIds = rootIds
                .map { rootId -> rootId.trim() }
                .filter { rootId -> rootId.isNotEmpty() }
                .toSet()
        )

    private fun logScanOutcome(trigger: String, outcome: ScanOutcome) {
        when (outcome.kind) {
            ScanOutcomeKind.SUCCESS,
            ScanOutcomeKind.PARTIAL -> {
                val session = outcome.session
                ScanWorkflowLogger.info(
                    "scanService ${outcome.kind}: trigger=$trigger, discovered=${session?.discoveredBookCount ?: 0}, " +
                        "updated=${session?.updatedBookCount ?: 0}, partial=${session?.partialBookCount ?: 0}, unavailable=${session?.unavailableBookCount ?: 0}"
                )
            }
            ScanOutcomeKind.BLOCKED ->
                ScanWorkflowLogger.warn("scanService blocked: trigger=$trigger, feedback=${outcome.feedback?.outcome?.identity?.topic}")
            ScanOutcomeKind.RETRY ->
                ScanWorkflowLogger.warn("scanService retry: trigger=$trigger, feedback=${outcome.feedback?.outcome?.identity?.topic}", outcome.cause)
            ScanOutcomeKind.FAILED ->
                ScanWorkflowLogger.error("scanService failed: trigger=$trigger, feedback=${outcome.feedback?.outcome?.identity?.topic}", outcome.cause)
        }
    }

    private fun emitScanOutcomeFeedback(outcome: ScanOutcome) {
        outcome.feedback?.let { feedback -> appEventSink.emitFeedback(feedback) }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Private scheduler queue item.
 *
 * Carries both the scan command and the mutable preemption flags needed to requeue an interrupted
 * older command without completing the caller's deferred result prematurely.
 */
private class QueuedScan(
    val command: ScanCommand,
    val sequence: Long,
    val result: CompletableDeferred<ScanOutcome> = CompletableDeferred()
) {
    val priorityRank: Int
        get() = if (isUserPriority) 1 else 0

    val isUserPriority: Boolean
        get() = command.trigger != AudiobookSchema.ScanTrigger.COLD_START

    var preemptRequested: Boolean = false
    var cancelRequested: Boolean = false
}
