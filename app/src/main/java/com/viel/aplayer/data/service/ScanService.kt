package com.viel.aplayer.data.service

import android.content.Context
import android.util.Log
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.RescanCoordinator
import com.viel.aplayer.library.RescanType
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 媒体库扫描调度应用服务（实现了 ScanScheduler 接口）。
 *
 * 核心设计目标：
 * 1. 彻底解耦消灭大上帝仓库：在 M6c 阶段直接直连配合 LibraryRootStore 与 RescanCoordinator，完全隔离并彻底废除对 BookLibraryRepository 的依赖。
 * 2. 完美平移串行同步锁：在类内部独立维护 Mutex 串行锁，并保留 COLD_START_LIGHT 与 USER_GLOBAL 扫描类型的智能研判逻辑，确保扫描安全。
 */
class ScanService(
    context: Context,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : ScanScheduler {

    // 详尽的中文注释：采用全局 applicationContext 隔离以彻底斩断潜在的内存泄漏风险
    private val appContext = context.applicationContext

    // 详尽的中文注释：直接实例化底层的 SAF 及 WebDAV 挂载库提供者组件
    private val rootStore = LibraryRootStore(appContext)

    // 详尽的中文注释：串行扫描专属后台协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("ScanService", "协程在 ScanService 运行中捕获到未处理异常", exception)
    }

    // 详尽的中文注释：专门维护在 IO 线程池中的扫描异步同步后台作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 详尽的中文注释：同步扫描的串行排他锁，杜绝前后台多任务重叠扫描引发的 Room 并发读写冲突
    private val scanMutex = Mutex()

    override suspend fun syncLibrary(trigger: String): Unit = scanMutex.withLock {
        // 详尽的中文注释：在持有串行扫描锁 the 受保护作用域中，执行同步物理文件扫描任务
        runSyncLibrary(trigger)
    }

    override fun scheduleLibrarySync(trigger: String) {
        // 详尽的中文注释：向前台或后台 WorkManager 暴露的非阻塞式扫描调度异步接口
        scope.launch {
            syncLibrary(trigger)
        }
    }

    /**
     * 详尽的中文注释：具体的书库物理文件与元数据同步核心流程，结合扫描自愈提取
     */
    private suspend fun runSyncLibrary(trigger: String) = withContext(Dispatchers.IO) {
        // 1. 同步校验并刷新所有 SAF 本地目录授权状态与 WebDAV 网络挂载连接状态
        rootStore.refreshPermissionStatuses()
        
        // 2. 区分冷启动轻量级浅同步还是用户发起的全局深度递归扫描
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            RescanType.COLD_START_LIGHT
        } else {
            RescanType.USER_GLOBAL
        }

        // 3. 构建临时扫描协调器，并挂载封面文件解析器的自愈检查回调，在书籍入库瞬间快速自检封面可达性
        val session = RescanCoordinator(
            context = appContext,
            triggerCoverRegeneration = coverRecoveryHelper::checkAndTriggerCoverRegeneration
        ).rescan(type)

        Log.i("ScanService", "书籍物理同步扫描已圆满完成. 新增: ${session.discoveredBookCount}, 待处理变动: ${session.pendingActionCount}")
    }
}
