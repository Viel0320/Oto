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

            val workerDependencies = com.viel.aplayer.APlayerApplication.getLibrarySyncWorkerDependencies(applicationContext)
            val scanScheduler = workerDependencies.scanScheduler

            scanScheduler.syncLibrary(trigger).toWorkerResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = ScanOutcomePolicy.fromFailure(e)
            ScanWorkflowLogger.warn("librarySyncWorker fallback outcome=${outcome.kind}: feedback=${outcome.feedback?.outcome?.identity?.topic}", e)
            outcome.toWorkerResult()
        }
    }

    private fun com.viel.aplayer.library.scan.ScanOutcome.toWorkerResult(): Result {
        return when (kind) {
            ScanOutcomeKind.SUCCESS,
            ScanOutcomeKind.PARTIAL,
            ScanOutcomeKind.BLOCKED -> Result.success()
            ScanOutcomeKind.RETRY -> Result.retry()
            ScanOutcomeKind.FAILED -> Result.failure()
        }
    }
}
