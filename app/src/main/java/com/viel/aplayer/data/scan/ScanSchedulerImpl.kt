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
import com.viel.aplayer.library.scan.ScanSession
import com.viel.aplayer.library.sync.LibrarySyncWorker
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.work.WorkSchedulingPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implements ScanScheduler.
 *
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Integrates with LibraryRootStore and ScanSessionRunner in the M6c phase, breaking all ties to the bloated BookLibraryRepository.
 * 2. Re-anchor Serial Scans Lock: Manages a private Mutex internally to coordinate COLD_START_LIGHT and USER_GLOBAL scan modes safely.
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

    private val scanMutex = Mutex()

    override suspend fun syncLibrary(trigger: String): ScanOutcome = scanMutex.withLock {
        val outcome = runSyncLibrary(trigger)
        logScanOutcome(trigger, outcome)
        emitScanOutcomeFeedback(outcome)
        outcome
    }

    override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean) {
        val scanTrigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)
        val policy = WorkSchedulingPolicy.librarySync(trigger = scanTrigger, requiresNetwork = requiresNetwork)
        val workManager = WorkManager.getInstance(appContext)
        val requestBuilder = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString("trigger", trigger)
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
     * Metadata synchronization and recovery.
     * Dispatches reachability checks, scans folders, and launches cover self-healing procedures.
     */
    private suspend fun runSyncLibrary(trigger: String): ScanOutcome = withContext(Dispatchers.IO) {
        val scanTrigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)
        createScanSession().execute(ScanCommand(scanTrigger))
    }

    @OptIn(UnstableApi::class)
    private fun createScanSession(): ScanSession =
        ScanSession(
            rootStatusAdapter = {
                rootStore.refreshPermissionStatuses()
            },
            importAdapter = { type, allowedRootIds ->
                ScanSessionRunner(
                    context = appContext,
                    vfsFileInterface = vfsFileInterface,
                    directoryListingCache = directoryListingCache,
                    triggerCoverRegeneration = coverRecoveryGateway::triggerRecovery
                ).rescan(type, allowedRootIds = allowedRootIds)
            },
            librarySnapshotAdapter = {
                AppDatabase.getInstance(appContext).bookDao().getAllBooksOnce().isEmpty()
            }
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
