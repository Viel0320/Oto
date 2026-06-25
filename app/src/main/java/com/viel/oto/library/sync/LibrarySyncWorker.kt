package com.viel.oto.library.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.oto.library.scan.ScanScheduler
import com.viel.oto.library.scan.ScanOutcomeKind
import com.viel.oto.library.scan.ScanOutcomePolicy
import com.viel.oto.logger.ScanWorkflowLogger
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {
    private val scanScheduler: ScanScheduler by inject()

    /**
     * Executes one WorkManager-backed library sync command.
     *
     * Cold-start work normally has no root scope, but rootIds are still forwarded so any future
     * background root command keeps the same scanner contract as direct user-priority scheduling.
     */
    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            val rootIds = inputData.getStringArray("rootIds")
                ?.filter { rootId -> rootId.isNotBlank() }
                ?.toSet()
                .orEmpty()

            scanScheduler.syncLibrary(trigger, rootIds = rootIds).toWorkerResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = ScanOutcomePolicy.fromFailure(e)
            ScanWorkflowLogger.warn("librarySyncWorker fallback outcome=${outcome.kind}: feedback=${outcome.feedback?.outcome?.identity?.topic}", e)
            outcome.toWorkerResult()
        }
    }

    private fun com.viel.oto.library.scan.ScanOutcome.toWorkerResult(): Result {
        return when (kind) {
            ScanOutcomeKind.SUCCESS,
            ScanOutcomeKind.PARTIAL,
            ScanOutcomeKind.BLOCKED -> Result.success()
            ScanOutcomeKind.RETRY -> Result.retry()
            ScanOutcomeKind.FAILED -> Result.failure()
        }
    }
}
