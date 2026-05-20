package com.viel.aplayer.library.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import com.viel.aplayer.data.LibraryRepository

class LibrarySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString("trigger") ?: "USER"
            val repository = LibraryRepository.getInstance(applicationContext)
            repository.syncLibrary(trigger)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.d("LibrarySyncWorker", "Library sync cancelled")
                throw e
            }
            Log.e("LibrarySyncWorker", "Error during library sync", e)
            Result.failure()
        }
    }
}