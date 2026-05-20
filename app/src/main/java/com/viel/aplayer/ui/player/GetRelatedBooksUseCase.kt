package com.viel.aplayer.ui.player

import kotlinx.coroutines.flow.*
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookWithProgress

/**
 * 用例类：获取与当前书籍相关的推荐列表。
 * 包括同作者、同播讲人的书籍，以及排除当前相关人选后的最近添加书籍。
 */
class GetRelatedBooksUseCase(private val repository: LibraryRepository) {

    /**
     * 获取推荐数据流。
     * @param currentId 当前书籍 ID
     * @param author 当前书籍作者（支持逗号分隔多个）
     * @param narrator 当前书籍播讲人（支持逗号分隔多个）
     */
    operator fun invoke(
        currentId: String,
        author: String,
        narrator: String
    ): Flow<RelatedData> {
        val authorList = author.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        val narratorList = narrator.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // 1. 作者板块流
        val authorFlows = authorList.map { name ->
            repository.filterByAuthorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books)
            }
        }

        // 2. 播讲人板块流
        val narratorFlows = narratorList.map { name ->
            repository.filterByNarratorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books)
            }
        }

        // 3. 最近添加流（排除当前作者和播讲人）
        val recentFlow = repository.getRecentlyAddedExclusive(currentId, authorList, narratorList, 3)

        // 聚合所有流
        return combine(
            if (authorFlows.isEmpty()) flowOf(emptyList()) else combine(authorFlows) { it.toList() },
            if (narratorFlows.isEmpty()) flowOf(emptyList()) else combine(narratorFlows) { it.toList() },
            recentFlow
        ) { authors, narrators, recent ->
            RelatedData(authors, narrators, recent)
        }
    }
}

/**
 * 聚合后的关联书籍数据模型。
 */
data class RelatedData(
    val authorSections: List<RelatedSection>,
    val narratorSections: List<RelatedSection>,
    val recentlyAdded: List<BookWithProgress>
)