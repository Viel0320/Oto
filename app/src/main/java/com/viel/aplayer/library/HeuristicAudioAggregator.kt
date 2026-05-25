package com.viel.aplayer.library

import com.viel.aplayer.media.AudiobookMetadata
import com.viel.aplayer.media.parser.EmbeddedCoverBytes

// Audio metadata extracted during one scan; kept package-local so orchestration and heuristics share one shape.
internal data class AudioMetadataRef(
    val file: FileRef,
    val metadata: AudiobookMetadata,
    // 详尽的中文注释：导入期 MP4 元数据解析已经读到的 covr 封面随音频引用传递，后续组书可直接写缓存而不重复 Range 读取。
    val embeddedCover: EmbeddedCoverBytes? = null
)

// Final heuristic plan consumed by ImportOrchestrator when it creates the generated audiobook draft.
internal data class HeuristicAggregationPlan(
    val title: String,
    val chapters: List<HeuristicChapterPlan>,
    val ruleVersion: String
)

// Per-file chapter decision after heuristic ordering and title cleanup.
internal data class HeuristicChapterPlan(
    val audio: AudioMetadataRef,
    val title: String
)

// Owns loose-audio aggregation rules so ImportOrchestrator can stay focused on scan flow and persistence drafts.
internal object HeuristicAudioAggregator {
    // Persisted rule version documents which heuristic produced a generated audiobook.
    const val RULE_VERSION = "sequence-v1"

    fun shouldAggregate(files: List<AudioMetadataRef>): Boolean {
        if (files.size < 2) return false
        // sameAlbum only depends on the album metadata field, never on description.
        val albums = files.map { it.metadata.album.trim() }
        val sameAlbum = albums.all { it.isNotBlank() && it == albums.first() }
        if (files.size == 2) return sameAlbum
        val names = files.map { it.file.displayName }
        return sameAlbum || hasSequentialNames(names)
    }

    fun buildPlan(files: List<AudioMetadataRef>): HeuristicAggregationPlan {
        val title = generatedBookTitle(files)
        // Filename cleanup needs group-level context to remove shared prefixes before choosing chapter labels.
        val titleContext = buildTitleContext(files, title)
        // Generated chapters use the requested priority: ID3 track index first, then cleaned filename natural order.
        val orderedFiles = sortGeneratedChapterFiles(files, titleContext)
        val chapters = orderedFiles.mapIndexed { index, audio ->
            HeuristicChapterPlan(
                audio = audio,
                title = generatedChapterTitle(audio, titleContext, index)
            )
        }
        return HeuristicAggregationPlan(
            title = title,
            chapters = chapters,
            ruleVersion = RULE_VERSION
        )
    }

    private fun hasSequentialNames(names: List<String>): Boolean {
        // Sequence fallback lets numbered chapter files aggregate even without complete album metadata.
        val regex = Regex("(\\d+)")
        val numbers = names.mapNotNull { regex.findAll(it).lastOrNull()?.value?.toIntOrNull() }
        if (numbers.size != names.size) return false
        return numbers.zipWithNext().all { (a, b) -> b == a + 1 || b > a }
    }

    private fun generatedBookTitle(files: List<AudioMetadataRef>): String {
        // 为每一次改动添加详尽的中文注释：启发式默认标题从 VFS 父路径提取，不再读取旧父目录 URI。
        val parentName = files.first().file.parentSourcePath.substringAfterLast('/').ifBlank { "Generated audiobook" }
        // Heuristic groups created by sameAlbum should display the shared album as the book title.
        return commonNonBlank(files.map { it.metadata.album })
            .ifBlank { commonNonBlank(files.map { it.metadata.title }) }
            .ifBlank { parentName }
    }

