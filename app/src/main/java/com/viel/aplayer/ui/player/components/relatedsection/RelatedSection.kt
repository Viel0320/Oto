package com.viel.aplayer.ui.player.components.relatedsection

import com.viel.aplayer.data.entity.BookWithProgress

/**
 * 关联书籍板块的数据模型。
 * 用于在详情页展示“同作者”或“同播讲人”的书籍列表。
 */
data class RelatedSection(
    /** 板块名称（通常是作者名或播讲人名） */
    val name: String,
    /** 该板块下的书籍列表 */
    val books: List<BookWithProgress>
)