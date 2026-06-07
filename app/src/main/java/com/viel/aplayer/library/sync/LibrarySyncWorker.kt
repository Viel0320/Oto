package com.viel.aplayer.library.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.logger.ScanWorkflowLogger

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            
            // 
            // Refactored to delegate scan requests to the focused ScanScheduler interface instead of calling legacy repository routines directly.
            // Safe Dependency Resolution (Uses the companion getter method to fetch dependencies lazily inside WorkManager's worker instance)
            // Prevents casting applicationContext to APlayerApplication directly, eliminating ClassCastExceptions during multi-process setups.
            val container = com.viel.aplayer.APlayerApplication.getContainer(applicationContext)
            val scanScheduler = container.scanScheduler
            
            // Execute Ingestion: Call suspend syncLibrary directly to perform the sync within WorkManager's execution scope.
            scanScheduler.syncLibrary(trigger)
            Result.success()
        } catch (e: java.io.IOException) {
            // Transient Exception Handler (Intercepts temporary physical failures such as network drops, timeouts, or SQLite lock contentions)
            // Dispatches WorkManager's Result.retry() response to schedule backoff attempts and recover background scan runs automatically.
            ScanWorkflowLogger.warn("librarySyncWorker retry: scheduling retry after transient sync failure", e)
            Result.retry()
        } catch (e: Exception) {
            // Fatal Fault Handler (Catches logic issues or parameter configuration errors, returning Result.failure() to avoid power-draining reload loops)
            ScanWorkflowLogger.error("librarySyncWorker failure: permanent logic error during library sync", e)
            Result.failure()
        }
    }
}
