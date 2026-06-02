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
        // 详尽中文注释：worker 生命周期日志单独归档到同步路径，便于区分“已入队未启动”和“已启动但执行失败”。
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
