package com.viel.aplayer.application.usecase

import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.entity.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Builds related audiobook recommendations from catalog read models.
 *
 * Heuristic recommendation sorting combines title similarity, sequence position, year,
 * narrator, author, and series signals while keeping repository row shapes behind this use case.
 */
class GetRelatedBooksUseCase(private val repository: BookCatalogGateway) {

    /**
     * Scores title similarity by finding the longest shared contiguous text segment after whitespace normalization.
     */
    private fun getLongestCommonSubstringLength(s1: String, s2: String): Int {
        if (s1.isEmpty() || s2.isEmpty()) return 0
        val str1 = s1.lowercase().replace("\\s".toRegex(), "")
        val str2 = s2.lowercase().replace("\\s".toRegex(), "")

        val dp = Array(str1.length + 1) { IntArray(str2.length + 1) }
        var maxLen = 0
        for (i in 1..str1.length) {
            for (j in 1..str2.length) {
                if (str1[i - 1] == str2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                    if (dp[i][j] > maxLen) {
                        maxLen = dp[i][j]
                    }
                } else {
                    dp[i][j] = 0
                }
            }
        }
        return maxLen
    }

    /**
     * Extracts sequence indexes from common Arabic-number and Chinese-number book title patterns.
     */
    private fun extractSequenceIndex(title: String): Double? {
        val clean = title.lowercase().replace("\\s".toRegex(), "")

        if (clean.contains("\u4E0A\u518C") || clean.endsWith("\u4E0A")) return 1.0
        if (clean.contains("\u4E2D\u518C") || clean.endsWith("\u4E2D")) return 2.0
        if (clean.contains("\u4E0B\u518C") || clean.endsWith("\u4E0B")) return 3.0

        val cnPattern = "\u7B2C([\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341]+)[\u90E8\u518C\u5377\u5B63\u7AE0\u96C6]".toRegex()
        cnPattern.find(clean)?.let { match ->
            val numStr = match.groupValues[1]
            return chineseToDecimal(numStr)
        }

        val numPattern = "\u7B2C(\\d+)[\u90E8\u518C\u5377\u5B63\u7AE0\u96C6]".toRegex()
        numPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }

        val tailNumPattern = "(\\d+)\\s*$".toRegex()
        val parenNumPattern = "\\((\\d+)\\)".toRegex()

        parenNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        tailNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }

        val anyNumPattern = "(\\d+)".toRegex()
        anyNumPattern.findAll(clean).forEach { match ->
            val num = match.groupValues[1].toDoubleOrNull()
            if (num != null && (num !in 1000.0..2200.0)) {
                return num
            }
        }

        val simpleCnNums = listOf(
            "\u4E00",
            "\u4E8C",
            "\u4E09",
            "\u56DB",
            "\u4E94",
            "\u516D",
            "\u4E03",
            "\u516B",
            "\u4E5D",
            "\u5341"
        )
        for (i in simpleCnNums.indices) {
            if (clean.contains(simpleCnNums[i])) {
                return (i + 1).toDouble()
            }
        }

        return null
    }

    /**
     * Parses compact Chinese numerals used in sequence titles into decimal values for recommendation ordering.
     */
    private fun chineseToDecimal(chinese: String): Double {
        val cnNums = mapOf(
            '\u4E00' to 1.0,
            '\u4E8C' to 2.0,
            '\u4E09' to 3.0,
            '\u56DB' to 4.0,
            '\u4E94' to 5.0,
            '\u516D' to 6.0,
            '\u4E03' to 7.0,
            '\u516B' to 8.0,
            '\u4E5D' to 9.0,
            '\u5341' to 10.0
        )
        if (chinese.length == 1) {
            return cnNums[chinese[0]] ?: 0.0
        }
        if (chinese.length == 2 && chinese[0] == '\u5341') {
            return 10.0 + (cnNums[chinese[1]] ?: 0.0)
        }
        var result = 0.0
        var temp = 0.0
        for (char in chinese) {
            val v = cnNums[char] ?: 0.0
            if (char == '\u5341') {
                result += if (temp == 0.0) 10.0 else temp * 10.0
                temp = 0.0
            } else {
                temp = v
            }
        }
        result += temp
        return result
    }

    /**
     * To stream related collections.
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

        val authorFlows = authorList.map { name ->
            repository.filterByAuthorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books.map { it.toRelatedBookCandidate() })
            }
        }

        val narratorFlows = narratorList.map { name ->
            repository.filterByNarratorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books.map { it.toRelatedBookCandidate() })
            }
        }

        val recentFlow = repository.getRecentlyAddedExclusive(currentId, authorList, narratorList, 3)
            .map { books ->
                books.map { it.toRelatedBookCandidate() }
            }

        val currentBookFlow = repository.observeBookById(currentId)
        val heuristicFlow = combine(repository.audiobooks, currentBookFlow) { allBooks, currentBook ->
            if (currentBook == null) return@combine emptyList()

            val currentTitle = currentBook.title
            val currentYear = currentBook.year
            val currentSeries = currentBook.series

            val scoredBooks = allBooks.filter { it.book.id != currentId }
                .map { item ->
                    val book = item.book
                    var score = 0.0

                    val cleanCurrentTitle = currentTitle.replace("\\s".toRegex(), "")
                    val cleanCandidateTitle = book.title.replace("\\s".toRegex(), "")
                    var isTitleMatched = false
                    if (cleanCurrentTitle.isNotBlank() && cleanCandidateTitle.isNotBlank()) {
                        when {
                            cleanCurrentTitle.equals(cleanCandidateTitle, ignoreCase = true) -> {
                                score += 40.0
                                isTitleMatched = true
                            }
                            cleanCandidateTitle.contains(cleanCurrentTitle, ignoreCase = true) ||
                            cleanCurrentTitle.contains(cleanCandidateTitle, ignoreCase = true) -> {
                                score += 30.0
                                isTitleMatched = true
                            }
                            else -> {
                                val lcsLen = getLongestCommonSubstringLength(currentTitle, book.title)
                                if (lcsLen >= 2) {
                                    score += lcsLen * 5.0
                                    isTitleMatched = true
                                }
                            }
                        }
                    }

                    if (isTitleMatched) {
                        val currentIndex = extractSequenceIndex(currentTitle)
                        val candidateIndex = extractSequenceIndex(book.title)
                        if (currentIndex != null && candidateIndex != null && candidateIndex < currentIndex) {
                            score = (score - 50.0).coerceAtLeast(1.0)
                        }
                    }

                    if (currentSeries.isNotBlank() && book.series.isNotBlank() &&
                        currentSeries.trim().equals(book.series.trim(), ignoreCase = true)) {
                        score += 35.0
                    }

                    if (authorList.isNotEmpty() && book.author.isNotBlank()) {
                        val candidateAuthors = book.author.split(",").map { it.trim() }
                        if (authorList.any { a -> candidateAuthors.any { ca -> ca.equals(a, ignoreCase = true) } }) {
                            score += 10.0
                        }
                    }

                    if (narratorList.isNotEmpty() && book.narrator.isNotBlank()) {
                        val candidateNarrators = book.narrator.split(",").map { it.trim() }
                        if (narratorList.any { n -> candidateNarrators.any { cn -> cn.equals(n, ignoreCase = true) } }) {
                            score += 8.0
                        }
                    }

                    if (currentYear.isNotBlank() && book.year.isNotBlank() && currentYear == book.year) {
                        score += 4.0
                    }

                    score += book.addedAt.toDouble() / 1e13

                    Pair(item, score)
                }

            val matchedBooks = scoredBooks.filter { it.second > 1e-5 }
                .sortedByDescending { it.second }
                .map { it.first }

            val recommendedBooks = if (matchedBooks.size < 5) {
                val matchedIds = matchedBooks.map { it.book.id }.toSet()
                val fillerBooks = allBooks.filter { it.book.id != currentId && !matchedIds.contains(it.book.id) }
                    .sortedByDescending { it.book.addedAt }
                    .take(5 - matchedBooks.size)

                matchedBooks + fillerBooks
            } else {
                matchedBooks.take(5)
            }
            recommendedBooks.map { it.toRelatedBookCandidate() }
        }

        return combine(
            if (authorFlows.isEmpty()) flowOf(emptyList()) else combine(authorFlows) { it.toList() },
            if (narratorFlows.isEmpty()) flowOf(emptyList()) else combine(narratorFlows) { it.toList() },
            recentFlow,
            heuristicFlow
        ) { authors, narrators, recent, heuristic ->
            RelatedData(authors, narrators, recent, heuristic)
        }
    }
}

/**
 * Data payload representing recommendations collections.
 */
data class RelatedData(
    val authorSections: List<RelatedSection>,
    val narratorSections: List<RelatedSection>,
    val recentlyAdded: List<RelatedBookCandidate>,
    val heuristicRecommended: List<RelatedBookCandidate>
)

/**
 * Groups recommendation candidates for application callers.
 * Lives in the application layer because the grouping is built from query/read-model concerns rather than a pure domain invariant.
 */
data class RelatedSection(
    val name: String,
    val books: List<RelatedBookCandidate>
)

/**
 * Room-free recommendation item.
 * Carries the display and playback fields needed by downstream scene adapters without exposing BookWithProgress or BookEntity.
 */
data class RelatedBookCandidate(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val totalDurationMs: Long,
    val thumbnailPath: String?,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int
)

/**
 * Hide repository progress wrappers at the use-case boundary.
 * Converts BookWithProgress exactly once so player and future scenes consume the same application recommendation shape.
 */
private fun BookWithProgress.toRelatedBookCandidate(): RelatedBookCandidate {
    return RelatedBookCandidate(
        id = book.id,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        totalDurationMs = book.totalDurationMs,
        thumbnailPath = book.thumbnailPath,
        coverPath = book.coverPath,
        coverLastUpdated = book.lastScannedAt,
        progressPercent = progressPercent
    )
}
