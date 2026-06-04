package com.viel.aplayer.abs.sync

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.logger.AbsSyncLogger
import java.util.concurrent.TimeUnit

class AbsSyncWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(rootId: String) {
        val request = OneTimeWorkRequestBuilder<AbsSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AbsSyncWorker.KEY_ROOT_ID, rootId)
                    .build()
            )
            .setInitialDelay(0, TimeUnit.SECONDS)
            .build()
        // Task Queue Logging (Trace work scheduling attempt)
        // Log the scheduling event with the unique work name when enqueuing the sync task.
        // The scheduler only tracks whether the task is successfully submitted to WorkManager, not the final execution.
        // Recording this helps diagnose queue collisions, concurrency constraints, and replacement policies.
        AbsSyncLogger.logSchedulerEnqueue(rootId = rootId, uniqueWorkName = uniqueWorkName(rootId))
        workManager.enqueueUniqueWork(
            uniqueWorkName(rootId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun uniqueWorkName(rootId: String): String = "abs-sync:$rootId"
}
