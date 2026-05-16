package com.viel.aplayer.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.data.LibraryRepository
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
    private val repository = LibraryRepository.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    private val _detailUiState = MutableStateFlow(DetailUiState())
    val detailUiState: StateFlow<DetailUiState> = _detailUiState.asStateFlow()

    private val _selectedFilter = MutableStateFlow(
        try {
            HomeFilter.valueOf(repository.getHomeFilter())
        } catch (_: Exception) {
            HomeFilter.NotStarted
        }
    )

    private var isFirstLoad = true

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        repository.audiobooks,
        _selectedFilter
    ) { audiobooks, filter ->
        var activeFilter = filter
        if (isFirstLoad) {
            isFirstLoad = false
            // 首次载入逻辑：优先进入 InProgress，如果没有正在阅读的书，则进入 NotStarted
            activeFilter = if (audiobooks.any { it.isInProgress }) {
                HomeFilter.InProgress
            } else {
                HomeFilter.NotStarted
            }

            // 同步保存状态，确保数据一致性
            if (activeFilter != filter) {
                _selectedFilter.value = activeFilter
                repository.setHomeFilter(activeFilter.name)
            }
        }
        LibraryUiState(audiobooks = audiobooks, selectedFilter = activeFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(selectedFilter = _selectedFilter.value)
    )

    init {
        enqueueLibrarySync()
    }

    fun setFilter(filter: HomeFilter) {
        _selectedFilter.value = filter
        repository.setHomeFilter(filter.name)
    }

    fun onLibraryRootSelected(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            repository.setLibraryRoot(uri)
            enqueueLibrarySync()
        } catch (_: SecurityException) {
            // Keep import flow non-blocking if the user selects an inaccessible folder.
        }
    }

    fun selectDetailBook(book: AudiobookEntity?) {
        val current = _detailUiState.value
        
        // 1. 无论书籍是否变化，只要调用 selectDetailBook 且 book 不为空，就应该显示详情页
        if (book != null) {
            _detailUiState.value = current.copy(
                book = book,
                isVisible = true,
                isAvailable = repository.checkFileExists(book.uri),
                progressPercent = book.progressPercent
            )
        } else {
            _detailUiState.value = current.copy(isVisible = false)
        }

        // 2. 颜色提取逻辑（仅在必要时执行）
        if (book != null && (book.coverPath != current.book?.coverPath || current.backgroundColorArgb == ImageProcessor.DEFAULT_BACKGROUND_ARGB)) {
            viewModelScope.launch(Dispatchers.Default) {
                // 检查数据库是否已经有缓存的主色调
                val cachedColor = book?.let { repository.getByUri(it.uri)?.backgroundColorArgb }
                val backgroundColor = cachedColor ?: ImageProcessor.getDominantColor(book?.coverPath)
                _detailUiState.value = _detailUiState.value.copy(backgroundColorArgb = backgroundColor)
                
                // 如果是新提取的，存入数据库
                if (book != null && cachedColor == null) {
                    repository.updateBackgroundColor(book.uri, backgroundColor)
                }
            }
        }
    }

    fun setDetailVisible(visible: Boolean) {
        _detailUiState.update { it.copy(isVisible = visible) }
    }

    private fun enqueueLibrarySync() {
        val syncRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>().build()
        workManager.enqueueUniqueWork(
            "LibrarySync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}
