package com.viel.aplayer.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.parse.ImageProcessor

/**
 * 详尽的中文注释：书籍详情页的 ViewModel。
 * 负责管理详情页的 UI 状态，包括选中书籍展示、文件可用性检查、封面主色提取等。
 * 从 LibraryViewModel 中独立出来，使各 ViewModel 职责单一、边界清晰。
 */
class DetailViewModel(application: Application) : AndroidViewModel(application) {
    // 详尽的中文注释：通过 Application 容器获取数据仓库依赖
    private val repository = (application as APlayerApplication).container.libraryRepository

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /**
     * 详尽的中文注释：选中一本书并展示详情页。
     * 立即设置基础信息和可见性，然后异步检查文件可用性和提取封面主色。
     *
     * @param book 选中的书籍实体，传入 null 表示关闭详情页。
     */
    fun selectBook(book: BookWithProgress?) {
        val current = _uiState.value

        // 详尽的中文注释：只要选中有效书籍，就显示详情页；同一本书重复选择也刷新详情状态。
        if (book != null) {
            _uiState.value = current.copy(
                book = book,
                isVisible = true,
                // 详尽的中文注释：异步检查文件可用性前先置为 false，避免闪烁
                isAvailable = false,
                progressPercent = book.progressPercent
            )
            viewModelScope.launch {
                // 详尽的中文注释：异步检查当前选中书籍的物理文件可用性
                val isAvailable = repository.checkDetailAvailability(book.book.id)
                _uiState.update { state ->
                    state.copy(isAvailable = isAvailable)
                }
            }
        } else {
            _uiState.value = current.copy(isVisible = false)
        }

        // 详尽的中文注释：封面主色提取逻辑，只在封面路径变化或尚未缓存颜色时执行。
        // 优先使用数据库中已缓存的主色调，避免重复解析封面图片。
        if (book != null && (book.book.coverPath != current.book?.book?.coverPath
                    || current.backgroundColorArgb == ImageProcessor.DEFAULT_BACKGROUND_ARGB)) {
            viewModelScope.launch(Dispatchers.Default) {
                val cachedColor = repository.getBookById(book.book.id)?.backgroundColorArgb
                val backgroundColor = cachedColor ?: ImageProcessor.getDominantColor(book.book.coverPath)
                _uiState.value = _uiState.value.copy(backgroundColorArgb = backgroundColor)

                // 详尽的中文注释：如果本次新提取了主色调，则写回数据库供下次复用
                if (cachedColor == null) {
                    repository.updateBackgroundColor(book.book.id, backgroundColor)
                }
            }
        }
    }

    /**
     * 详尽的中文注释：设置详情页的可见性。
     * 传入 false 关闭详情页（不清空书籍数据，以支持退场动画）。
     */
    fun setVisible(visible: Boolean) {
        _uiState.update { it.copy(isVisible = visible) }
    }

    /**
     * 详尽的中文注释：当指定书籍被删除时，若详情页正在展示该书则自动关闭并清空数据。
     * 供外层协调器（如 APlayerApp 的 onDeleteBook 回调）在删除书籍时统一调用。
     */
    fun dismissIfShowing(bookId: String) {
        if (_uiState.value.book?.book?.id == bookId) {
            _uiState.update { it.copy(isVisible = false, book = null) }
        }
    }
}
