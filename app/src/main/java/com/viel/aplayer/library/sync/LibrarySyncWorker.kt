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
            // 在 M4.5 重构中，为了解除后台定时同步任务对重量级旧仓库的硬编码依赖，
            // 切换为从 Application 的 container 中提取更小知识面的 ScanScheduler 网关组件，
            // 异步在物理后台调度触发书库文件增量扫描同步逻辑。
            // 详尽的中文注释：在 WorkManager 触发的后台同步工作器中，使用伴生静态方法安全惰性解析依赖容器，
            // 避免将 applicationContext 强转为 APlayerApplication，防止因多进程或辅助启动组件产生 ClassCastException 导致后台同步任务中断崩溃。
            val container = com.viel.aplayer.APlayerApplication.getContainer(applicationContext)
            val scanScheduler = container.scanScheduler
            
            scanScheduler.scheduleLibrarySync(trigger)
            Result.success()
        } catch (e: java.io.IOException) {
            // 详尽的中文注释：针对网络断开、WebDAV 握手超时、SQLite 数据库并发忙死等瞬时物理 I/O 异常，
            // 调度 WorkManager 的 Result.retry() 重载退避重试，利用退避退避策略自愈后台更新
            ScanWorkflowLogger.warn("librarySyncWorker retry: scheduling retry after transient sync failure", e)
            Result.retry()
        } catch (e: Exception) {
            // 详尽的中文注释：针对逻辑缺陷、未定义参数等非瞬时崩溃型故障，返回 Result.failure() 终止任务，规避死循环引起的异常耗电
            ScanWorkflowLogger.error("librarySyncWorker failure: permanent logic error during library sync", e)
            Result.failure()
        }
    }
}
