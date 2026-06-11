package com.viel.aplayer.data.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.orchestrator.ScanSessionRunner
import com.viel.aplayer.library.scan.ScanCommand
import com.viel.aplayer.library.scan.ScanImportAdapter
import com.viel.aplayer.library.scan.ScanLibrarySnapshotAdapter
import com.viel.aplayer.library.scan.ScanOutcome
import com.viel.aplayer.library.scan.ScanOutcomeKind
import com.viel.aplayer.library.scan.ScanRootStatusAdapter
import com.viel.aplayer.library.scan.ScanSession
import com.viel.aplayer.library.sync.LibrarySyncWorker
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.media.parser.CoverRecoveryHelper
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
 * Library Ingestion Coordination Service (Implements ScanScheduler)
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Integrates with LibraryRootStore and ScanSessionRunner in the M6c phase, breaking all ties to the bloated BookLibraryRepository.
 * 2. Re-anchor Serial Scans Lock: Manages a private Mutex internally to coordinate COLD_START_LIGHT and USER_GLOBAL scan modes safely.
 */
@OptIn(UnstableApi::class)
class ScanService(
    context: Context,
    private val coverRecoveryHelper: CoverRecoveryHelper,
    // Shared VFS Facade (Dependency injection reference)
    // Reference to the module's single VFS reader, avoiding internal self-initialization.
    private val vfsFileInterface: VfsFileInterface,
    // Scanner Directory Cache (Injects scanner-only directory child snapshots)
    // Keeps WebDAV listing reuse inside ingestion flows while playback and availability checks continue using direct VFS providers.
    private val directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache,
    // Application Event Sink (Reports scan feedback without depending on playback coordination)
    // ScanService is an application ingestion module, so Toast requests now use the app-level feedback seam directly.
    private val appEventSink: AppEventSink
) : ScanScheduler, java.io.Closeable {

    // Safe Application Context Binding (Memory leak avoidance)
    // Binds applicationContext to avoid tracking Activity lifecycle contexts.
    private val appContext = context.applicationContext

    // Root Status Adapter Construction (Infrastructure adapter seam)
    // Wraps LibraryRootStore so ScanSession owns root eligibility state while ScanService keeps Android construction details.
    private val rootStore = LibraryRootStore(appContext)

    // Private Sync Exception Handler (Task crash safety block)
    // Captures background exception states to avoid uncaught background thread failures.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("scanService coroutine failure", exception)
    }

    // Background Ingestion Coroutine Scope (Resource-isolated execution pool)
    // Manages background tasks on the IO dispatch thread pool to prevent blocking main routines.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Serial Scan Exclusion Mutex (Database transaction isolation)
    // Excludes concurrent scanner invocations to prevent SQLite concurrent lock and transaction errors.
    private val scanMutex = Mutex()

    override suspend fun syncLibrary(trigger: String): ScanOutcome = scanMutex.withLock {
        val outcome = runSyncLibrary(trigger)
        logScanOutcome(trigger, outcome)
        emitScanOutcomeFeedback(outcome)
        outcome
    }

    override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean) {
        // Update ScanService to use type-safe AudiobookSchema.ScanTrigger: Convert trigger string to ScanTrigger enum before scheduling.
        val scanTrigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)
        val policy = WorkSchedulingPolicy.librarySync(trigger = scanTrigger, requiresNetwork = requiresNetwork)
        // Enqueue Unique Work (Apply trigger-aware replacement and connectivity policy)
        // Cold-start scans keep debounce behavior, while user/root-edit scans replace stale queued work so new root settings are not dropped.
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString("trigger", trigger)
                    .build()
            )
            .setConstraints(policy.constraints)
            .setBackoffCriteria(policy.backoffPolicy, policy.backoffDelay, policy.backoffTimeUnit)
            .build()
        workManager.enqueueUniqueWork(
            policy.uniqueWorkName,
            policy.existingWorkPolicy,
            request
        )
    }

    /**
     * Core Sync Execution (Metadata synchronization and recovery)
     * Dispatches reachability checks, scans folders, and launches cover self-healing procedures.
     */
    private suspend fun runSyncLibrary(trigger: String): ScanOutcome = withContext(Dispatchers.IO) {
        // Scan Session Adapter Assembly (Command execution seam)
        // Builds concrete Android and Room adapters once per command while ScanSession owns status transitions and outcome mapping.
        // Update ScanService to use type-safe AudiobookSchema.ScanTrigger: Convert trigger string to ScanTrigger enum before executing ScanCommand.
        val scanTrigger = runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)
        createScanSession().execute(ScanCommand(scanTrigger))
    }

    private fun createScanSession(): ScanSession =
        ScanSession(
            rootStatusAdapter = ScanRootStatusAdapter {
                rootStore.refreshPermissionStatuses()
            },
            importAdapter = ScanImportAdapter { type, allowedRootIds ->
                // Runner Import Adapter (Inventory stream and import transaction bridge)
                // Delegates the heavy scan work to ScanSessionRunner while keeping runner construction outside the command state module.
                ScanSessionRunner(
                    context = appContext,
                    vfsFileInterface = vfsFileInterface,
                    directoryListingCache = directoryListingCache,
                    triggerCoverRegeneration = coverRecoveryHelper::checkAndTriggerCoverRegeneration
                ).rescan(type, allowedRootIds = allowedRootIds)
            },
            librarySnapshotAdapter = ScanLibrarySnapshotAdapter {
                AppDatabase.getInstance(appContext).bookDao().getAllBooksOnce().isEmpty()
            }
        )

    private fun logScanOutcome(trigger: String, outcome: ScanOutcome) {
        // Outcome Logging (Records the unified scan result category at the service boundary)
        // This keeps diagnostics aligned with the command result returned to WorkManager and foreground callers.
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
                ScanWorkflowLogger.warn("scanService blocked: trigger=$trigger, message=${outcome.message?.mergeKey}")
            ScanOutcomeKind.RETRY ->
                ScanWorkflowLogger.warn("scanService retry: trigger=$trigger, message=${outcome.message?.mergeKey}", outcome.cause)
            ScanOutcomeKind.FAILED ->
                ScanWorkflowLogger.error("scanService failed: trigger=$trigger, message=${outcome.message?.mergeKey}", outcome.cause)
        }
    }

    private fun emitScanOutcomeFeedback(outcome: ScanOutcome) {
        // Outcome Feedback Emission (Publishes the policy-selected user message once per scan command)
        // Both manual scans and WorkManager-triggered scans now use the same outcome text instead of separate service/worker mappings.
        outcome.message?.let { message -> appEventSink.showToast(message) }
    }

    override fun close() {
        // Terminate Active Sync Pipelines (Shutdown resource cleanup)
        // Cancels the private scope upon service teardown to abort active directory synchronization tasks.
        scope.cancel()
    }
}
