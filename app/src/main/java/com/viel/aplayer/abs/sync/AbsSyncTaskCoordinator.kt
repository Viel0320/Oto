package com.viel.aplayer.abs.sync

// Resource Cleanup Support: Import Closeable and cancel extensions for proper background scope lifecycle management.
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.ui.common.UiEvent
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
 * ABS Sync Task Coordinator (Manages application-scoped ABS catalog synchronization)
 * Coordinates manual and automatic ABS catalog sync jobs outside SettingsViewModel lifetimes while preventing concurrent writes for the same root.
 */
class AbsSyncTaskCoordinator(
    private val libraryRootDao: LibraryRootDao,
    private val synchronizer: AbsCatalogSynchronizer,
    private val playbackManager: com.viel.aplayer.media.PlaybackManager,
    // Root Preflight Refresh (Updates root state before ABS catalog synchronization)
    // Injects the application-service boundary so the coordinator can block unavailable roots without owning protocol-specific availability logic.
    private val rootPreflight: (suspend (String) -> LibraryRootAvailabilityUpdate?)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : Closeable {

    /**
     * Release running scopes (To terminate pending sync task events on teardown)
     */
    override fun close() {
        scope.cancel()
    }

    private val lock = Any()
    private val runningRootIds = linkedSetOf<String>()
    private val _events = MutableSharedFlow<AbsSyncTaskResult>(extraBufferCapacity = 16)
    val events: SharedFlow<AbsSyncTaskResult> = _events.asSharedFlow()

    /**
     * Start Sync Task (Launches an application-level background synchronization job)
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
                    playbackManager.sendUiEvent(UiEvent.ShowToast("ABS 后台同步失败：$errMsg"))
                    return@launch
                }
                if (preflight != null && !preflight.isSyncAvailable) {
                    // Unavailable Root Short-Circuit (Stops ABS sync before remote catalog requests begin)
                    // Emits the same result channel and toast path as failures while preserving the refreshed root status in Room.
                    val errMsg = buildRootUnavailableSyncMessage(preflight)
                    _events.emit(
                        AbsSyncTaskResult(
                            rootId = root.id,
                            displayName = root.displayName,
                            origin = origin,
                            summary = null,
                            errorMessage = errMsg
                        )
                    )
                    playbackManager.sendUiEvent(UiEvent.ShowToast(errMsg))
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
                playbackManager.sendUiEvent(UiEvent.ShowToast(toastMsg))
            } catch (error: Exception) {
                val errMsg = error.message ?: "ABS 后台同步失败"
                _events.emit(
                    AbsSyncTaskResult(
                        rootId = rootId,
                        displayName = rootId,
                        origin = origin,
                        summary = null,
                        errorMessage = errMsg.redactAbsError()
                    )
                )
                playbackManager.sendUiEvent(UiEvent.ShowToast("ABS 后台同步失败：${errMsg.redactAbsError()}"))
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
 * Sync Task Origin (Categorizes the source of the synchronization trigger)
 * Allows UI consumers to distinguish user-initiated manual synchronization from automatic synchronization following server registration.
 */
enum class AbsSyncTaskOrigin {
    MANUAL,
    AUTO_ADD
}

/**
 * Sync Task Result (Represents the lightweight result event dispatched to the UI)
 * Contains synchronization details on success and a compact user-facing error message when execution is blocked or fails.
 */
data class AbsSyncTaskResult(
    val rootId: String,
    val displayName: String,
    val origin: AbsSyncTaskOrigin,
    val summary: AbsSyncSummary?,
    val errorMessage: String?
)
