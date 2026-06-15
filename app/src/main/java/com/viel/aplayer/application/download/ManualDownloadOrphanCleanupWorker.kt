package com.viel.aplayer.application.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.CancellationException

class ManualDownloadOrphanCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // Worker Dependency Resolution (Fetch only the process-owned manual cache maintenance command)
            // The worker avoids UI dependency views and does not construct DownloadManager because orphan cleanup only needs cache keys and Room metadata.
            APlayerApplication.getProcessContainer(applicationContext)
                .manualDownloadOrphanCleaner
                .cleanOrphans()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DownloadSyncLogger.logOrphanCleanupFailure(error::class.java.simpleName, error.message)
            Result.retry()
        }
    }
}

class ManualDownloadOrphanCleanupScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue() {
        // Unique Orphan Cleanup Work (Coalesce repeated maintenance requests)
        // Existing queued cleanup is allowed to finish because it uses a fresh Room/cache snapshot when executed.
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ManualDownloadOrphanCleanupWorker>().build()
        )
    }

    private companion object {
        private const val UNIQUE_WORK_NAME = "manual-download-orphan-cleanup"
    }
}
