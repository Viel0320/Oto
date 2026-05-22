package com.viel.aplayer.ui.edit

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.HazePresets
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 为每一次改动添加详尽的中文注释：
 * 编辑书籍元数据的轻量规范化 ViewModel。
 * 其生命周期已成功与 EditBookActivity 解耦，现在挂载于主 App 的 Activity 级作用域中。
 */
class EditBookViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as APlayerApplication).container.libraryRepository

    private val _bookState = MutableStateFlow<BookEntity?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    /**
     * 为每一次改动添加详尽的中文注释：
     * 启动编辑书籍流程。触发异步读取数据，并将悬浮 Overlay 的可见状态设为 true。
     */
    fun startEdit(bookId: String) {
        loadBook(bookId)
        setVisible(true)
    }

    /**
     * 为每一次改动添加详尽的中文注释：
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
     * 为每一次改动添加详尽的中文注释：根据书籍 ID 异步加载单本图书的底层 Room 实体记录
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _bookState.value = repository.getBookById(bookId)
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：将编辑好的全新元数据异步保存并持久化回数据库。
     * @param onComplete 保存成功并持久化后的回调，一般用于关闭悬浮 Overlay
     */
    fun saveBook(
        title: String,
        author: String,
        narrator: String,
        year: String,
        description: String,
        onComplete: () -> Unit
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            repository.updateBookDetails(
                id = currentBook.id,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                description = description.trim(),
                year = year.trim()
            )
            onComplete()
        }
    }
}

/**
 * 为每一次改动添加详尽的中文注释：
 * 新建的 Composable 书籍编辑悬浮层包裹组件。
 * 替代原本独立的 EditBookActivity，集成平滑的垂直滑入滑出进退场动画。
 */
@Composable
fun EditBookOverlay(
    editViewModel: EditBookViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // 为每一次改动添加详尽的中文注释：增加 detailHazeState 参数，接收来自详情页渲染出来的模糊采样源
    hazeState: HazeState? = null,
    onSaveSuccess: () -> Unit = {}
) {
    val isVisible by editViewModel.isVisible.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        EditBookScreen(
            viewModel = editViewModel,
            onNavigationBack = { editViewModel.setVisible(false) },
            onSaveSuccess = {
                onSaveSuccess()
                editViewModel.setVisible(false)
            },
            glassEffectMode = glassEffectMode,
            detailHazeState = hazeState
        )
    }
}