    private fun sortGeneratedChapterFiles(files: List<AudioMetadataRef>, titleContext: GeneratedTitleContext): List<AudioMetadataRef> {
        // Rule 1: when all files expose an ID3 track index, keep ID3 title semantics and sort by that index.
        if (files.all { it.metadata.trackIndex != null }) {
            return files.sortedWith(compareBy<AudioMetadataRef> { it.metadata.trackIndex ?: Int.MAX_VALUE }
                .thenNaturalBy { it.file.displayName })
        }
        // Rule 2/3/4: otherwise sort naturally by the best cleaned filename title.
        return files.sortedWith(compareNaturalBy<AudioMetadataRef> { generatedFilenameTitle(it, titleContext) }
            .thenNaturalBy { it.file.displayName })
    }

    private fun generatedChapterTitle(audio: AudioMetadataRef, titleContext: GeneratedTitleContext, index: Int): String {
        // Rule 1: ID3 Title has display priority even when sorting falls back to filename.
        audio.metadata.title.trim().takeIf { it.isNotBlank() }?.let { return it }
        // Rule 2/3: use the filename after removing book/common prefix and sequence noise.
        generatedFilenameTitle(audio, titleContext).takeIf { it.isNotBlank() }?.let { return it }
        // Rule 4: final fallback is a sequential chapter label.
        return "Chapter ${index + 1}"
    }

    private fun generatedFilenameTitle(audio: AudioMetadataRef, titleContext: GeneratedTitleContext): String {
        val rawName = audio.file.displayName.substringBeforeLast('.', missingDelimiterValue = audio.file.displayName)
        // Filename fallback removes the book title first so repeated album names do not become chapter text.
        val withoutBook = stripBookTitle(rawName, titleContext.bookTitle)
        // Then remove the group-level common prefix before stripping chapter/part sequence decorations.
        val withoutPrefix = stripCommonPrefix(withoutBook, titleContext.commonFilenamePrefix)
        return cleanChapterSequenceNoise(withoutPrefix).ifBlank { cleanChapterSequenceNoise(withoutBook) }
    }

    private fun buildTitleContext(files: List<AudioMetadataRef>, bookTitle: String): GeneratedTitleContext {
        // Common filename prefix is computed after book-title stripping so album names are not double-counted.
        val strippedNames = files.map { audio ->
            val rawName = audio.file.displayName.substringBeforeLast('.', missingDelimiterValue = audio.file.displayName)
            stripBookTitle(rawName, bookTitle)
        }
        return GeneratedTitleContext(
            bookTitle = bookTitle,
            commonFilenamePrefix = commonReadablePrefix(strippedNames).trimTitleSeparators()
        )
    }

    private fun stripCommonPrefix(value: String, prefix: String): String {
        if (prefix.isBlank()) return value
        // Public filename fallback removes only the literal shared prefix, preserving the per-file readable suffix.
        return value.removePrefix(prefix).trimTitleSeparators()
    }

    private fun stripBookTitle(value: String, bookTitle: String): String {
        if (bookTitle.isBlank()) return value
        // Compare normalized text, but cut from the original string to preserve readable punctuation.
        val normalizedValue = value.normalizeTitleToken()
        val normalizedBook = bookTitle.normalizeTitleToken()
        if (normalizedBook.isBlank() || !normalizedValue.startsWith(normalizedBook)) return value
        val cutLength = value.length.coerceAtMost(bookTitle.length)
        return value.drop(cutLength).trimTitleSeparators()
    }

    private fun cleanChapterSequenceNoise(value: String): String {
        val trimmed = value.trimTitleSeparators()
        // Remove leading labels like "Chapter 03", "第 3 章", or "Part 2".
        val withoutLeading = trimmed.replace(
            Regex("^(?i)(chapter|chap|ch|part|track|cd|disc|vol(?:ume)?)\\s*\\d+\\s*[-_~:：.、—–]*\\s*"),
            ""
        ).replace(
            Regex("^第\\s*\\d+\\s*[章节回卷部集]\\s*[-_~:：.、—–]*\\s*"),
            ""
        ).replace(
            // Common-prefix stripping can leave only a bare numeric sequence at the start.
            Regex("^\\d+\\s*[-_~:：.、—–]*\\s*"),
            ""
        )
        // Remove trailing split numbers like "~ 1" so the visible title is the chapter name.
        return withoutLeading.replace(Regex("\\s*[-_~:：.、—–]*\\s*\\d+\\s*$"), "").trimTitleSeparators()
    }

