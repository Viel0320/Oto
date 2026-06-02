package com.viel.aplayer.ui.home

// 导入全局通用的 UI 一次性反馈事件定义，用以解耦模块专有的 LibraryUiEvent
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    // 切换到高层门面 libraryFacade 以实现接口解耦与防腐
    private val libraryFacade = container.libraryFacade
    private val settingsRepository = container.settingsRepository

    /**
     * 跨域书库根目录删除协调器用例，用于替代原有的门面仓库调用，以满足解耦要求。
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase

    private val _scanResultDialogState = MutableStateFlow<ScanSessionEntity?>(null)
    val scanResultDialogState: StateFlow<ScanSessionEntity?> = _scanResultDialogState.asStateFlow()

    private var lastCompletedSessionId: String? = null
    // 记录 ViewModel 初始化启动的时间戳，用于过滤在本次启动前已完成的历史扫描会话，防止冷启动时重复弹窗与 Toast
    private val viewModelStartTime = System.currentTimeMillis()

    // 一次性 UI 事件流。重构为使用全局通用的 UiEvent 通道代替原先局部的 LibraryUiEvent，
    // ViewModel 不再操作任何模块局部的事件定义，进一步提升架构纯净度。
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // 用户手动选择的 filter，初始为 null 表示"用户尚未手动操作过"。
    // 当 null 时，combine 管道会按优先级链（持久化设置 → 首次加载自动判断 → 默认值）统一决策，
    // 避免多个异步源在冷启动时竞争修改导致 FilterChip 动画闪烁。
    private val _selectedFilter = MutableStateFlow<HomeFilter?>(null)

    private var isFirstLoad = true

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        libraryFacade.audiobooks,
        _selectedFilter,
        settingsRepository.settingsFlow
    ) { audiobooks, userSelection, appSettings ->
        // 统一的 filter 决策优先级链，所有 filter 赋值判断集中在此处完成，
        // 确保无论有多少异步数据源，combine 只在所有输入就绪后发射一次最终结果，
        // 从根本上消除冷启动时 FilterChip 的多次状态跳变和动画闪烁。
        //
        // 优先级：用户手动选择 > 首次加载自动判断 > 持久化设置 > 默认值
        val activeFilter = if (userSelection != null) {
            // 用户已手动点击 FilterChip 选择了 filter，最高优先级，直接采用
            userSelection
        } else if (isFirstLoad && audiobooks.isNotEmpty()) {
            // 首次加载且数据已就绪时，根据实际书籍状态自动判断最适合的 filter。
            // 如果有正在播放的书籍则选 InProgress，否则选 NotStarted。
            // 自动选中的结果会被持久化，使下次冷启动时通过 appSettings 恢复一致状态。
            isFirstLoad = false
            val autoFilter = if (audiobooks.any { it.isInProgress }) {
                HomeFilter.InProgress
            } else {
                HomeFilter.NotStarted
            }
            viewModelScope.launch {
                settingsRepository.updateHomeFilter(autoFilter.name)
            }
            autoFilter
        } else {
            // 使用持久化设置中的 homeFilter 值恢复上次退出时的状态。
            // 若解析失败（如持久化值无效），兜底使用 NotStarted。
            isFirstLoad = false
            try {
                HomeFilter.valueOf(appSettings.homeFilter)
            } catch (_: Exception) {
                HomeFilter.NotStarted
            }
        }

        // 以下所有数据变换（过滤、分组、排序截取）均在 ViewModel 的 Flow 管道中完成，
        // 避免在 Composable 层使用 remember 做业务运算，确保 UI 层只做纯渲染。

        // 1. 按当前 filter 过滤书籍列表
        val filteredAudiobooks = audiobooks.filter { it.matchesFilter(activeFilter) }

        // 2. 将过滤后的书籍按作者分组，用于 LazyColumn 的分组展示
        val groupedByAuthor = filteredAudiobooks.groupBy { it.book.author }

        // 3. 计算"最近"区域的书籍（NotStarted 按添加时间倒序取10；InProgress 按最后播放时间倒序取5）
        val recentBooks = when (activeFilter) {
            HomeFilter.NotStarted -> audiobooks.filter { it.isNotStarted }
                .sortedByDescending { it.book.addedAt }
                .take(10)
            HomeFilter.InProgress -> audiobooks.filter { it.isInProgress && (it.progress?.lastPlayedAt ?: 0) > 0 }
                .sortedByDescending { it.progress?.lastPlayedAt ?: 0 }
                .take(5)
            else -> emptyList()
        }

        // 4. 确定"最近"区域的标题字符串资源 ID
        val recentTitleRes = when (activeFilter) {
            HomeFilter.NotStarted -> R.string.recently_added_title
            HomeFilter.InProgress -> R.string.recently_played_title
            else -> 0
        }

        // 5. 判断是否应展示"最近"横向滚动区域
        val shouldShowRecentBooks = (activeFilter == HomeFilter.NotStarted || activeFilter == HomeFilter.InProgress) && recentBooks.isNotEmpty()

        LibraryUiState(
            audiobooks = audiobooks,
            selectedFilter = activeFilter,
            filteredAudiobooks = filteredAudiobooks,
            groupedByAuthor = groupedByAuthor,
            recentBooks = recentBooks,
            recentTitleRes = recentTitleRes,
            shouldShowRecentBooks = shouldShowRecentBooks,
            // 把全局玻璃效果模式随主页 UiState 下发，确保 Home/Dialog/Player 使用同一个持久化选择。
            glassEffectMode = appSettings.glassEffectMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        // 初始值不设置具体 filter，等待 combine 管道所有输入就绪后再一次性发射最终状态
        initialValue = LibraryUiState()
    )

    /**
     * 判断书籍是否匹配指定的过滤条件。
     * 此方法从 HomeScreen Composable 迁移至 ViewModel，确保过滤逻辑在数据层完成。
     */
    private fun BookWithProgress.matchesFilter(filter: HomeFilter): Boolean {
        return when (filter) {
            HomeFilter.NotStarted -> isNotStarted
            HomeFilter.InProgress -> isInProgress
            HomeFilter.Finished -> isFinished
        }
    }

    init {
        // 冷启动扫描直接提交至新业务门面底层，使 ViewModel 彻底与具体的 WorkManager/并发解耦
        libraryFacade.scheduleLibrarySync("COLD_START")
        observeScanSessions()
    }

    private fun observeScanSessions() {
        viewModelScope.launch {
            libraryFacade.observeLatestScanSession().collect { session ->
                if (session != null && session.id != lastCompletedSessionId) {
                    // 
                    // 只有当扫描会话的完成时间晚于本次启动时间戳时，才触发 Toast 提示或弹窗展示。
                    // 这能完美过滤掉上一次运行留存的已完成会话，消除冷启动时因 Flow 首次下发历史状态导致的“库为空提示”和“重复弹窗”。
                    val completedAt = session.completedAt ?: 0L
                    if (completedAt > viewModelStartTime) {
                        if (session.pendingActionCount > 0) {
                            _scanResultDialogState.value = session
                        } else {
                            // 
                            // 详尽的中文注释：在扫描完成回调中，通过挂起函数 first() 直接获取底层书籍 Flow 的最新实体快照；
                            // 这解决了当无 UI Composable 活跃订阅时，直接读取受 WhileSubscribed(5000) 门控的 uiState.value 只拿到底层初始空值 LibraryUiState() 从而误判空库并误报提示的严重并发 Bug
                            val currentBooks = libraryFacade.audiobooks.first()
                            val isLibraryEmpty = currentBooks.isEmpty()

                            // 
                            // 如果没有产生待处理的冲突/残缺动作（不需要弹出强制审核 Dialog），
                            // 我们提供一个极具交互感和友好温度的 Toast 提醒，将扫描已同步的结果（新书数量）明确告知用户，根治“无弹出无反馈”的痛点。
                            val message = if (session.discoveredBookCount > 0) {
                                "媒体库同步已完成，新增了 ${session.discoveredBookCount} 本书籍"
                            } else if (isLibraryEmpty) {
                                // 如果经过扫描后库中依然没有任何有效书籍，提供极其温和与指引性的提示，消灭“空库却提示最新”的尴尬体验。
                                "媒体库为空，未扫描到有效书籍"
                            } else {
                                "媒体库已是最新状态"
                            }
                            
                            // 使用通用的 UiEvent.ShowToast 发射一次性 Toast 事件，交由主 Composable 容器层消费展示。
                            _uiEvents.tryEmit(UiEvent.ShowToast(message))
                        }
                    }
                    // Remember the completed session so the same result does not reopen the dialog.
                    lastCompletedSessionId = session.id
                }
            }
        }
    }

    fun dismissScanResultDialog() {
        _scanResultDialogState.value = null
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            // 检测被删除的书籍是否是当前正在播放的书，如果是，则停止后台播放服务，切断音频输出与会话连接
            val playbackManager = com.viel.aplayer.media.PlaybackManager.getInstance(getApplication())
            val currentPlayingId = playbackManager.getCurrentBookId()
            if (currentPlayingId == bookId) {
                playbackManager.stopPlayback()
            }

            // 用新门面接口检查物理音频文件存在性并删除书籍记录
            val fileExists = libraryFacade.checkPrimaryAudioFileExists(bookId)

            libraryFacade.deleteBook(bookId)

            // 使用通用的 UiEvent.ShowToast 发射图书移除结果 Toast。
            val fileStatus = if (fileExists) "源文件已保留" else "源文件已丢失或不存在"
            val message = "书籍已从媒体库移除\n$fileStatus"
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
        }
    }

    // 更新有声书的阅读状态（未开始/进行中/已完成）到持久化数据库中，并展示高交互性轻量 Toast 提示
    fun updateBookReadStatus(bookId: String, readStatus: String) {
        viewModelScope.launch {
            // 使用新高层门面更新书籍阅读状态
            libraryFacade.updateBookReadStatus(bookId, readStatus)
            val message = when (readStatus) {
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.NOT_STARTED -> "已标记为：未开始"
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.IN_PROGRESS -> "已标记为：进行中"
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.FINISHED -> "已标记为：已完成"
                else -> "状态已更新"
            }
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
        }
    }

    // 强制触发后台协程重建有声书的封面文件与全部元数据信息，并在开始与完成时展示轻量 Toast 反馈
    fun forceRegenerateCoverAndMetadata(bookId: String) {
        viewModelScope.launch {
            _uiEvents.tryEmit(UiEvent.ShowToast("正在重建封面与元数据..."))
            // 用新高层门面深度重构封面与元数据物理缓存
            libraryFacade.forceRegenerateCoverAndMetadata(bookId)
            _uiEvents.tryEmit(UiEvent.ShowToast("封面与元数据重建已完成"))
        }
    }

    fun setFilter(filter: HomeFilter) {
        // 用户手动点击 FilterChip 时，将选择写入 _selectedFilter（非 null），
        // combine 管道检测到 userSelection != null 后会以最高优先级直接采用。
        // 同时异步持久化到 DataStore，使下次冷启动通过 settingsFlow 恢复。
        _selectedFilter.value = filter
        viewModelScope.launch {
            settingsRepository.updateHomeFilter(filter.name)
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        // 目录授权、root 入库和扫描均通过新门面在应用级完成
        libraryFacade.addLibraryRootAndScheduleSync(uri)
    }

    // 删除库根目录并释放 SAF 授权，通过专职用例协调安全停播与数据清理，然后通过 Toast 通知用户结果。
    fun deleteLibraryRoot(root: com.viel.aplayer.data.entity.LibraryRootEntity) {
        viewModelScope.launch {
            // 调用高层跨域用例执行删除，规避由 Repository 直接触发播放器停播的反向依赖问题
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root)
            val message = if (playbackWasStopped) {
                "媒体库已移除，当前播放已停止"
            } else {
                "媒体库已移除"
            }
            android.widget.Toast.makeText(
                getApplication(),
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }



    fun clearSearchHistory() {
        viewModelScope.launch {
            // 通过新门面清空检索词历史
            libraryFacade.clearHistory()
        }
    }

    fun triggerRescan() {
        // 手动触发增量重扫，提交至门面调度器队列
        libraryFacade.scheduleLibrarySync("USER")
    }
}
