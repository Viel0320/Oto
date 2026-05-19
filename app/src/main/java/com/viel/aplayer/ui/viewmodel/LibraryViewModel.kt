package com.viel.aplayer.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.BookWithProgress
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.ScanSessionEntity
import com.viel.aplayer.ui.state.DetailUiState
import com.viel.aplayer.ui.screens.HomeFilter
import com.viel.aplayer.ui.state.LibraryUiState
import com.viel.aplayer.util.image.ImageProcessor
import com.viel.aplayer.worker.LibrarySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    private val repository = container.libraryRepository
    private val settingsRepository = container.settingsRepository
    private val workManager = WorkManager.getInstance(application)

    private val _scanResultDialogState = MutableStateFlow<ScanSessionEntity?>(null)
    val scanResultDialogState: StateFlow<ScanSessionEntity?> = _scanResultDialogState.asStateFlow()

    private var lastCompletedSessionId: String? = null

    private val _detailUiState = MutableStateFlow(DetailUiState())
    val detailUiState: StateFlow<DetailUiState> = _detailUiState.asStateFlow()

    private val _selectedFilter = MutableStateFlow(HomeFilter.NotStarted)

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _selectedFilter.value = try {
                    HomeFilter.valueOf(settings.homeFilter)
                } catch (_: Exception) {
                    HomeFilter.NotStarted
                }
            }
        }
    }

    private var isFirstLoad = true

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        repository.audiobooks,
        _selectedFilter
    ) { audiobooks, filter ->
        var activeFilter = filter
        if (isFirstLoad && audiobooks.isNotEmpty()) {
            isFirstLoad = false
            // Restore first-load filter selection independently from scan dialog handling.
            activeFilter = if (audiobooks.any { it.isInProgress }) {
                HomeFilter.InProgress
            } else {
                HomeFilter.NotStarted
            }

            // Persist the auto-selected filter so the saved setting matches the visible home filter.
            if (activeFilter != filter) {
                _selectedFilter.value = activeFilter
                viewModelScope.launch {
                    settingsRepository.updateHomeFilter(activeFilter.name)
                }
            }
        }
        LibraryUiState(audiobooks = audiobooks, selectedFilter = activeFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(selectedFilter = _selectedFilter.value)
    )

    init {
        viewModelScope.launch {
            // Startup refreshes root permission status before the cold-start light scan reads active roots.
            repository.refreshLibraryRootStatuses()
            enqueueLibrarySync("COLD_START")
        }
        observeScanSessions()
    }

    private fun observeScanSessions() {
        viewModelScope.launch {
            repository.observeLatestScanSession().collect { session ->
                if (session != null && session.id != lastCompletedSessionId) {
                    // Only pending scan actions require user review, so scans without new pending items stay silent.
                    if (session.pendingActionCount > 0) {
                        _scanResultDialogState.value = session
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
            val book = repository.getBookById(bookId)
            // Book no longer has a sourceUri; check the first AUDIO BookFile instead.
            val fileExists = repository.getPrimaryAudioUri(bookId)?.let { repository.checkFileExists(it) } ?: false

            if (_detailUiState.value.book?.book?.id == bookId) {
                _detailUiState.update { it.copy(isVisible = false, book = null) }
            }
            repository.deleteBook(bookId)

            // 发送状态通知，说明删除书籍记录时是否仍保留源文件。
            val fileStatus = if (fileExists) "源文件已保留" else "源文件已丢失或不存在"
            val message = "书籍已从媒体库移除\n$fileStatus"
            
            // 使用 Spannable 实现 Toast 文本居中显示。
            val spannable = SpannableString(message)
            spannable.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                0,
                message.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            Toast.makeText(
                getApplication<Application>(),
                spannable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun setFilter(filter: HomeFilter) {
        _selectedFilter.value = filter
        viewModelScope.launch {
            settingsRepository.updateHomeFilter(filter.name)
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // Root insertion is awaited so the following scan can see the selected root immediately.
                repository.setLibraryRoot(uri)
                enqueueLibrarySync("USER")
            } catch (_: SecurityException) {
                // Keep import flow non-blocking if the user selects an inaccessible folder.
            }
        }
    }

    fun selectDetailBook(book: BookWithProgress?) {
        val current = _detailUiState.value
        
        // 1. 只要选中有效书籍，就显示详情页；同一本书重复选择也刷新详情状态。
        if (book != null) {
            _detailUiState.value = current.copy(
                book = book,
                isVisible = true,
                // Filled asynchronously from BookFile(AUDIO); Book itself is no longer a file identity.
                isAvailable = false,
                progressPercent = book.progressPercent // 显式初始化数据库中的阅读进度。
            )
            viewModelScope.launch {
                // Detail availability persists BookFile and Book status for the selected book only.
                val isAvailable = repository.checkDetailAvailability(book.book.id)
                _detailUiState.update { state ->
                    state.copy(isAvailable = isAvailable)
                }
            }
        } else {
            _detailUiState.value = current.copy(isVisible = false)
        }
        
        // 2. 封面主色提取逻辑，只在封面变化或尚未缓存颜色时执行。
        if (book != null && (book.book.coverPath != current.book?.book?.coverPath || current.backgroundColorArgb == ImageProcessor.DEFAULT_BACKGROUND_ARGB)) {
            viewModelScope.launch(Dispatchers.Default) {
                // 优先使用数据库中已经缓存的主色调，避免重复解析封面。
                val cachedColor = repository.getBookById(book.book.id)?.backgroundColorArgb
                val backgroundColor = cachedColor ?: ImageProcessor.getDominantColor(book.book.coverPath)
                _detailUiState.value = _detailUiState.value.copy(backgroundColorArgb = backgroundColor)
                
                // 如果本次新提取了主色调，则写回数据库供下次复用。
                if (cachedColor == null) {
                    repository.updateBackgroundColor(book.book.id, backgroundColor)
                }
            }
        }
    }

    fun setDetailVisible(visible: Boolean) {
        _detailUiState.update { it.copy(isVisible = visible) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun triggerRescan() {
        enqueueLibrarySync("USER")
    }

    private fun enqueueLibrarySync(trigger: String = "USER") {
        val syncRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setInputData(androidx.work.Data.Builder().putString("trigger", trigger).build())
            .build()
        workManager.enqueueUniqueWork(
            "LibrarySync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}
