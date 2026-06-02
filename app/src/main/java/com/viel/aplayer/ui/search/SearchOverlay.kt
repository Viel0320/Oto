package com.viel.aplayer.ui.search

// 详尽的中文注释：导入生命周期感知的 collectAsStateWithLifecycle 扩展函数以替代原有的 collectAsState，
// 从而确保在 Activity 处于后台 STOPPED 状态时能自动阻断数据监听，防范数据库被无端重复计算
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 全新设计的同 Activity 内非独立搜索悬浮层（Stateful Overlay）。
 *
 * 包裹在带有垂直滑入滑出以及优雅渐显渐隐动画的 AnimatedVisibility 中。
 * 能够直接与底部的主页共享同一个 appBackdrop 采样源，实现防穿帮、极致 premium 的毛玻璃视效。
 */
@Composable
fun SearchOverlay(
    searchViewModel: SearchViewModel,
    backdrop: LayerBackdrop?,
    glassEffectMode: GlassEffectMode,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 详尽的中文注释：使用具备生命周期感知特性的 collectAsStateWithLifecycle() 收集悬浮层的可见性状态，
    // 保证在应用推入后台（进入 STOPPED 状态）时能自动切断多余的数据监听以保存电量
    val isVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()

    // 详尽的中文注释：当全局设置开启了 miuix-blur 模式时，我们将搜索悬浮层的展开与隐藏动画限制为“纯淡入淡出 (fadeIn/fadeOut)”。
    // 这能有效规避高斯模糊采样图层在高速滑入/滑出时可能产生的边缘裁剪或渲染闪烁，令磨砂玻璃的显隐动画更加极致、 premium 和平滑；
    // 在常规非 miuix-blur 模式下，则继续沿用原生的“滑入滑出 + 淡入淡出”丰富过渡效果。
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    AnimatedVisibility(
        visible = isVisible,
        enter = if (isBlur) {
            fadeIn(animationSpec = tween(400))
        } else {
            slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400))
        },
        exit = if (isBlur) {
            fadeOut(animationSpec = tween(400))
        } else {
            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400))
        },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            // 详尽的中文注释：如果开启了新命名的 MiuixBlur，将外层 Surface 容器背景置为透明，使渲染引擎可以透出下方底层内容以实现透光
            color = if (glassEffectMode == GlassEffectMode.MiuixBlur) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            SearchScreen(
                onBack = { searchViewModel.setVisible(false) },
                onNavigateToDetail = onNavigateToDetail,
                onLoadBook = onLoadBook,
                onNavigateToPlayer = onNavigateToPlayer,
                viewModel = searchViewModel,
                backdrop = backdrop,
                glassEffectMode = glassEffectMode
            )
        }
    }
}

/**
 * 搜索容器的有状态中转适配器（Stateful Screen）。
 * 
 * 负责从 SearchViewModel 中收集输入的文本状态 query、搜索的历史记录 searchHistory 以及搜索的结果 searchResults，
 * 随后无损、干净地将其透传给专门负责 UI 绘制的无状态展示组件 SearchContent。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    viewModel: SearchViewModel,
    backdrop: LayerBackdrop?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier
) {
    // 详尽的中文注释：统一改用生命周期感知的 collectAsStateWithLifecycle() 从 ViewModel 收集 query、searchResults 与 searchHistory。
    // 这能让应用被 STOPPED（置于后台）时立刻暂停对数据库的 flatMapLatest 和 combine 多路流的高频重算运算，
    // 从而大幅度优化后台情况下的能耗和内存，达到与全局其余流收集组件的架构一致性
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()

    // 详尽中文注释：将收集的各个业务数据和回调函数，完全无状态地透传给从物理文件解耦出去的无状态 UI 页面。
    SearchContent(
        query = query,
        searchResults = searchResults,
        searchHistory = searchHistory,
        commandSuggestions = commandSuggestionsFor(query),
        onQueryChange = { viewModel.onQueryChange(it) },
        onSearch = { viewModel.search(it) },
        onClearQuery = { viewModel.clearQuery() },
        onDeleteHistory = { viewModel.deleteHistory(it) },
        onClearHistory = { viewModel.clearHistory() },
        onBack = onBack,
        onNavigateToDetail = { id ->
            viewModel.saveSearchHistory(query.text)
            onNavigateToDetail(id)
        },
        onLoadBook = onLoadBook,
        onNavigateToPlayer = onNavigateToPlayer,
        autoFocus = true,
        backdrop = backdrop,
        glassEffectMode = glassEffectMode,
        modifier = modifier
    )
}
