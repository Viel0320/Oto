package com.viel.aplayer.ui.edit

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 编辑书籍元数据的轻量规范化 ViewModel。
 * 其生命周期已成功与 EditBookActivity 解耦，现在挂载于主 App 的 Activity 级作用域中。
 */
class EditBookViewModel(application: Application) : AndroidViewModel(application) {
    // 在 M6 物理收口中，将 EditBookViewModel 中对旧仓库的依赖彻底移去，替换为引入高层业务门面 libraryFacade。
    private val libraryFacade = (application as APlayerApplication).container.libraryFacade

    private val _bookState = MutableStateFlow<BookEntity?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    /**
     * 启动编辑书籍流程。触发异步读取数据，并将悬浮 Overlay 的可见状态设为 true。
     */
    fun startEdit(bookId: String) {
        loadBook(bookId)
        setVisible(true)
    }

    /**
     * 控制编辑 Overlay 悬浮层的显隐。
     * 当关闭悬浮层时，主动将 bookState 置为空，彻底清除脏数据缓存，防止下一次拉起时发生界面闪烁。
     */
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
        if (!visible) {
            _bookState.value = null
        }
    }

    /**
     * 根据书籍 ID 异步加载单本图书的底层 Room 实体记录
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            // 使用 libraryFacade 高层门面异步获取书籍详情信息
            _bookState.value = libraryFacade.getBookById(bookId)
        }
    }

    /**
     * 将编辑好的全新元数据以及用户手动上传裁剪后的自定义封面路径异步保存并持久化回数据库。
     * @param newCoverPath 用户手动裁剪生成的临时封面文件绝对物理路径，如果为 null 则表示未修改封面
     * @param onComplete 保存成功并持久化后的回调，一般用于关闭悬浮 Overlay
     */
    fun saveBook(
        title: String,
        author: String,
        narrator: String,
        year: String,
        description: String,
        newCoverPath: String?,
        onComplete: () -> Unit
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            // 使用 libraryFacade 异步持久化修改后的文本元数据字段到 Room
            libraryFacade.updateBookDetails(
                id = currentBook.id,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                description = description.trim(),
                year = year.trim()
            )
            // 如果更换了封面，调用 libraryFacade 级联存储以物理清理老封面残余并重刷自愈封面
            if (newCoverPath != null) {
                libraryFacade.saveCustomCover(currentBook.id, newCoverPath)
            }
            onComplete()
        }
    }
}

/**
 * 有状态的 Composable 书籍编辑悬浮层包裹组件（Stateful Overlay）。
 * 该组件负责承载并管理所有的业务生命周期及状态订阅逻辑，
 * 从 ViewModel 中按需收集 `isVisible` 与 `bookState` 数据，
 * 并在平滑进退场动画内将数据与操作以 Lambda 回调形式完整传递给底层的无状态渲染组件 `EditBookScreen`。
 *
 * @param editViewModel 关联的轻量书籍编辑 ViewModel 实例
 * @param glassEffectMode 系统当前的玻璃毛玻璃特效配置模式
 * @param modifier 外部修饰词
 * @param backdrop 来自详情页渲染出来的专属模糊采样源
 * @param onSaveSuccess 书籍成功保存后的宿主级事件回调
 */
@Composable
fun EditBookOverlay(
    editViewModel: EditBookViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // 增加 detailBackdrop 参数，接收来自详情页渲染出来的模糊采样源
    backdrop: LayerBackdrop? = null,
    onSaveSuccess: () -> Unit = {}
) {
    val isVisible by editViewModel.isVisible.collectAsStateWithLifecycle()
    // 在此处高品质订阅有状态数据模型 bookState，向下透传，贯彻单向数据流与关注点分离
    val book by editViewModel.bookState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        // 调用已被彻底解耦抽离的无状态 EditBookScreen UI 组件
        EditBookScreen(
            book = book,
            onNavigationBack = { editViewModel.setVisible(false) },
            onSave = { title, author, narrator, year, description, newCoverPath ->
                editViewModel.saveBook(
                    title = title,
                    author = author,
                    narrator = narrator,
                    year = year,
                    description = description,
                    newCoverPath = newCoverPath,
                    onComplete = {
                        onSaveSuccess()
                        editViewModel.setVisible(false)
                    }
                )
            },
            glassEffectMode = glassEffectMode,
            detailBackdrop = backdrop
        )
    }
}
