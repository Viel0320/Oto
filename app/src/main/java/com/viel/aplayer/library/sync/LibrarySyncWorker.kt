package com.viel.aplayer.library.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            
            // 
            // 在 M4.5 重构中，为了解除后台定时同步任务对重量级旧仓库的硬编码依赖，
            // 切换为从 Application 的 container 中提取更小知识面的 ScanScheduler 网关组件，
            // 异步在物理后台调度触发书库文件增量扫描同步逻辑。
            val container = (applicationContext as com.viel.aplayer.APlayerApplication).container
            val scanScheduler = container.scanScheduler
            
            scanScheduler.scheduleLibrarySync(trigger)
            Result.success()
        } catch (e: java.io.IOException) {
            // 详尽的中文注释：针对网络断开、WebDAV 握手超时、SQLite 数据库并发忙死等瞬时物理 I/O 异常，
            // 调度 WorkManager 的 Result.retry() 重载退避重试，利用退避退避策略自愈后台更新
            Log.w("LibrarySyncWorker", "Transient network/DB lock error during library sync, scheduling retry", e)
            Result.retry()
        } catch (e: Exception) {
            // 详尽的中文注释：针对逻辑缺陷、未定义参数等非瞬时崩溃型故障，返回 Result.failure() 终止任务，规避死循环引起的异常耗电
            Log.e("LibrarySyncWorker", "Permanent logic error during library sync", e)
            Result.failure()
        }
    }
}
