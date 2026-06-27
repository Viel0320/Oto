package com.viel.oto.media.manifest

import com.viel.oto.library.FileRef
import com.viel.oto.media.AudiobookMetadata
import com.viel.oto.media.parser.EmbeddedCoverBytes
import java.io.InputStream

/**
 * Audio file plus parsed metadata that the import pipeline can group before persistence.
 */
data class AudioMetadataRef(
    val file: FileRef,
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes? = null
)

/**
 * Generated multi-file book plan produced from loose audio files and optional sidecar context.
 */
data class HeuristicAggregationPlan(
    val title: String,
    val chapters: List<HeuristicChapterPlan>,
    val ruleVersion: String,
    val sidecarDescription: String? = null,
    val sidecarCoverFile: FileRef? = null
)

/**
 * One generated chapter entry before the library import pipeline maps it to persisted entities.
 */
data class HeuristicChapterPlan(
    val audio: AudioMetadataRef,
    val title: String
)

/**
 * Builds generated audiobook plans from adjacent loose audio files.
 */
object HeuristicAudioAggregator {
    const val RULE_VERSION = "sequence-v1"

    fun shouldAggregate(files: List<AudioMetadataRef>): Boolean {
        if (files.size < 2) return false
        val albums = files.map { it.metadata.album.trim() }
        val sameAlbum = albums.all { it.isNotBlank() && it == albums.first() }
        if (files.size == 2) return sameAlbum
        val names = files.map { it.file.displayName }
        return sameAlbum || hasSequentialNames(names)
    }

    suspend fun buildPlan(
        files: List<AudioMetadataRef>,
        directoryContext: ManifestSidecarSupport.DirectoryContext,
        openTextFile: suspend (FileRef) -> InputStream?
    ): HeuristicAggregationPlan {
        val title = generatedBookTitle(files)
        val titleContext = buildTitleContext(files, title)
        val orderedFiles = sortGeneratedChapterFiles(files, titleContext)
        val chapters = orderedFiles.mapIndexed { index, audio ->
            HeuristicChapterPlan(
                audio = audio,
                title = generatedChapterTitle(audio, titleContext, index)
            )
        }
        val sidecarPayload = ManifestSidecarSupport.resolveForHeuristic(
            directoryContext = directoryContext,
            openTextFile = openTextFile
        )
        return HeuristicAggregationPlan(
            title = title,
            chapters = chapters,
            ruleVersion = RULE_VERSION,
            sidecarDescription = sidecarPayload.description,
            sidecarCoverFile = sidecarPayload.coverFile
        )
    }

    private fun hasSequentialNames(names: List<String>): Boolean {
        val regex = Regex("(\\d+)")
        val numbers = names.mapNotNull { name ->
            regex.findAll(sequenceComparableName(name)).lastOrNull()?.value?.toIntOrNull()
        }
        if (numbers.size != names.size) return false
        return numbers.zipWithNext().all { (a, b) -> b == a + 1 || b > a }
    }

    /**
     * Removes the filename extension before sequence detection so codec suffixes such as mp3,
     * m4a, or mp4 cannot replace the chapter number as the last numeric token.
     */
    private fun sequenceComparableName(name: String): String =
        name.substringBeforeLast('.', missingDelimiterValue = name)

    private fun generatedBookTitle(files: List<AudioMetadataRef>): String {
        val parentName = files.first().file.parentSourcePath.substringAfterLast('/').ifBlank { "Generated audiobook" }
        return commonNonBlank(files.map { it.metadata.album })
            .ifBlank { commonNonBlank(files.map { it.metadata.title }) }
            .ifBlank { parentName }
    }

    private fun sortGeneratedChapterFiles(files: List<AudioMetadataRef>, titleContext: GeneratedTitleContext): List<AudioMetadataRef> {
        if (files.all { it.metadata.trackIndex != null }) {
            return files.sortedWith(compareBy<AudioMetadataRef> { it.metadata.trackIndex ?: Int.MAX_VALUE }
                .thenNaturalBy { it.file.displayName })
        }
        return files.sortedWith(compareNaturalBy<AudioMetadataRef> { generatedFilenameTitle(it, titleContext) }
            .thenNaturalBy { it.file.displayName })
    }

