package com.viel.oto.abs.sync

import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import com.viel.oto.library.availability.isSyncAvailable
import com.viel.oto.logger.AbsLogSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Manages application-scoped ABS catalog synchronization.
 * Coordinates manual and automatic ABS catalog sync jobs outside SettingsViewModel lifetimes while preventing concurrent writes for the same root.
 */
class AbsSyncTaskCoordinator(
    private val libraryRootDao: LibraryRootDao,
    private val synchronizer: AbsCatalogSynchronizer,
    private val feedbackSink: AbsSyncFeedbackSink,
    private val rootPreflight: (suspend (String) -> LibraryRootAvailabilityUpdate?)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : Closeable {

    /**
     * To terminate pending sync task events on teardown.
     */
    override fun close() {
        scope.cancel()
    }

    private val lock = Any()
    private val runningRootIds = linkedSetOf<String>()
    private val _events = MutableSharedFlow<AbsSyncTaskResult>(extraBufferCapacity = 16)
    val events: SharedFlow<AbsSyncTaskResult> = _events.asSharedFlow()

    /**
     * Launches an application-level background synchronization job.
     * Returns false when the selected root is already running; otherwise starts a guarded sync task that validates root availability first.
     */
    fun start(rootId: String, origin: AbsSyncTaskOrigin): Boolean {
        synchronized(lock) {
            if (runningRootIds.contains(rootId)) {
                return false
            }
            runningRootIds += rootId
        }
        scope.launch {
            try {
                val preflight = rootPreflight?.invoke(rootId)
                val root = preflight?.root ?: libraryRootDao.getRootById(rootId)
                if (root == null) {
                    val errMsg = "ABS_ROOT_NOT_FOUND"
                    _events.emit(
                        AbsSyncTaskResult(
                            rootId = rootId,
                            displayName = rootId,
                            origin = origin,
                            summary = null,
                            errorMessage = errMsg
                        )
                    )
                    feedbackSink.syncRootMissing()
                    return@launch
                }
                if (preflight != null && !preflight.isSyncAvailable) {
                    val errMsg = "ROOT_UNAVAILABLE:${preflight.availability.status}"
                    _events.emit(
                        AbsSyncTaskResult(
                            rootId = root.id,
                            displayName = root.displayName,
                            origin = origin,
                            summary = null,
                            errorMessage = errMsg
                        )
                    )
                    feedbackSink.syncBlocked(rootId = root.id, availability = preflight)
                    return@launch
                }
                val summary = synchronizer.syncRootWithSummary(root)
                _events.emit(
                    AbsSyncTaskResult(
                        rootId = root.id,
                        displayName = root.displayName,
                        origin = origin,
                        summary = summary,
                        errorMessage = null
                    )
                )
                feedbackSink.syncCompleted(rootId = root.id, summary = summary)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val errMsg = error.message ?: "ABS_BACKGROUND_SYNC_FAILED"
                _events.emit(
                    AbsSyncTaskResult(
                        rootId = rootId,
                        displayName = rootId,
                        origin = origin,
                        summary = null,
                        errorMessage = errMsg.redactAbsError()
                    )
                )
                feedbackSink.syncFailed(rootId, errMsg.redactAbsError())
            } finally {
                synchronized(lock) {
                    runningRootIds -= rootId
                }
            }
        }
        return true
    }

    private fun String.redactAbsError(): String =
        AbsLogSanitizer.sanitizeText(this)
}

/**
 * Categorizes the source of the synchronization trigger.
 * Allows UI consumers to distinguish user-initiated manual synchronization from automatic synchronization following server registration.
 */
enum class AbsSyncTaskOrigin {
    MANUAL,
    AUTO_ADD
}

/**
 * Represents the lightweight result event dispatched to the UI.
 * Contains synchronization details on success and a compact user-facing error message when execution is blocked or fails.
 */
data class AbsSyncTaskResult(
    val rootId: String,
    val displayName: String,
    val origin: AbsSyncTaskOrigin,
    val summary: AbsSyncSummary?,
    val errorMessage: String?
)
