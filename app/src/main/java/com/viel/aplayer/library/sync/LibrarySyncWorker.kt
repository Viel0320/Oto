package com.viel.aplayer.library.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.data.LibraryRepository

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            val repository = LibraryRepository.getInstance(applicationContext)
            // 详尽的中文注释：Worker 只负责兼容历史入队请求，真正扫描提交给 Repository 应用级队列，避免 Worker/页面生命周期持有长扫描。
            repository.scheduleLibrarySync(trigger)
            Result.success()
        } catch (e: Exception) {
            Log.e("LibrarySyncWorker", "Error during library sync", e)
            Result.failure()
        }
    }
}
