package com.viel.oto.library.orchestrator

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.runCatchingCancellable
import com.viel.oto.library.FileRef
import com.viel.oto.library.orchestrator.draftmodels.BookDraft
import com.viel.oto.library.vfsFileKey
import com.viel.oto.logger.SecureLog
import com.viel.oto.media.AudiobookMetadata
import com.viel.oto.media.manifest.AudioMetadataRef
import com.viel.oto.media.manifest.ChapterCandidate
import com.viel.oto.media.manifest.HeuristicAggregationPlan
import com.viel.oto.media.manifest.MetadataSuggestion
import com.viel.oto.media.parser.CoverExtractor
import com.viel.oto.media.parser.MetadataResolver
import com.viel.oto.media.parser.Mp4MetadataFrameReader
import java.util.UUID

/**
 * BookDraftFactory.
 *
 * A pure entity mapping factory decoupled from the original ConflictClaimStep.
 * It maps audiobook metadata resolved from manifests, tags, or heuristic classification into core Oto database entities:
 * BookEntity, BookFileEntity, and ChapterEntity, and bundles them into a BookDraft.
 *
 * This isolation decouples decision algorithms from data-mapping details,
 * keeping core step classes focused and avoiding god-class anti-patterns.
 */
@OptIn(UnstableApi::class)
internal class BookDraftFactory(private val metadataResolver: MetadataResolver) {

    /**
     * Single-file mapping.
     * Constructs a BookDraft containing a BookEntity, a BookFileEntity, and associated chapters from metadata.
     */
    fun buildSingleAudioDraft(
        bookId: String,
        audio: AudioMetadataRef,
        description: String,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val fileId = UUID.randomUUID().toString()
        val title = singleAudioBookTitle(audio.metadata, audio.file.displayName)
        val series = resolveSeries(audio.metadata.album, audio.metadata.title, audio.file.displayName)

        val book = BookEntity(
            id = bookId,
            rootId = audio.file.rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            sourceRoot = audio.file.parentSourceKey,
            title = title,
            author = audio.metadata.author.trim(),
            narrator = audio.metadata.narrator.trim(),
            description = description.trim(),
            year = audio.metadata.year,
            totalDurationMs = audio.metadata.durationMs,
            totalFileSize = audio.file.fileSize,
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            series = series
        )
        val file = audio.toBookFile(bookId, fileId, 0, AudiobookSchema.FileStatus.READY)
        val chapters = if (audio.metadata.chapters.isNotEmpty()) {
            audio.metadata.chapters.mapIndexed { index, chapter ->
                chapter.copy(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = fileId,
                    index = index,
                    source = AudiobookSchema.ChapterSource.EMBEDDED
                )
            }
        } else {
            emptyList()
        }
        return BookDraft(book, listOf(file), chapters)
    }

