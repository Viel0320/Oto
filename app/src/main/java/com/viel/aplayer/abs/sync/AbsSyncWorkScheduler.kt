package com.viel.aplayer.abs.sync

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.logger.AbsSyncLogger
import com.viel.aplayer.work.WorkSchedulingPolicy
import java.util.concurrent.TimeUnit

class AbsSyncWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(rootId: String) {
        val policy = WorkSchedulingPolicy.absRootSync(rootId)
        val request = OneTimeWorkRequestBuilder<AbsSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AbsSyncWorker.KEY_ROOT_ID, rootId)
                    .build()
            )
            .setInitialDelay(0, TimeUnit.SECONDS)
            .setConstraints(policy.constraints)
            .setBackoffCriteria(policy.backoffPolicy, policy.backoffDelay, policy.backoffTimeUnit)
            .build()
        AbsSyncLogger.logSchedulerEnqueue(rootId = rootId, uniqueWorkName = policy.uniqueWorkName)
        workManager.enqueueUniqueWork(
            policy.uniqueWorkName,
            policy.existingWorkPolicy,
            request
        )
    }
}
