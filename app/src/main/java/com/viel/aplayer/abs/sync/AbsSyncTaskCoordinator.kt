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
 * 详尽的中文注释：ABS 同步应用级协调器。
 *
 * 设计目标：
 * 1. 手动同步和“添加成功后自动同步”都在应用级协程作用域中运行，不受 SettingsActivity / SettingsViewModel 销毁影响。
 * 2. ViewModel 只负责发起任务和消费结果事件，不再直接承载真正的同步生命周期。
 * 3. 同一 root 在同一时刻只允许存在一个应用级同步任务，避免重复点击或页面切换导致并发写库。
 */
class AbsSyncTaskCoordinator(
    private val libraryRootDao: LibraryRootDao,
    private val synchronizer: AbsCatalogSynchronizer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val lock = Any()
    private val runningRootIds = linkedSetOf<String>()
    private val _events = MutableSharedFlow<AbsSyncTaskResult>(extraBufferCapacity = 16)
    val events: SharedFlow<AbsSyncTaskResult> = _events.asSharedFlow()

    /**
     * 详尽的中文注释：以应用级后台任务的方式启动一次 ABS 同步。
     * 若同一 root 已经在同步中，则直接返回 false，调用方可据此给出“同步已在进行中”的提示。
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
                    _events.emit(
                        AbsSyncTaskResult(
                            rootId = rootId,
                            displayName = rootId,
                            origin = origin,
                            summary = null,
                            errorMessage = "找不到对应的 ABS 书库根"
                        )
                    )
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
            } catch (error: Exception) {
                _events.emit(
                    AbsSyncTaskResult(
                        rootId = rootId,
                        displayName = rootId,
                        origin = origin,
                        summary = null,
                        errorMessage = error.message
                    )
                )
            } finally {
                synchronized(lock) {
                    runningRootIds -= rootId
                }
            }
        }
        return true
    }
}

/**
 * 详尽的中文注释：同步来源只保留最小分类，
 * 便于 UI 在展示结果时区分这是“用户手动触发”还是“添加服务器后的自动后台同步”。
 */
enum class AbsSyncTaskOrigin {
    MANUAL,
    AUTO_ADD
}

/**
 * 详尽的中文注释：应用级同步任务结束后抛给 UI 层的轻量结果事件。
 * 如果 summary 非空，则说明同步实际完成；如果 errorMessage 非空，则说明任务中途失败。
 */
data class AbsSyncTaskResult(
    val rootId: String,
    val displayName: String,
    val origin: AbsSyncTaskOrigin,
    val summary: AbsSyncSummary?,
    val errorMessage: String?
)