    /**
     * Manifest-file mapping.
     * Constructs a BookDraft from manifest specifications (CUE/M3U8), including track durations and metadata suggestions.
     */
    suspend fun buildManifestDraft(
        bookId: String,
        sourceType: AudiobookSchema.SourceType,
        sourceFile: FileRef,
        audioFiles: List<FileRef>,
        chapterCandidates: List<ChapterCandidate>,
        fileTitles: Map<String, String>,
        fileDurations: Map<String, Long>,
        title: String,
        author: String = "",
        narrator: String = "",
        year: String = "",
        description: String = "",
        series: String = "",
        bookStatus: AudiobookSchema.BookStatus = AudiobookSchema.BookStatus.READY,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val durationByKey = audioFiles
            .mapWithBoundedConcurrency { file ->
                file.vfsKey to (fileDurations[file.vfsKey] ?: readDuration(file))
            }
            .toMap()
        val audioBookFiles = audioFiles.mapIndexed { index, ref ->
            ref.toBookFile(bookId, UUID.randomUUID().toString(), index, AudiobookSchema.FileStatus.READY, durationByKey[ref.vfsKey] ?: 0L)
        }
        val manifestFile = sourceFile.toManifestBookFile(bookId, UUID.randomUUID().toString())
        val fileIdByKey = audioBookFiles.associate { it.vfsKey() to it.id }
        val fileStartByKey = mutableMapOf<String, Long>()
        var start = 0L
        audioBookFiles.forEach { file ->
            fileStartByKey[file.vfsKey()] = start
            start += file.durationMs
        }

        val chapterSource = when (sourceType) {
            AudiobookSchema.SourceType.CUE -> AudiobookSchema.ChapterSource.CUE
            AudiobookSchema.SourceType.M3U8 -> AudiobookSchema.ChapterSource.M3U8
            else -> AudiobookSchema.ChapterSource.GENERATED
        }

        val chapters = if (chapterCandidates.isNotEmpty()) {
            chapterCandidates.mapIndexed { index, chapter ->
                val fileId = fileIdByKey[chapter.fileKey].orEmpty()
                val fileDuration = durationByKey[chapter.fileKey] ?: 0L
                val fallbackDuration = nextChapterOffset(chapterCandidates, index) ?: (fileDuration - chapter.fileOffsetMs)
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = fileId,
                    index = index,
                    title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                    startPositionMs = (fileStartByKey[chapter.fileKey] ?: 0L) + chapter.fileOffsetMs,
                    durationMs = (if (chapter.durationMs > 0L) chapter.durationMs else fallbackDuration).coerceAtLeast(0L),
                    fileOffsetMs = chapter.fileOffsetMs,
                    source = chapterSource
                )
            }
        } else {
            var chapterStart = 0L
            audioBookFiles.mapIndexed { index, file ->
                val chapterTitle = fileTitles[file.vfsKey()]?.ifBlank { null } ?: file.displayName.substringBeforeLast('.')
                val chapter = ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = file.id,
                    index = index,
                    title = chapterTitle,
                    startPositionMs = chapterStart,
                    durationMs = file.durationMs,
                    fileOffsetMs = 0L,
                    source = chapterSource
                )
                chapterStart += file.durationMs
                chapter
            }
        }

        val book = BookEntity(
            id = bookId,
            rootId = sourceFile.rootId,
            sourceType = sourceType,
            sourceRoot = sourceFile.parentSourceKey,
            title = title.ifBlank { sourceFile.displayName.substringBeforeLast('.') },
            author = author.trim(),
            narrator = narrator.trim(),
            description = description,
            year = year,
            totalDurationMs = audioBookFiles.sumOf { it.durationMs },
            totalFileSize = audioBookFiles.sumOf { it.fileSize } + manifestFile.fileSize,
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            status = bookStatus,
            series = series
        )
        return BookDraft(book, listOf(manifestFile) + audioBookFiles, chapters)
    }

    /**
     * Heuristic mapping.
     * Constructs a BookDraft from a heuristically grouped set of audio files, generating track-based virtual chapters.
     */
    fun buildGeneratedDraft(
        bookId: String,
        source: ImportSourceRef,
        plan: HeuristicAggregationPlan,
        description: String,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val orderedFiles = plan.chapters.map { it.audio }
        val firstChapterMetadata = orderedFiles.first().metadata
        val manifestJson = orderedFiles.joinToString(prefix = "[", postfix = "]") { "\"${it.file.vfsKey.escapeJson()}\"" }
        val bookFiles = orderedFiles.mapIndexed { index, audio ->
            audio.toBookFile(bookId, UUID.randomUUID().toString(), index, AudiobookSchema.FileStatus.READY)
        }
        var chapterStart = 0L
        val chapters = plan.chapters.mapIndexed { index, chapterPlan ->
            val file = bookFiles[index]
            val chapter = ChapterEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                bookFileId = file.id,
                index = index,
                title = chapterPlan.title.ifBlank { "Chapter ${index + 1}" },
                startPositionMs = chapterStart,
                durationMs = file.durationMs,
                fileOffsetMs = 0L,
                source = AudiobookSchema.ChapterSource.GENERATED
            )
            chapterStart += file.durationMs
            chapter
        }
        val series = resolveSeries(firstChapterMetadata.album, firstChapterMetadata.title, orderedFiles.first().file.displayName)

        val book = BookEntity(
            id = bookId,
            rootId = orderedFiles.first().file.rootId,
            sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
            sourceRoot = orderedFiles.first().file.parentSourceKey,
            generatedManifestJson = manifestJson,
            heuristicRuleVersion = plan.ruleVersion,
            title = plan.title,
            author = firstChapterMetadata.author.trim(),
            narrator = firstChapterMetadata.narrator.trim(),
            description = description.trim(),
            year = firstChapterMetadata.year.trim(),
            totalDurationMs = bookFiles.sumOf { it.durationMs },
            totalFileSize = bookFiles.sumOf { it.fileSize },
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            series = series
        )
        Log.i(TAG, "Generated audiobook draft: title=${plan.title.logValue()}, source=${source.displayName.logValue()}, files=${orderedFiles.size}")
        return BookDraft(book, bookFiles, chapters)
    }

    /**
     * Initial track reading.
     * Extracts tag information from the first audio track in a manifest list as a fallback for missing manifest attributes.
     */
    suspend fun firstManifestAudioMetadata(audioRefs: List<FileRef>): ManifestAudioMetadata? {
        val firstAudio = audioRefs.firstOrNull() ?: return null
        return runCatchingCancellable {
            ManifestAudioMetadata(firstAudio, metadataResolver.extract(firstAudio))
        }.onFailure { error ->
            SecureLog.warn(TAG, "Failed to read manifest fallback metadata: ${firstAudio.vfsDisplayId()}", error)
        }.getOrNull()
    }

    /**
     * Precedence rule evaluator.
     * Evaluates final book metadata fields following the precedence: Manifest > Embedded tags > File name.
     */
    fun resolveManifestBookMetadata(
        manifestMetadata: MetadataSuggestion,
        firstAudio: ManifestAudioMetadata?,
        sourceFile: FileRef,
        sidecarDescription: String? = null
    ): ResolvedManifestMetadata =
        ResolvedManifestMetadata(
            title = firstNonBlank(
                manifestMetadata.title,
                firstAudio?.metadata?.album,
                firstAudio?.metadata?.title,
                sourceFile.displayName.substringBeforeLast('.')
            ),
            author = firstNonBlank(manifestMetadata.author, firstAudio?.metadata?.author),
            narrator = firstNonBlank(manifestMetadata.narrator, firstAudio?.metadata?.narrator),
            year = firstNonBlank(manifestMetadata.year, firstAudio?.metadata?.year),
            description = firstNonBlank(manifestMetadata.description, sidecarDescription, firstAudio?.metadata?.description),
            series = resolveSeries(
                firstAudio?.metadata?.album.orEmpty(),
                firstAudio?.metadata?.title.orEmpty(),
                sourceFile.displayName
            )
        )

    private fun FileRef.toManifestBookFile(bookId: String, id: String): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.SOURCE_MANIFEST,
            index = 0,
            sourcePath = sourcePath,
            sourceIdentity = sourceIdentity,
            etag = etag,
            displayName = displayName,
            durationMs = 0L,
            fileSize = fileSize,
            lastModified = lastModified,
            status = AudiobookSchema.FileStatus.READY
        )

    private fun AudioMetadataRef.toBookFile(
        bookId: String,
        id: String,
        index: Int,
        status: AudiobookSchema.FileStatus,
        overrideDurationMs: Long? = null
    ): BookFileEntity = file.toAudioBookFile(bookId, id, index, status, overrideDurationMs ?: metadata.durationMs)

    private fun FileRef.toBookFile(
        bookId: String,
        id: String,
        index: Int,
        status: AudiobookSchema.FileStatus,
        overrideDurationMs: Long? = null
    ): BookFileEntity = toAudioBookFile(bookId, id, index, status, overrideDurationMs ?: 0L)

    private fun FileRef.toAudioBookFile(
        bookId: String,
        id: String,
        index: Int,
        status: AudiobookSchema.FileStatus,
        durationMs: Long
    ): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.AUDIO,
            index = index,
            sourcePath = sourcePath,
            sourceIdentity = sourceIdentity,
            etag = etag,
            displayName = displayName,
            durationMs = durationMs,
            fileSize = fileSize,
            lastModified = lastModified,
            status = status
        )

    private suspend fun readDuration(file: FileRef): Long =
        runCatchingCancellable {
            val metadataDuration = metadataResolver.extract(file).durationMs
            if (Mp4MetadataFrameReader.supports(file.displayName)) {
                return@runCatchingCancellable metadataDuration
            }
            metadataDuration.takeIf { it > 0L }?.let { return@runCatchingCancellable it }
            metadataDuration
        }.getOrDefault(0L)

    private fun nextChapterOffset(chapters: List<ChapterCandidate>, index: Int): Long? {
        val current = chapters.getOrNull(index) ?: return null
        return chapters.drop(index + 1).firstOrNull { it.fileKey == current.fileKey }?.fileOffsetMs?.minus(current.fileOffsetMs)
    }

    private fun resolveSeries(album: String, title: String, displayName: String): String =
        album.trim()
            .ifBlank { title.trim() }
            .ifBlank { displayName.substringBeforeLast('.') }

    private fun singleAudioBookTitle(metadata: AudiobookMetadata, displayName: String): String =
        metadata.title.trim()
            .ifBlank { metadata.album.trim() }
            .ifBlank { displayName.substringBeforeLast('.') }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun FileRef.vfsDisplayId(): String =
        "vfs://$rootId/$sourcePath"

    private fun BookFileEntity.vfsKey(): String =
        vfsFileKey(rootId, sourcePath)


    private fun String.logValue(): String =
        ifBlank { "<blank>" }
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    internal data class ManifestAudioMetadata(
        val file: FileRef,
        val metadata: AudiobookMetadata
    )

    internal data class ResolvedManifestMetadata(
        val title: String,
        val author: String,
        val narrator: String,
        val year: String,
        val description: String,
        val series: String
    )

    companion object {
        private const val TAG = "BookDraftFactory"
    }
}

/**
 * Formatting security utility.
 * Sanitizes backslashes and double quotes in raw strings before manual JSON template composition.
 * Prevents structural breakdowns and runtime parsing crashes when handling URIs with escape sequences.
 */
internal fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
