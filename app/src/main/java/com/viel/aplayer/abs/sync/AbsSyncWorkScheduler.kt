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
        // 详尽中文注释：调度器只负责“是否成功入队”，不负责执行结果，因此这里单独记录 unique work 名称，方便排查互斥与覆盖策略。
        AbsSyncLogger.logSchedulerEnqueue(rootId = rootId, uniqueWorkName = uniqueWorkName(rootId))
        workManager.enqueueUniqueWork(
            uniqueWorkName(rootId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun uniqueWorkName(rootId: String): String = "abs-sync:$rootId"
}
