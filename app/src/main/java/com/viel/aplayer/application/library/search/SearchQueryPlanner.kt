package com.viel.aplayer.application.library.search

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.gateway.BookCatalogGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Search Query Planner (Maps search text into narrow catalog query flows)
 * Moves directive parsing and multi-token intersection out of SearchViewModel so UI state stays presentation-only.
 */
class SearchQueryPlanner(
    private val audiobooks: Flow<List<BookWithProgress>>,
    private val searchAudiobooks: (String) -> Flow<List<BookWithProgress>>,
    private val filterByYear: (String) -> Flow<List<BookWithProgress>>,
    private val filterByAuthor: (String) -> Flow<List<BookWithProgress>>,
    private val filterByNarrator: (String) -> Flow<List<BookWithProgress>>
) {
    fun search(query: String): Flow<List<BookWithProgress>> {
        val tokenFlows = tokenize(query).map(::flowForToken)
        if (tokenFlows.isEmpty()) return flowOf(emptyList())
        if (tokenFlows.size == 1) return tokenFlows.first()

        return combine(tokenFlows) { lists ->
            lists.reduce { acc, list ->
                // Book Id Intersection (Preserve the existing multi-token AND semantics)
                // Search tokens are evaluated separately, then each later list is filtered by the previous token id set.
                val accIds = acc.map { it.book.id }.toSet()
                list.filter { it.book.id in accIds }
            }
        }
    }

    private fun tokenize(query: String): List<String> {
        return query.split(WHITESPACE_REGEX).filter { token -> token.isNotBlank() }
    }

    private fun flowForToken(token: String): Flow<List<BookWithProgress>> {
        val parts = token.split(":", limit = 2)
        if (parts.size != DIRECTIVE_PARTS) return searchAudiobooks(token)

        val directive = parts[0].trim().lowercase()
        val content = parts[1].trim()
        if (directive !in DIRECTIVES) return searchAudiobooks(token)

        return when (directive) {
            DIRECTIVE_YEAR -> if (content.isEmpty()) audiobooks else filterByYear(content)
            DIRECTIVE_AUTHOR, DIRECTIVE_WRITER -> if (content.isEmpty()) audiobooks else filterByAuthor(content)
            DIRECTIVE_NARRATOR -> if (content.isEmpty()) audiobooks else filterByNarrator(content)
            else -> audiobooks
        }
    }

    companion object {
        private const val DIRECTIVE_PARTS = 2
        private const val DIRECTIVE_YEAR = "year"
        private const val DIRECTIVE_AUTHOR = "author"
        private const val DIRECTIVE_WRITER = "writer"
        private const val DIRECTIVE_NARRATOR = "narrator"
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DIRECTIVES = setOf(DIRECTIVE_YEAR, DIRECTIVE_AUTHOR, DIRECTIVE_WRITER, DIRECTIVE_NARRATOR)

        /**
         * Gateway-backed Planner Factory (Adapts the catalog gateway to search-only query functions)
         *
         * Keeps LibraryGraph wiring concise while SearchViewModel receives only the finished search read model.
         */
        fun from(bookCatalogGateway: BookCatalogGateway): SearchQueryPlanner {
            return SearchQueryPlanner(
                audiobooks = bookCatalogGateway.audiobooks,
                searchAudiobooks = bookCatalogGateway::searchAudiobooks,
                filterByYear = bookCatalogGateway::filterByYear,
                filterByAuthor = bookCatalogGateway::filterByAuthor,
                filterByNarrator = bookCatalogGateway::filterByNarrator
            )
        }
    }
}
