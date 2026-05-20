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
                progressPercent = book.progressPercent,
                // 为每一次改动添加详尽的中文注释：选中新书时，立即把 fullSourcePath 重置为空，防止上一次选中的路径数据残留引起界面闪烁
                fullSourcePath = ""
            )

            // 为每一次改动添加详尽的中文注释：启动协程异步加载该有声书底层的物理文件，并根据不同的有声书源类型提取对应的源文件名，
            // 并在 ViewModel 侧直接进行 SAF URL 解码和物理路径中 "primary:" 前缀的截取与路径拼接，从而将干净的 fullSourcePath 供给 UI 渲染
            viewModelScope.launch {
                // 为每一次改动添加详尽的中文注释：改用无过滤的 getAllFilesForBookSync 接口，确保拉取到包含 SOURCE_MANIFEST 角色在内的完整物理文件列表
                val files = repository.getAllFilesForBookSync(book.book.id)
                val fileName = when (book.book.sourceType) {
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.SINGLE_AUDIO -> {
                        // 为每一次改动添加详尽的中文注释：单音频文件，文件名（带拓展名）直接取首个关联文件的 displayName
                        files.firstOrNull()?.displayName.orEmpty()
                    }
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.CUE,
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.M3U8 -> {
                        // 为每一次改动添加详尽的中文注释：清单有声书优先筛选角色为 SOURCE_MANIFEST 的文件（清单本身），不存在则用首轨文件名做兜底
                        files.firstOrNull { it.fileRole == com.viel.aplayer.data.db.AudiobookSchema.FileRole.SOURCE_MANIFEST }?.displayName
                            ?: files.firstOrNull()?.displayName.orEmpty()
                    }
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.GENERATED_M3U8 -> {
                        // 为每一次改动添加详尽的中文注释：启发式聚合书籍，按照 index 升序排序后，获取第一章（首轨）的音频文件名作为源文件名
                        files.sortedBy { it.index }.firstOrNull()?.displayName.orEmpty()
                    }
                    else -> ""
                }

                // 为每一次改动添加详尽的中文注释：对有声书的 sourceRoot 进行 URL 解码，还原 %3A 与 %2F
                val root = book.book.sourceRoot
                val decodedRoot = android.net.Uri.decode(root)

                // 为每一次改动添加详尽的中文注释：使用 lastIndexOf("primary:") 寻找最后一个 "primary:" 关键字并截取其后的真实物理目录段。
                // 因为在 SAF 授权树模式下，物理 Uri 可能同时包含授权树与具体文件前缀（从而出现两个 primary: 关键字，例如 "Audiobooks/document/primary:Audiobooks"），
                // 采用 lastIndexOf 能够极其智能、彻底地将 "Audiobooks/document/primary:" 这样无关的多重冗余协议前缀全部剥除，只保留真实的 "Audiobooks" 物理目录，完美满足用户的精炼路径诉求
                val primaryKey = "primary:"
                val startIndex = decodedRoot.lastIndexOf(primaryKey, ignoreCase = true)
                val cleanRoot = if (startIndex != -1) {
                    decodedRoot.substring(startIndex + primaryKey.length)
                } else {
                    decodedRoot
                }

                // 为每一次改动添加详尽的中文注释：将干净的物理根目录与匹配识别出的文件名进行高可靠性路径拼接
                val finalPath = if (cleanRoot.endsWith("/")) {
                    "$cleanRoot$fileName"
                } else if (cleanRoot.isNotEmpty() && fileName.isNotEmpty()) {
                    "$cleanRoot/$fileName"
                } else {
                    "$cleanRoot$fileName"
                }

                _uiState.update { state ->
                    state.copy(fullSourcePath = finalPath)
                }
            }

            viewModelScope.launch {
                // 详尽的中文注释：异步检查当前选中书籍的物理文件可用性
                val isAvailable = repository.checkDetailAvailability(book.book.id)
                _uiState.update { state ->
                    state.copy(isAvailable = isAvailable)
                }
            }
        } else {
            // 为每一次改动添加详尽的中文注释：当关闭详情页时，将 isVisible 置为 false 且清空 fullSourcePath
            _uiState.update { state ->
                state.copy(isVisible = false, fullSourcePath = "")
            }
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