/**
 * 为每一次改动添加详尽的中文注释：
 * 书籍编辑屏幕的主渲染 Composable 视图。
 * 支持磨砂玻璃视效以及向原生 Material 3 不透明样式的高性能无损回退。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class
)
@Composable
fun EditBookScreen(
    viewModel: EditBookViewModel,
    onNavigationBack: () -> Unit,
    onSaveSuccess: () -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    detailHazeState: HazeState? = null
) {
    val book by viewModel.bookState.collectAsStateWithLifecycle()

    // 为每一次改动添加详尽的中文注释：接管并拦截系统预测性返回手势事件，提供安全的返回退出
    androidx.activity.compose.PredictiveBackHandler(enabled = book != null) { progressFlow ->
        try {
            progressFlow.collect { /* 可选用于后续扩展更精细的平移反馈，此处直接进行手势关闭 */ }
            onNavigationBack()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 用户中途取消
        }
    }

    // 为每一次改动添加详尽的中文注释：判断是否开启 Haze 效果且存在有效的背景模糊采样源（即 Detail 页面）
    val isHaze = glassEffectMode == GlassEffectMode.Haze && detailHazeState != null

    val animatedBgColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background

    // 为每一次改动添加详尽的中文注释：定义背景调色层，Haze 模式下采用高透光的半透明色以便看清底部的详情页折射
    val backgroundBrush = remember(animatedBgColor, bgColor, isHaze) {
        if (isHaze) {
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.35f),
                    bgColor.copy(alpha = 0.5f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.9f),
                    bgColor.copy(alpha = 0.95f)
                )
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isHaze) {
                    // 为每一次改动添加详尽的中文注释：采用全局公共模板 HazePresets.HazeStyle 确立“高雅白羽雾化”高密度毛玻璃基底，并在背后采样底层的 detailHazeState
                    Modifier.hazeEffect(state = detailHazeState, style = HazePresets.HazeStyle)
                } else {
                    Modifier
                }
            )
            .background(if (isHaze) Color.Transparent else MaterialTheme.colorScheme.background),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "修改书籍信息",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigationBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                val currentBook = book
                if (currentBook == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 为每一次改动添加详尽的中文注释：高频输入框状态隔离，完全避免 ViewModel 高频刷新开销
                    var title by remember(currentBook) { mutableStateOf(currentBook.title) }
                    var author by remember(currentBook) { mutableStateOf(currentBook.author) }
                    var narrator by remember(currentBook) { mutableStateOf(currentBook.narrator) }
                    var year by remember(currentBook) { mutableStateOf(currentBook.year) }
                    var description by remember(currentBook) { mutableStateOf(currentBook.description) }

                    // 为每一次改动添加详尽的中文注释：在 Haze 磨砂玻璃下，配置输入框背景为轻微半透融合色，极大增强透光的质感层级
                    val textFieldColors = if (isHaze) {
                        OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        val coverPath = currentBook.coverPath
                        if (!coverPath.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 4.dp
                            ) {
                                // 为每一次改动添加详尽的中文注释：防 physical Enoent物理文件丢失导致崩溃，使用 Coil 进行安全的异步读取
                                AsyncImage(
                                    model = File(coverPath),
                                    contentDescription = "书籍封面",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 书名输入框
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("书名") },
                            placeholder = { Text("请输入书名") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 作者输入框
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text("作者") },
                            placeholder = { Text("请输入作者") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 讲述人输入框
                        OutlinedTextField(
                            value = narrator,
                            onValueChange = { narrator = it },
                            label = { Text("讲述人") },
                            placeholder = { Text("请输入讲述人") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 年份输入框
                        OutlinedTextField(
                            value = year,
                            onValueChange = { year = it },
                            label = { Text("年份") },
                            placeholder = { Text("请输入出版年份") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 简介描述输入框
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("简介描述") },
                            placeholder = { Text("请输入书籍简介") },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 保存修改按钮
                        if (isHaze) {
                            // 为每一次改动添加详尽的中文注释：
                            // 在 Haze 模式下，将保存按钮重构为与详情页播放按钮同样高品质的磨砂玻璃 Surface。
                            // 共享底部的 detailHazeState，底色采用半透主色 (0.12f) 并用 1.dp 精细主色边框勾勒 (0.25f)。
                            Surface(
                                onClick = {
                                    if (title.isBlank()) {
                                        title = "Unknown"
                                    }
                                    viewModel.saveBook(
                                        title = title,
                                        author = author,
                                        narrator = narrator,
                                        year = year,
                                        description = description,
                                        onComplete = onSaveSuccess
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    // 为每一次改动添加详尽的中文注释：在此处引入全局的 HazePresets.HazeStyle 磨砂材质，使保存按钮与整个编辑页面的白羽雾化透光质感达到高度和谐与视觉统一
                                    .hazeEffect(state = detailHazeState, style = HazePresets.HazeStyle),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                ),
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = "保存"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            // 为每一次改动添加详尽的中文注释：
                            // 在 Material 模式下优雅无缝回退到高能效、不带高斯模糊的 Material 3 实色 Button。
                            Button(
                                onClick = {
                                    if (title.isBlank()) {
                                        title = "Unknown"
                                    }
                                    viewModel.saveBook(
                                        title = title,
                                        author = author,
                                        narrator = narrator,
                                        year = year,
                                        description = description,
                                        onComplete = onSaveSuccess
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = "保存",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
