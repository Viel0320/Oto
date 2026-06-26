package com.viel.oto.abs.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.runCatchingCancellable
import com.viel.oto.library.LibraryRootStore
import com.viel.oto.library.availability.isSyncAvailable
import com.viel.oto.logger.AbsSyncLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AbsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val feedbackSink: AbsSyncFeedbackSink by inject()
    private val absCatalogSynchronizer: AbsCatalogSynchronizer by inject()
    private val libraryRootStore: LibraryRootStore by inject()

    override suspend fun doWork(): Result {
        val rootId = inputData.getString(KEY_ROOT_ID) ?: return Result.failure()
        AbsSyncLogger.logWorkerStart(rootId)
        val preflight = libraryRootStore.refreshRootStatus(rootId)
            ?: return Result.failure()
        if (!preflight.isSyncAvailable) {
            AbsSyncLogger.logWorkerFailure(
                rootId = rootId,
                errorClass = "RootUnavailable",
                message = "ROOT_UNAVAILABLE:${preflight.availability.status}"
            )
            feedbackSink.syncBlocked(rootId = rootId, availability = preflight)
            return Result.failure()
        }
        return runSync(rootId, preflight.root, absCatalogSynchronizer)
    }

    companion object {
        const val KEY_ROOT_ID = "root_id"

        /**
         * Separates Android worker setup from ABS catalog execution.
         * Keeping the retry adapter in this focused seam lets tests verify cancellation propagation without constructing WorkManager runtime objects.
         */
        internal suspend fun runSync(
            rootId: String,
            root: LibraryRootEntity,
            absCatalogSynchronizer: AbsCatalogSynchronizer
        ): Result {
            return runCatchingCancellable {
                absCatalogSynchronizer.syncRoot(root)
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
    }
}
