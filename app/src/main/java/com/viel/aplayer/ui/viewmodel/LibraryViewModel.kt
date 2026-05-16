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
import com.viel.aplayer.ui.state.HomeFilter
import com.viel.aplayer.ui.state.LibraryUiState
import com.viel.aplayer.ui.utils.extractCoverDominantColorArgb
import com.viel.aplayer.worker.LibrarySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
        // 仅在书籍改变或进度改变时更新状态
        if (current.book?.uri != book?.uri || current.book?.lastPosition != book?.lastPosition) {
            _detailUiState.value = current.copy(
                book = book,
                isAvailable = book?.let { repository.checkFileExists(it.uri) } ?: false,
                progressPercent = book?.progressPercent ?: 0
            )
        }

        // 仅在封面路径改变时重新提取颜色，避免频繁重置背景色
        if (book?.coverPath != current.book?.coverPath || (book != null && current.backgroundColorArgb == 0)) {
            viewModelScope.launch(Dispatchers.Default) {
                val backgroundColor = extractCoverDominantColorArgb(book?.coverPath)
                _detailUiState.value = _detailUiState.value.copy(backgroundColorArgb = backgroundColor)
            }
        }
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
