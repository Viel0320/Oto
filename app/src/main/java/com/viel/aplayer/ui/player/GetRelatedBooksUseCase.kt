package com.viel.aplayer.ui.player

import kotlinx.coroutines.flow.*
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookWithProgress

/**
 * 为每一次改动添加详尽的中文注释：
 * 用例类：获取与当前书籍相关的推荐列表。
 * 已升级重构：不仅包含原有的同作者、同播讲人和最近添加书籍分类，
 * 更是置顶新增了由 100% 响应式 Flow 驱动的“启发式推荐” (Heuristic Recommendation) 算法，
 * 深度结合书名相似度（LCS 最长公共子串算法）、作者/播讲人重合度、年份匹配以及添加时间新鲜度进行多维度加权打分，并配备智能兜底填充策略。
 */
class GetRelatedBooksUseCase(private val repository: LibraryRepository) {

    /**
     * 为每一次改动添加详尽的中文注释：计算两个字符串的最长公共连续子串 (LCS) 长度，
     * 用于有声书书名相关度的动态打分，能够极其精准地识别系列丛书或前缀相同的相关书籍。
     */
    private fun getLongestCommonSubstringLength(s1: String, s2: String): Int {
        if (s1.isEmpty() || s2.isEmpty()) return 0
        // 将字符串转换为小写并去除空格以提升相似度匹配的鲁棒性
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
     * 为每一次改动添加详尽的中文注释：
     * 从书名中提取出其丛书或序列的自然序号。
     * 支持提取：
     * 1. 汉字“上、中、下”分别映射为 1.0, 2.0, 3.0
     * 2. 阿拉伯数字，如“第3部”、“卷2”、“(4)”或末尾的数字，提取出其数值
     * 3. 中文数字，如“第一册”、“第二卷”等，映射为对应的数值 1.0, 2.0...
     */
    private fun extractSequenceIndex(title: String): Double? {
        val clean = title.lowercase().replace("\\s".toRegex(), "")
        
        // 1. 上中下特殊映射
        if (clean.contains("上册") || clean.endsWith("上")) return 1.0
        if (clean.contains("中册") || clean.endsWith("中")) return 2.0
        if (clean.contains("下册") || clean.endsWith("下")) return 3.0
        
        // 2. 匹配“第X(部|册|卷|季|集|传)”
        val cnPattern = "第([一二三四五六七八九十]+)[部册卷季集]".toRegex()
        cnPattern.find(clean)?.let { match ->
            val numStr = match.groupValues[1]
            return chineseToDecimal(numStr)
        }
        
        val numPattern = "第(\\d+)[部册卷季集]".toRegex()
        numPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        
        // 3. 直接匹配尾部的阿拉伯数字或括号中的数字
        val tailNumPattern = "(\\d+)\\s*$".toRegex() // 结尾的阿拉伯数字
        val parenNumPattern = "\\((\\d+)\\)".toRegex() // 括号内的阿拉伯数字
        
        parenNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        tailNumPattern.find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        
        // 4. 直接在文件名或书名中寻找第一个明显的数字（如果有的话，但排除 1900~2200 年份）
        val anyNumPattern = "(\\d+)".toRegex()
        anyNumPattern.findAll(clean).forEach { match ->
            val num = match.groupValues[1].toDoubleOrNull()
            if (num != null && (num < 1000 || num > 2200)) {
                return num
            }
        }
        
        // 5. 中文数字兜底，如 "一", "二"...
        val simpleCnNums = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        for (i in simpleCnNums.indices) {
            if (clean.contains(simpleCnNums[i])) {
                return (i + 1).toDouble()
            }
        }
        
        return null
    }

    /**
     * 为每一次改动添加详尽的中文注释：
     * 将繁体/简体中文数字转换为对应的 Double 数值，辅助进行丛书前后册大小比对。
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

        // 为每一次改动添加详尽的中文注释：
        // 4. 置顶高保真启发式智能推荐流 (Heuristic Recommendation)
        // 通过 combine 管道将全局书籍流 audiobooks 与当前有声书的实时 Room 实体流 observeBookById 联动合并。
        // 一旦用户在详情页右上角修改了元数据并保存，该数据流管道会瞬间重新捕获，触发算法跑分重算，极速响应式实时更新播放面板推荐页。
        val currentBookFlow = repository.observeBookById(currentId)
        val heuristicFlow = combine(repository.audiobooks, currentBookFlow) { allBooks, currentBook ->
            if (currentBook == null) return@combine emptyList<BookWithProgress>()
            
            val currentTitle = currentBook.title
            val currentYear = currentBook.year ?: ""
            
            // 筛选并计算候选书籍评分
            val scoredBooks = allBooks.filter { it.book.id != currentId }
                .map { item ->
                    val book = item.book
                    var score = 0.0
                    
                    // A. 【最高优先级】书名相关度打分 (Title Similarity)
                    val cleanCurrentTitle = currentTitle.replace("\\s".toRegex(), "")
                    val cleanCandidateTitle = book.title.replace("\\s".toRegex(), "")
                    var isTitleMatched = false
                    if (cleanCurrentTitle.isNotBlank() && cleanCandidateTitle.isNotBlank()) {
                        when {
                            // 书名完全一致（若有重名）：+40.0 分
                            cleanCurrentTitle.equals(cleanCandidateTitle, ignoreCase = true) -> {
                                score += 40.0
                                isTitleMatched = true
                            }
                            // 前缀/包含匹配（如丛书关系）：+30.0 分
                            cleanCandidateTitle.contains(cleanCurrentTitle, ignoreCase = true) || 
                            cleanCurrentTitle.contains(cleanCandidateTitle, ignoreCase = true) -> {
                                score += 30.0
                                isTitleMatched = true
                            }
                            else -> {
                                // 利用 LCS 算法计算最长公共连续子串长度，只要重合长度 >= 2 即可享受高额动态打分：每字 +5 分
                                val lcsLen = getLongestCommonSubstringLength(currentTitle, book.title)
                                if (lcsLen >= 2) {
                                    score += lcsLen * 5.0
                                    isTitleMatched = true
                                }
                            }
                        }
                    }

                    // 为每一次改动添加详尽的中文注释：
                    // 自然排序降权过滤设计：如果书名匹配成功，分析两本书的丛书/自然排序序号（如上中下、123、一二三等）。
                    // 若候选书籍的序列序号严格小于当前播放书籍（即属于当前播放章节或册数之前的历史内容），
                    // 则对其进行大额降权（扣减 25.0 分，但不低于 1.0 分，保证关联性仍存），从而让后续未听的章节或新书自动跃升排到前列。
                    if (isTitleMatched) {
                        val currentIndex = extractSequenceIndex(currentTitle)
                        val candidateIndex = extractSequenceIndex(book.title)
                        if (currentIndex != null && candidateIndex != null && candidateIndex < currentIndex) {
                            score = (score - 25.0).coerceAtLeast(1.0)
                        }
                    }
                    
                    // B. 作者匹配打分：+10.0 分
                    if (authorList.isNotEmpty() && !book.author.isNullOrBlank()) {
                        val candidateAuthors = book.author.split(",").map { it.trim() }
                        if (authorList.any { a -> candidateAuthors.any { ca -> ca.equals(a, ignoreCase = true) } }) {
                            score += 10.0
                        }
                    }
                    
                    // C. 讲述人（播讲人）匹配打分：+8.0 分
                    if (narratorList.isNotEmpty() && !book.narrator.isNullOrBlank()) {
                        val candidateNarrators = book.narrator.split(",").map { it.trim() }
                        if (narratorList.any { n -> candidateNarrators.any { cn -> cn.equals(n, ignoreCase = true) } }) {
                            score += 8.0
                        }
                    }
                    
                    // D. 出版年份匹配打分：+4.0 分
                    if (currentYear.isNotBlank() && !book.year.isNullOrBlank() && currentYear == book.year) {
                        score += 4.0
                    }
                    
                    // E. 新鲜度微调权重（Added Date）：保证匹配分值相同时，最近添加/导入的书籍排在最前面
                    score += book.addedAt.toDouble() / 1e13
                    
                    Pair(item, score)
                }
            
            // 过滤出真正具有相关度（匹配得分 > 0）的书籍
            val matchedBooks = scoredBooks.filter { it.second > 1e-5 }
                .sortedByDescending { it.second }
                .map { it.first }
            
            // 智能兜底填充策略：如果推荐数不足 5 个，从剩余书籍中按 addedAt 倒序（Recently Added）强力补充，保证展示的饱满度与高端感
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

        // 聚合所有流：将启发式智能推荐流 (heuristicFlow) 合并注入 RelatedData 最顶层
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
 * 聚合后的关联书籍数据模型。
 */
data class RelatedData(
    val authorSections: List<RelatedSection>,
    val narratorSections: List<RelatedSection>,
    val recentlyAdded: List<BookWithProgress>,
    // 为每一次改动添加详尽的中文注释：新增 heuristicRecommended 板块，用于承载高颜值的启发式推荐列表
    val heuristicRecommended: List<BookWithProgress>
)