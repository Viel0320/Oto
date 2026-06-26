package com.viel.oto.app.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.viel.oto.application.download.ManualDownloadOrphanCleaner
import com.viel.oto.logger.DownloadSyncLogger
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager entrypoint for manual-download cache cleanup.
 *
 * The Worker stays in the app module because WorkManager instantiates Android runtime classes by
 * name, while the cleanup rule itself remains in the application download module.
 */
class ManualDownloadOrphanCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val orphanCleaner: ManualDownloadOrphanCleaner by inject()

    override suspend fun doWork(): Result {
        return try {
            orphanCleaner.cleanOrphans()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DownloadSyncLogger.logOrphanCleanupFailure(error::class.java.simpleName, error.message)
            Result.retry()
        }
    }
}

/**
 * Schedules orphan cleanup through WorkManager's unique-work policy.
 *
 * Keeping scheduling beside the Worker avoids exposing WorkManager types through application download
 * commands while preserving the existing KEEP semantics for repeated enqueue attempts.
 */
class ManualDownloadOrphanCleanupScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue() {
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
