package com.viel.aplayer.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.data.LibraryRepository

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val repository = LibraryRepository.getInstance(applicationContext)
        repository.syncLibrary()
        return Result.success()
    }
}
