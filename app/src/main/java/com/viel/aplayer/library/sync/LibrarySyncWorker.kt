package com.viel.aplayer.library.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.data.gateway.ScanScheduler

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            
            // 详尽的中文注释：
            // 在 M4.5 重构中，为了解除后台定时同步任务对重量级旧仓库的硬编码依赖，
            // 切换为从 Application 的 container 中提取更小知识面的 ScanScheduler 网关组件，
            // 异步在物理后台调度触发书库文件增量扫描同步逻辑。
            val container = (applicationContext as com.viel.aplayer.APlayerApplication).container
            val scanScheduler = container.scanScheduler
            
            scanScheduler.scheduleLibrarySync(trigger)
            Result.success()
        } catch (e: Exception) {
            Log.e("LibrarySyncWorker", "Error during library sync", e)
            Result.failure()
        }
    }
}
