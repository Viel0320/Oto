package com.viel.aplayer.abs.sync

import com.viel.aplayer.data.dao.LibraryRootDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * ABS Sync Task Coordinator: Manages application-scoped ABS catalog synchronization.
 *
 * Design Objectives:
 * 1. Ensure that both manual synchronization and automatic synchronization (triggered upon adding a server)
 *    run within an application-scoped coroutine scope. This prevents interruption when `SettingsActivity` or
 *    `SettingsViewModel` is destroyed.
 * 2. Decouple the ViewModel so that it only initiates tasks and consumes result events without hosting the
 *    actual synchronization lifecycle.
 * 3. Restrict concurrent synchronization executions per server root, permitting only one active sync task
 *    per `rootId` to avoid write conflicts in the local database.
 */
class AbsSyncTaskCoordinator(
    private val libraryRootDao: LibraryRootDao,
    private val synchronizer: AbsCatalogSynchronizer,
    private val playbackManager: com.viel.aplayer.media.PlaybackManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val lock = Any()
    private val runningRootIds = linkedSetOf<String>()
    private val _events = MutableSharedFlow<AbsSyncTaskResult>(extraBufferCapacity = 16)
    val events: SharedFlow<AbsSyncTaskResult> = _events.asSharedFlow()

    /**
     * Start Sync Task: Launches an application-level background synchronization job.
     *
     * Initiates the catalog sync for the specified root. If a synchronization task for the given `rootId`
     * is already active, this method immediately returns `false` so the caller can display a "sync in progress" message.
     *
     * @param rootId The unique identifier of the library root.
     * @param origin The trigger source of this synchronization task.
     * @return `true` if the synchronization task was successfully queued; `false` if it was already running.
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
                val root = libraryRootDao.getRootById(rootId)
                if (root == null) {
                    val errMsg = "找不到对应的 ABS 书库根"
                    _events.emit(
                        AbsSyncTaskResult(
                            rootId = rootId,
                            displayName = rootId,
                            origin = origin,
                            summary = null,
                            errorMessage = errMsg
                        )
                    )
                    playbackManager.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast("ABS 后台同步失败：$errMsg"))
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
                val toastMsg = "ABS 后台同步完成：成功添加 ${summary.addedBooks} 本，失败 ${summary.failedItems} 本"
                playbackManager.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast(toastMsg))
            } catch (error: Exception) {
                val errMsg = error.message ?: "ABS 后台同步失败"
                _events.emit(
                    AbsSyncTaskResult(
                        rootId = rootId,
                        displayName = rootId,
                        origin = origin,
                        summary = null,
                        errorMessage = error.message
                    )
                )
                val toastMsg = "ABS 后台同步失败：${errMsg.redactAbsError()}"
                playbackManager.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast(toastMsg))
            } finally {
                synchronized(lock) {
                    runningRootIds -= rootId
                }
            }
        }
        return true
    }

    private fun String.redactAbsError(): String =
        replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
}

/**
 * Sync Task Origin: Categorizes the source of the synchronization trigger.
 *
 * Classified to enable the UI to distinguish between user-initiated manual synchronization
 * and automated background synchronization following server addition.
 */
enum class AbsSyncTaskOrigin {
    MANUAL,
    AUTO_ADD
}

/**
 * Sync Task Result: Represents the lightweight result event dispatched to the UI.
 *
 * Contains synchronization details upon completion. A non-null `summary` denotes a successful
 * execution, whereas a non-null `errorMessage` indicates a failure.
 */
data class AbsSyncTaskResult(
    val rootId: String,
    val displayName: String,
    val origin: AbsSyncTaskOrigin,
    val summary: AbsSyncSummary?,
    val errorMessage: String?
)
