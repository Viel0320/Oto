package com.viel.aplayer.ui.home

import androidx.annotation.StringRes
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.GlassEffectMode

/**
 * 图书馆主页的 UI 状态聚合类。
 *
 * 所有数据变换（过滤、分组、排序截取）均在 ViewModel 的 Flow 管道中完成，
 * Composable 层直接消费预计算好的字段，不再承担任何业务运算。
 */
data class LibraryUiState(
    /** 书架上的所有书籍列表（原始未过滤） */
    val audiobooks: List<BookWithProgress> = emptyList(),

    /** 当前激活的过滤类型。null 表示 combine 管道尚未产出首个最终决策，UI 应暂不渲染 FilterChip 行 */
    val selectedFilter: HomeFilter? = null,

    /** 根据当前 filter 过滤后的书籍列表 */
    val filteredAudiobooks: List<BookWithProgress> = emptyList(),

    /** 过滤后的书籍按作者分组，用于 LazyColumn 按作者展示 */
    val groupedByAuthor: Map<String, List<BookWithProgress>> = emptyMap(),

    /** "最近"区域的书籍列表（NotStarted → 最近添加；InProgress → 最近播放） */
    val recentBooks: List<BookWithProgress> = emptyList(),

    /**
     * "最近"区域的标题字符串资源 ID。
     * 为 0 时表示当前 filter 不展示最近区域。
     */
    @param:StringRes val recentTitleRes: Int = 0,

    /** 是否应当展示"最近"横向滚动区域 */
    val shouldShowRecentBooks: Boolean = false,

    /** 为每一次改动添加详尽的中文注释：当前悬浮层玻璃效果模式，供主页 Dialog 与播放器 BottomSheet 共用同一全局设置。 */
    // 为每一次改动添加详尽的中文注释：LibraryUiState 初始值默认 Material，确保设置流首帧尚未到达时不主动启用 Haze。
    val glassEffectMode: GlassEffectMode = GlassEffectMode.Material
)