    private fun commonReadablePrefix(values: List<String>): String {
        if (values.isEmpty()) return ""
        val shortest = values.minBy { it.length }
        val rawPrefixLength = shortest.indices
            .firstOrNull { index -> values.any { value -> value.getOrNull(index) != shortest[index] } }
            ?: shortest.length
        // Cut the prefix at a separator boundary so words are not chopped in half.
        return shortest.take(rawPrefixLength).substringBeforeLastSeparator()
    }

    private fun String.substringBeforeLastSeparator(): String {
        // Prefix stripping only keeps separator-bounded chunks.
        val index = indexOfLast { it.isWhitespace() || it in "-_~:：.、—–" }
        return if (index >= 0) take(index + 1) else ""
    }

    private fun String.trimTitleSeparators(): String =
        // Trim common separators left by book-title or sequence removal.
        trim().trim { it.isWhitespace() || it in "-_~:：.、—–" }.trim()

    private fun String.normalizeTitleToken(): String =
        // Natural comparisons ignore punctuation and spacing so "Book - 01" and "Book 01" line up.
        lowercase().replace(Regex("[\\s\\p{Punct}—–_]+"), "")

    private fun naturalSortKey(value: String): List<NaturalSortPart> {
        // Split text and number runs so "10" sorts after "2".
        val parts = Regex("\\d+|\\D+").findAll(value.lowercase()).map { match ->
            val token = match.value
            token.toLongOrNull()?.let { NaturalSortPart.Number(it) } ?: NaturalSortPart.Text(token)
        }.toList()
        return parts.ifEmpty { listOf(NaturalSortPart.Text(value.lowercase())) }
    }

    private fun <T> compareNaturalBy(selector: (T) -> String): Comparator<T> =
        // Kotlin compareBy requires Comparable keys, so natural sorting uses an explicit Comparator.
        Comparator { left, right -> compareNaturalKeys(naturalSortKey(selector(left)), naturalSortKey(selector(right))) }

    private fun <T> Comparator<T>.thenNaturalBy(selector: (T) -> String): Comparator<T> =
        // Keep tie-breaking stable and human ordered for filenames with numeric suffixes.
        thenComparator { left, right -> compareNaturalKeys(naturalSortKey(selector(left)), naturalSortKey(selector(right))) }

    private fun compareNaturalKeys(left: List<NaturalSortPart>, right: List<NaturalSortPart>): Int {
        // Compare each natural sort token until one side wins.
        val max = maxOf(left.size, right.size)
        for (index in 0 until max) {
            val l = left.getOrNull(index) ?: return -1
            val r = right.getOrNull(index) ?: return 1
            val result = l.compareTo(r)
            if (result != 0) return result
        }
        return 0
    }

    private fun commonNonBlank(values: List<String>): String {
        // Shared nonblank metadata is safe to promote to a generated book-level value.
        val trimmed = values.map { it.trim() }.filter { it.isNotBlank() }
        return if (trimmed.isNotEmpty() && trimmed.all { it == trimmed.first() }) trimmed.first() else ""
    }

    private data class GeneratedTitleContext(
        val bookTitle: String,
        val commonFilenamePrefix: String
    )

    private sealed interface NaturalSortPart : Comparable<NaturalSortPart> {
        data class Text(val value: String) : NaturalSortPart
        data class Number(val value: Long) : NaturalSortPart

        override fun compareTo(other: NaturalSortPart): Int =
            when {
                this is Number && other is Number -> value.compareTo(other.value)
                this is Text && other is Text -> value.compareTo(other.value)
                this is Number -> -1
                else -> 1
            }
    }
}
