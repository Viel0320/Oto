package com.viel.aplayer.library.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.library.scan.ScanOutcomeKind
import com.viel.aplayer.library.scan.ScanOutcomePolicy
import com.viel.aplayer.logger.ScanWorkflowLogger
import kotlinx.coroutines.CancellationException

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            
            // 
            // Refactored to delegate scan requests to the focused ScanScheduler interface instead of calling legacy repository routines directly.
            // Library Sync Worker Dependency Resolution (Fetch only the scheduler required by this WorkManager job)
            // Narrowing the worker view prevents background scans from depending on playback, settings, or ABS container entries.
            val workerDependencies = com.viel.aplayer.APlayerApplication.getLibrarySyncWorkerDependencies(applicationContext)
            val scanScheduler = workerDependencies.scanScheduler
            
            // Execute Ingestion Outcome (Share the same scan result contract used by foreground commands)
            // WorkManager now adapts the returned ScanOutcome instead of classifying scanner exceptions separately.
            scanScheduler.syncLibrary(trigger).toWorkerResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Worker Fallback Outcome (Uses the shared scan policy when failure happens before ScanScheduler returns)
            // This keeps WorkManager retry/failure semantics in one policy even for dependency resolution or early command failures.
            val outcome = ScanOutcomePolicy.fromFailure(e)
            ScanWorkflowLogger.warn("librarySyncWorker fallback outcome=${outcome.kind}: feedback=${outcome.feedback?.outcome?.identity?.topic}", e)
            outcome.toWorkerResult()
        }
    }

    private fun com.viel.aplayer.library.scan.ScanOutcome.toWorkerResult(): Result {
        // WorkManager Outcome Adapter (Maps domain scan result semantics to background scheduling decisions)
        // Blocked and partial scans complete the command, retry only represents transient infrastructure failure.
        return when (kind) {
            ScanOutcomeKind.SUCCESS,
            ScanOutcomeKind.PARTIAL,
            ScanOutcomeKind.BLOCKED -> Result.success()
            ScanOutcomeKind.RETRY -> Result.retry()
            ScanOutcomeKind.FAILED -> Result.failure()
        }
    }
}
