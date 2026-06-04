package com.viel.aplayer.data.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.orchestrator.RescanType
import com.viel.aplayer.library.orchestrator.ScanSessionRunner
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Library Ingestion Coordination Service (Implements ScanScheduler)
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Integrates with LibraryRootStore and ScanSessionRunner in the M6c phase, breaking all ties to the bloated BookLibraryRepository.
 * 2. Re-anchor Serial Scans Lock: Manages a private Mutex internally to coordinate COLD_START_LIGHT and USER_GLOBAL scan modes safely.
 */
@OptIn(UnstableApi::class)
class ScanService(
    context: Context,
    private val coverRecoveryHelper: CoverRecoveryHelper,
    // Shared VFS Facade (Dependency injection reference)
    // Reference to the module's single VFS reader, avoiding internal self-initialization.
    private val vfsFileInterface: VfsFileInterface,
    private val playbackManager: com.viel.aplayer.media.PlaybackManager
) : ScanScheduler, java.io.Closeable {

    // Safe Application Context Binding (Memory leak avoidance)
    // Binds applicationContext to avoid tracking Activity lifecycle contexts.
    private val appContext = context.applicationContext

    // Local Database Store Instantiation (Sub-dependency initializer)
    // Directly instantiates LibraryRootStore to manage credentials and local paths.
    private val rootStore = LibraryRootStore(appContext)

    // Private Sync Exception Handler (Task crash safety block)
    // Captures background exception states to avoid uncaught background thread failures.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("scanService coroutine failure", exception)
    }

    // Background Ingestion Coroutine Scope (Resource-isolated execution pool)
    // Manages background tasks on the IO dispatch thread pool to prevent blocking main routines.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Serial Scan Exclusion Mutex (Database transaction isolation)
    // Excludes concurrent scanner invocations to prevent SQLite concurrent lock and transaction errors.
    private val scanMutex = Mutex()

    override suspend fun syncLibrary(trigger: String): Unit = scanMutex.withLock {
        // Protected Sync Block (Mutex-wrapped scanner execution)
        // Invokes actual library sync operations within the scanMutex execution block.
        runSyncLibrary(trigger)
    }

    override fun scheduleLibrarySync(trigger: String) {
        // Asynchronous Ingest Invoker (WorkManager dispatch bridge)
        // Dispatches a non-blocking job to run the scanner asynchronously inside the private scope.
        scope.launch {
            syncLibrary(trigger)
        }
    }

    /**
     * Core Sync Execution (Metadata synchronization and recovery)
     * Dispatches reachability checks, scans folders, and launches cover self-healing procedures.
     */
    private suspend fun runSyncLibrary(trigger: String) = withContext(Dispatchers.IO) {
        // 1. Validate and refresh permission/connection states for SAF and WebDAV.
        rootStore.refreshPermissionStatuses()
        
        // 2. Classify rescan type (shallow/light for COLD_START, deep for active USER request).
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            RescanType.COLD_START_LIGHT
        } else {
            RescanType.USER_GLOBAL
        }

        // 3. Rescan files utilizing ScanSessionRunner.
        // Injects cover recovery hooks to repair missing images during database ingestion, passing vfsFileInterface.
        val session = ScanSessionRunner(
            context = appContext,
            vfsFileInterface = vfsFileInterface,
            triggerCoverRegeneration = coverRecoveryHelper::checkAndTriggerCoverRegeneration
        ).rescan(type)

        ScanWorkflowLogger.info("scanService success: trigger=$trigger, discovered=${session.discoveredBookCount}, pending=${session.pendingActionCount}")

        // Broadcast Scan Completion (Decoupled UI notification bus)
        // Emits toast commands through PlaybackManager's event stream.
        // Replaces legacy hardcoded Toast calls inside business services to maintain clean domain boundaries.
        if (session.pendingActionCount == 0) {
            val isLibraryEmpty = com.viel.aplayer.data.db.AppDatabase.getInstance(appContext).bookDao().getAllBooksOnce().isEmpty()
            val message = if (session.discoveredBookCount > 0) {
                "媒体库同步已完成，新增了 ${session.discoveredBookCount} 本书籍"
            } else if (isLibraryEmpty) {
                "媒体库为空，未扫描到有效书籍"
            } else {
                "媒体库已是最新状态"
            }
            playbackManager.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast(message))
        }
    }

    override fun close() {
        // Terminate Active Sync Pipelines (Shutdown resource cleanup)
        // Cancels the private scope upon service teardown to abort active directory synchronization tasks.
        scope.cancel()
    }
}
