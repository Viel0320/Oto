package com.viel.aplayer.abs.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.logger.AbsSyncLogger

class AbsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val rootId = inputData.getString(KEY_ROOT_ID) ?: return Result.failure()
        // Worker Execution Logging (Distinguishes worker lifecycle states)
        // Logs startup separately so diagnostics can tell queued work from actually executed work.
        AbsSyncLogger.logWorkerStart(rootId)
        // ABS Sync Worker Dependency Resolution (Fetch only feedback and catalog-sync capabilities)
        // The worker should not learn settings, playback, or VFS dependencies while mirroring a single ABS root.
        val workerDependencies = APlayerApplication.getAbsSyncWorkerDependencies(applicationContext)
        val preflight = LibraryRootStore(applicationContext).refreshRootStatus(rootId)
            ?: return Result.failure()
        if (!preflight.isSyncAvailable) {
            // Worker Root Preflight Guard (Blocks background ABS sync when the target root is unavailable)
            // Refreshes persisted root status and reports the skipped sync before catalog requests are attempted.
            val message = buildRootUnavailableSyncMessage(preflight)
            AbsSyncLogger.logWorkerFailure(
                rootId = rootId,
                errorClass = "RootUnavailable",
                message = message
            )
            // Worker Feedback Dispatch (Use the app-level sink instead of the playback event bus)
            // WorkManager runs outside the UI lifecycle, so user-facing sync messages must go through the process-wide feedback seam.
            workerDependencies.appEventSink.showToast(message)
            return Result.failure()
        }
        val root = preflight.root
        return runCatching {
            workerDependencies.absCatalogSynchronizer.syncRoot(root)
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
