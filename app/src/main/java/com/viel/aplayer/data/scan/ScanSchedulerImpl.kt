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
import com.viel.aplayer.library.availability.isDirectorySyncRoot
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Per-root fan-out scan controller.
 *
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Integrates with LibraryRootStore and ScanSessionRunner without any tie to a
 *    legacy library repository.
 * 2. Per-root parallelism: A single sync command fans out into one independent scan job per library root, each
 *    running its own ScanSession (and thus its own ScanSessionEntity), bounded by a shared concurrency limit.
 * 3. User commands bind to a root: a user-initiated scan on a root cancels any in-flight job for that same root and
 *    is NOT requeued, while cold-start jobs on other roots keep running untouched.
 */
class ScanSchedulerImpl(
    context: Context,
    private val coverRecoveryGateway: CoverRecoveryGateway,
    private val vfsFileInterface: VfsFileInterface,
    private val directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache,
    private val appEventSink: AppEventSink,
    private val rootScanExecutor: RootScanExecutor? = null,
    private val coldStartRootIdsProvider: (suspend () -> List<String>)? = null
) : ScanScheduler, java.io.Closeable {

    private val appContext = context.applicationContext

    private val rootStore by lazy { LibraryRootStore(appContext) }

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("scanService coroutine failure", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private val mutex = Mutex()
    private val activeByRoot = mutableMapOf<String, ActiveRootScan>()
    private val scanSemaphore = Semaphore(SCAN_CONCURRENCY)

    override suspend fun syncLibrary(trigger: String, rootIds: Set<String>): ScanOutcome {
        val scanTrigger = parseTrigger(trigger)
        val isUser = scanTrigger != AudiobookSchema.ScanTrigger.COLD_START
        val cleaned = rootIds.map { it.trim() }.filter { it.isNotEmpty() }
        val targetRootIds: List<String> = when {
            cleaned.isNotEmpty() -> cleaned
            scanTrigger == AudiobookSchema.ScanTrigger.COLD_START -> coldStartRootIds()
            else -> emptyList()
        }

        if (targetRootIds.isEmpty()) {
            // USER/ADD without targets, or cold-start with no active directory roots: run one command so the
            // blocked / no-work feedback wording is preserved.
            val outcome = runSingleRootCommand(ScanCommand(scanTrigger, emptySet()))
            logScanOutcome(scanTrigger.name, outcome)
            if (isUser) emitScanOutcomeFeedback(outcome)
            return outcome
        }

        val deferreds = mutex.withLock {
            targetRootIds.mapNotNull { rootId -> launchRootScanLocked(rootId, scanTrigger, isUser) }
        }
        val outcomes = deferreds.mapNotNull { deferred ->
            try {
                deferred.await()
            } catch (_: CancellationException) {
                // Distinguish our own cancellation (propagate) from a root that another user command superseded
                // (drop it from aggregation instead of misreporting it as a failure).
                currentCoroutineContext().ensureActive()
                null
            }
        }
        val aggregated = aggregate(outcomes)
        if (!isUser && hasCatalogChange(outcomes)) {
            val discovered = outcomes.sumOf { outcome -> outcome.session?.discoveredBookCount ?: 0 }
            appEventSink.emitFeedback(ScanOutcomePolicy.coldStartSummaryFeedback(discovered))
        }
        return aggregated
    }

    override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean, rootIds: Set<String>) {
        val scanTrigger = parseTrigger(trigger)
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
        val request = requestBuilder.build()
        workManager.enqueueUniqueWork(
            policy.uniqueWorkName,
            policy.existingWorkPolicy,
            request
        )
    }

    /**
     * Starts or reuses the scan job bound to one root, applying user-over-cold precedence.
     *
     * Must be called while holding [mutex]. A user command cancels any in-flight job for the same root and starts a
     * fresh one (never requeued). A cold-start command yields to a user job on that root (returns null) and reuses an
     * already-running cold-start job instead of starting a duplicate.
     */
    private fun launchRootScanLocked(
        rootId: String,
        trigger: AudiobookSchema.ScanTrigger,
        isUser: Boolean
    ): Deferred<ScanOutcome>? {
        val existing = activeByRoot[rootId]
        if (!isUser) {
            if (existing != null && existing.isUserPriority) return null
            if (existing != null && existing.job.isActive) return existing.job
        } else {
            existing?.job?.cancel(CancellationException("Superseded by user scan on root $rootId"))
        }

        lateinit var deferred: Deferred<ScanOutcome>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                scanSemaphore.withPermit {
                    ensureActive()
                    val outcome = runSingleRootCommand(ScanCommand(trigger, setOf(rootId)))
                    logScanOutcome(trigger.name, outcome)
                    if (isUser) emitScanOutcomeFeedback(outcome)
                    outcome
                }
            } finally {
                mutex.withLock {
                    if (activeByRoot[rootId]?.job === deferred) {
                        activeByRoot.remove(rootId)
                    }
                }
            }
        }
        activeByRoot[rootId] = ActiveRootScan(deferred, isUser)
        deferred.start()
        return deferred
    }

    /**
     * Executes one root-scoped command via the injectable executor seam, defaulting to a fresh ScanSession.
     */
    private suspend fun runSingleRootCommand(command: ScanCommand): ScanOutcome =
        rootScanExecutor?.run(command)
            ?: withContext(Dispatchers.IO) { createScanSession().execute(command) }

    @OptIn(UnstableApi::class)
    private fun createScanSession(): ScanSession =
        ScanSession(
            rootStatusAdapter = { targetRootIds ->
                if (targetRootIds.isEmpty()) {
                    rootStore.refreshPermissionStatuses()
                } else {
                    targetRootIds.mapNotNull { rootId -> rootStore.refreshRootStatus(rootId) }
                }
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

    private suspend fun coldStartRootIds(): List<String> =
        coldStartRootIdsProvider?.invoke() ?: defaultColdStartRootIds()

    /**
     * Resolves all ACTIVE directory roots for a cold-start fan-out without triggering a full permission refresh.
     *
     * Availability is intentionally not pre-filtered here: per-root ScanSession decides reachability, and keeping
     * unreachable roots in the set still lets each root enter its recovery path.
     */
    private suspend fun defaultColdStartRootIds(): List<String> =
        AppDatabase.getInstance(appContext).libraryRootDao().getActiveRootsOnce()
            .filter { root -> root.isDirectorySyncRoot() }
            .map { root -> root.id }

    /**
     * Folds per-root outcomes into one, keeping the most severe kind so a WorkManager-backed cold-start surfaces a
     * single representative result. Rank: FAILED > RETRY > PARTIAL > SUCCESS > BLOCKED.
     */
    private fun aggregate(outcomes: List<ScanOutcome>): ScanOutcome =
        outcomes.maxByOrNull { outcome -> rank(outcome.kind) }
            ?: ScanOutcomePolicy.noScanWorkRequired()

    private fun rank(kind: ScanOutcomeKind): Int = when (kind) {
        ScanOutcomeKind.FAILED -> 4
        ScanOutcomeKind.RETRY -> 3
        ScanOutcomeKind.PARTIAL -> 2
        ScanOutcomeKind.SUCCESS -> 1
        ScanOutcomeKind.BLOCKED -> 0
    }

    private fun hasCatalogChange(outcomes: List<ScanOutcome>): Boolean =
        outcomes.any { outcome ->
            val session = outcome.session ?: return@any false
            (session.discoveredBookCount + session.updatedBookCount + session.partialBookCount) > 0
        }

    private fun parseTrigger(trigger: String): AudiobookSchema.ScanTrigger =
        runCatching { AudiobookSchema.ScanTrigger.valueOf(trigger) }
            .getOrDefault(AudiobookSchema.ScanTrigger.USER)

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

    private companion object {
        private const val SCAN_CONCURRENCY = 4
    }
}

/**
 * Injectable per-root command executor seam.
 *
 * Defaults to running a fresh ScanSession; tests substitute a controllable executor to verify fan-out, precedence,
 * concurrency limiting, and aggregation without constructing Android scan infrastructure.
 */
fun interface RootScanExecutor {
    suspend fun run(command: ScanCommand): ScanOutcome
}

/**
 * Private scheduler bookkeeping for the job bound to one root.
 *
 * [isUserPriority] lets a cold-start fan-out yield to (rather than cancel) a user-initiated scan on the same root.
 */
private class ActiveRootScan(
    val job: Deferred<ScanOutcome>,
    val isUserPriority: Boolean
)
