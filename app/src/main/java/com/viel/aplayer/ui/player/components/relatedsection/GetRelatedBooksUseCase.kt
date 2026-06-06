package com.viel.aplayer.ui.player.components.relatedsection

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.gateway.BookQueryGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * UseCase: Get related audiobooks (Service querying related catalog recommendations)
 * Features "Heuristic Recommendation" sorting by combining title similarity (LCS), year, narrator, and author weight scoring.
 * Refactored to bind BookQueryGateway interface rather than direct fat LibraryRepository dependencies.
 */
class GetRelatedBooksUseCase(private val repository: BookQueryGateway) {

    /**
     * Compute longest common substring (To score title similarity using dynamic programming algorithms)
     */
    private fun getLongestCommonSubstringLength(s1: String, s2: String): Int {
        if (s1.isEmpty() || s2.isEmpty()) return 0
        // Normalize comparison strings (To strip spaces and convert characters to lowercase for robust matches)
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
     * Parse sequence digits (To extract sequence index numbers from book title patterns)
     */
    private fun extractSequenceIndex(title: String): Double? {
        val clean = title.lowercase().replace("\\s".toRegex(), "")
        
        // 1. Map relative Chinese markers
        if (clean.contains("上册") || clean.endsWith("上")) return 1.0
        if (clean.contains("中册") || clean.endsWith("中")) return 2.0
        if (clean.contains("下册") || clean.endsWith("下")) return 3.0
        
        // 2. Match standard prefix patterns
        val cnPattern = "第([一二三四五六七八九十]+)[部册卷季集]".toRegex()
        cnPattern.find(clean)?.let { match ->
            val numStr = match.groupValues[1]
            return chineseToDecimal(numStr)
        }
        
        val numPattern = "第(\\d+)[部册卷季集]".toRegex()
        numPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        
        // 3. Match trailing digits or parenthesized numbers
        val tailNumPattern = "(\\d+)\\s*$".toRegex()
        val parenNumPattern = "\\((\\d+)\\)".toRegex()
        
        parenNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        tailNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        
        // 4. Fallback search for first non-year number digits
        val anyNumPattern = "(\\d+)".toRegex()
        anyNumPattern.findAll(clean).forEach { match ->
            val num = match.groupValues[1].toDoubleOrNull()
            if (num != null && (num !in 1000.0..2200.0)) {
                return num
            }
        }
        
        // 5. Check Chinese digit characters fallback
        val simpleCnNums = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        for (i in simpleCnNums.indices) {
            if (clean.contains(simpleCnNums[i])) {
                return (i + 1).toDouble()
            }
        }
        
        return null
    }

    /**
     * Chinese digits to decimal converter (To parse Chinese digit strings into double values)
     */
    private fun chineseToDecimal(chinese: String): Double {
        val cnNums = mapOf(
            '一' to 1.0, '二' to 2.0, '三' to 3.0, '四' to 4.0, '五' to 5.0,
            '六' to 6.0, '七' to 7.0, '八' to 8.0, '九' to 9.0, '十' to 10.0
        )
        if (chinese.length == 1) {
            return cnNums[chinese[0]] ?: 0.0
        }
        if (chinese.length == 2 && chinese[0] == '十') {
            return 10.0 + (cnNums[chinese[1]] ?: 0.0)
        }
        var result = 0.0
        var temp = 0.0
        for (char in chinese) {
            val v = cnNums[char] ?: 0.0
            if (char == '十') {
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
     * Query related items flows (To stream related collections)
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
 
        // 1. Author collections flow
        val authorFlows = authorList.map { name ->
            repository.filterByAuthorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books)
            }
        }
 
        // 2. Narrator collections flow
        val narratorFlows = narratorList.map { name ->
            repository.filterByNarratorLimited(name, currentId, 3).map { books ->
                RelatedSection(name, books)
            }
        }
 
        // 3. Recently added flow (excluding current creator and narrator)
        val recentFlow = repository.getRecentlyAddedExclusive(currentId, authorList, narratorList, 3)

        //
        // 4. Combined heuristic scoring flow (To calculate recommendation metrics dynamically)
        // Combines audiobooks list and current book metadata flow to recalculate recommendations instantly upon edits.
        val currentBookFlow = repository.observeBookById(currentId)
        val heuristicFlow = combine(repository.audiobooks, currentBookFlow) { allBooks, currentBook ->
            if (currentBook == null) return@combine emptyList()
            
            val currentTitle = currentBook.title
            val currentYear = currentBook.year
            val currentSeries = currentBook.series
            
            // Calculate candidate recommendation scores
            val scoredBooks = allBooks.filter { it.book.id != currentId }
                .map { item ->
                    val book = item.book
                    var score = 0.0
                    
                    // A. Title similarity scoring (Highest priority)
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

                    // Related Heuristic Score Sequence Decay Adjustment (Deduct 50 points if candidate index is smaller)
                    // Deducts 50.0 points from the recommendation score of candidate books with sequence numbers smaller than the current book to prioritize subsequent chapters.
                    if (isTitleMatched) {
                        val currentIndex = extractSequenceIndex(currentTitle)
                        val candidateIndex = extractSequenceIndex(book.title)
                        if (currentIndex != null && candidateIndex != null && candidateIndex < currentIndex) {
                            score = (score - 50.0).coerceAtLeast(1.0)
                        }
                    }
                    
                    // Related Heuristic Score Series Match (Add score boost if books belong to the same series)
                    // If both the current book and candidate book have a matching non-empty series, boost the score by 35.0 to prioritize series-level related items.
                    if (currentSeries.isNotBlank() && book.series.isNotBlank() &&
                        currentSeries.trim().equals(book.series.trim(), ignoreCase = true)) {
                        score += 35.0
                    }
                    
                    // B. Author matched score: +10.0
                    if (authorList.isNotEmpty() && book.author.isNotBlank()) {
                        val candidateAuthors = book.author.split(",").map { it.trim() }
                        if (authorList.any { a -> candidateAuthors.any { ca -> ca.equals(a, ignoreCase = true) } }) {
                            score += 10.0
                        }
                    }
                    
                    // C. Narrator matched score: +8.0
                    if (narratorList.isNotEmpty() && book.narrator.isNotBlank()) {
                        val candidateNarrators = book.narrator.split(",").map { it.trim() }
                        if (narratorList.any { n -> candidateNarrators.any { cn -> cn.equals(n, ignoreCase = true) } }) {
                            score += 8.0
                        }
                    }
                    
                    // D. Publishing year matched score: +4.0
                    if (currentYear.isNotBlank() && book.year.isNotBlank() && currentYear == book.year) {
                        score += 4.0
                    }
                    
                    // E. Freshness weight tuning (Added Date)
                    score += book.addedAt.toDouble() / 1e13
                    
                    Pair(item, score)
                }
            
            // Filter candidates with matching scores
            val matchedBooks = scoredBooks.filter { it.second > 1e-5 }
                .sortedByDescending { it.second }
                .map { it.first }
            
            // Fallback backfill strategy (To fill up list with recently added items if recommendations are fewer than 5)
            if (matchedBooks.size < 5) {
                val matchedIds = matchedBooks.map { it.book.id }.toSet()
                val fillerBooks = allBooks.filter { it.book.id != currentId && !matchedIds.contains(it.book.id) }
                    .sortedByDescending { it.book.addedAt }
                    .take(5 - matchedBooks.size)
                
                matchedBooks + fillerBooks
            } else {
                matchedBooks.take(5)
            }
        }

        // Assemble data flows (To bundle same-author, same-narrator, and heuristic flows into RelatedData)
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
 * Consolidated related details (Data payload representing recommendations collections)
 */
data class RelatedData(
    val authorSections: List<RelatedSection>,
    val narratorSections: List<RelatedSection>,
    val recentlyAdded: List<BookWithProgress>,
    val heuristicRecommended: List<BookWithProgress>
)