    private fun generatedChapterTitle(audio: AudioMetadataRef, titleContext: GeneratedTitleContext, index: Int): String {
        audio.metadata.title.trim().takeIf { it.isNotBlank() }?.let { return it }
        generatedFilenameTitle(audio, titleContext).takeIf { it.isNotBlank() }?.let { return it }
        return "Chapter ${index + 1}"
    }

    private fun generatedFilenameTitle(audio: AudioMetadataRef, titleContext: GeneratedTitleContext): String {
        val rawName = audio.file.displayName.substringBeforeLast('.', missingDelimiterValue = audio.file.displayName)
        val withoutBook = stripBookTitle(rawName, titleContext.bookTitle)
        val withoutPrefix = stripCommonPrefix(withoutBook, titleContext.commonFilenamePrefix)
        return cleanChapterSequenceNoise(withoutPrefix).ifBlank { cleanChapterSequenceNoise(withoutBook) }
    }

    private fun buildTitleContext(files: List<AudioMetadataRef>, bookTitle: String): GeneratedTitleContext {
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
        return value.removePrefix(prefix).trimTitleSeparators()
    }

    private fun stripBookTitle(value: String, bookTitle: String): String {
        if (bookTitle.isBlank()) return value
        val normalizedValue = value.normalizeTitleToken()
        val normalizedBook = bookTitle.normalizeTitleToken()
        if (normalizedBook.isBlank() || !normalizedValue.startsWith(normalizedBook)) return value
        val cutLength = value.length.coerceAtMost(bookTitle.length)
        return value.drop(cutLength).trimTitleSeparators()
    }

    private fun cleanChapterSequenceNoise(value: String): String {
        val trimmed = value.trimTitleSeparators()
        val withoutLeading = trimmed.replace(
            Regex("^(?i)(chapter|chap|ch|part|track|cd|disc|vol(?:ume)?)\\s*\\d+\\s*[-_~:：.、—–]*\\s*"),
            ""
        ).replace(
            Regex("^第\\s*\\d+\\s*[章节回卷部集]\\s*[-_~:：.、—–]*\\s*"),
            ""
        ).replace(
            Regex("^\\d+\\s*[-_~:：.、—–]*\\s*"),
            ""
        )
        return withoutLeading.replace(Regex("\\s*[-_~:：.、—–]*\\s*\\d+\\s*$"), "").trimTitleSeparators()
    }

    private fun commonReadablePrefix(values: List<String>): String {
        if (values.isEmpty()) return ""
        val shortest = values.minBy { it.length }
        val rawPrefixLength = shortest.indices
            .firstOrNull { index -> values.any { value -> value.getOrNull(index) != shortest[index] } }
            ?: shortest.length
        return shortest.take(rawPrefixLength).substringBeforeLastSeparator()
    }

    private fun String.substringBeforeLastSeparator(): String {
        val index = indexOfLast { it.isWhitespace() || it in "-_~:：.、—–" }
        return if (index >= 0) take(index + 1) else ""
    }

    private fun String.trimTitleSeparators(): String =
        trim().trim { it.isWhitespace() || it in "-_~:：.、—–" }.trim()

    private fun String.normalizeTitleToken(): String =
        lowercase().replace(Regex("[\\s\\p{Punct}—–_]+"), "")

    private fun naturalSortKey(value: String): List<NaturalSortPart> {
        val parts = Regex("\\d+|\\D+").findAll(value.lowercase()).map { match ->
            val token = match.value
            token.toLongOrNull()?.let { NaturalSortPart.Number(it) } ?: NaturalSortPart.Text(token)
        }.toList()
        return parts.ifEmpty { listOf(NaturalSortPart.Text(value.lowercase())) }
    }

    private fun <T> compareNaturalBy(selector: (T) -> String): Comparator<T> =
        Comparator { left, right -> compareNaturalKeys(naturalSortKey(selector(left)), naturalSortKey(selector(right))) }

    private fun <T> Comparator<T>.thenNaturalBy(selector: (T) -> String): Comparator<T> =
        thenComparator { left, right -> compareNaturalKeys(naturalSortKey(selector(left)), naturalSortKey(selector(right))) }

    private fun compareNaturalKeys(left: List<NaturalSortPart>, right: List<NaturalSortPart>): Int {
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
            when (this) {
                is Number if other is Number -> value.compareTo(other.value)
                is Text if other is Text -> value.compareTo(other.value)
                is Number -> -1
                else -> 1
            }
    }
}
