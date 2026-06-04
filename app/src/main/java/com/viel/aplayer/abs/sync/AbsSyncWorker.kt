package com.viel.aplayer.abs.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.logger.AbsSyncLogger

class AbsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val rootId = inputData.getString(KEY_ROOT_ID) ?: return Result.failure()
        // Worker Execution Logging (Distinguish worker lifecycle states)
        // Log the worker startup event independently in the sync logger.
        // This helps verify if a scheduled task was actually picked up by WorkManager and executed,
        // distinguishing between "queued but not run" and "run but failed" states.
        AbsSyncLogger.logWorkerStart(rootId)
        val container = APlayerApplication.getContainer(applicationContext)
        val root = AppDatabase.getInstance(applicationContext).libraryRootDao().getRootById(rootId)
            ?: return Result.failure()
        return runCatching {
            container.absCatalogSynchronizer.syncRoot(root)
            AbsSyncLogger.logWorkerSuccess(rootId)
            Result.success()
        }.getOrElse { error ->
            AbsSyncLogger.logWorkerRetry(
                rootId = rootId,
                errorClass = error::class.java.simpleName,
                message = error.message
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_ROOT_ID = "root_id"
    }
}
