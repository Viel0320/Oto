package com.viel.aplayer.abs.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.runCatchingCancellable
import com.viel.aplayer.di.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.event.feedback.LibraryAccessFeedbackFacts
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.logger.AbsSyncLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AbsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val workerDependencies: AbsSyncWorkerDependencies by inject()
    private val libraryRootStore: LibraryRootStore by inject()

    override suspend fun doWork(): Result {
        val rootId = inputData.getString(KEY_ROOT_ID) ?: return Result.failure()
        AbsSyncLogger.logWorkerStart(rootId)
        val preflight = libraryRootStore.refreshRootStatus(rootId)
            ?: return Result.failure()
        if (!preflight.isSyncAvailable) {
            val message = buildRootUnavailableSyncMessage(preflight)
            AbsSyncLogger.logWorkerFailure(
                rootId = rootId,
                errorClass = "RootUnavailable",
                message = "ROOT_UNAVAILABLE:${preflight.availability.status}"
            )
            workerDependencies.appEventSink.emitFeedback(
                LibraryAccessFeedbackFacts.syncBlocked(rootId = rootId, detailMessage = message)
            )
            return Result.failure()
        }
        return runSync(rootId, preflight.root, workerDependencies)
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
            workerDependencies: AbsSyncWorkerDependencies
        ): Result {
            return runCatchingCancellable {
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
    }
}
