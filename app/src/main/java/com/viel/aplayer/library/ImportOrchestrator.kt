package com.viel.aplayer.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.data.AudiobookSchema
import com.viel.aplayer.data.BookEntity
import com.viel.aplayer.data.BookFileEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.data.PendingScanActionEntity
import com.viel.aplayer.library.manifest.CueManifestParser
import com.viel.aplayer.library.manifest.M3u8ManifestParser
import com.viel.aplayer.library.manifest.ManifestResolver
import com.viel.aplayer.media.CoverExtractor
import com.viel.aplayer.media.MetadataExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// Import orchestration follows the documented fixed order: cue -> m3u8 -> audio.
class ImportOrchestrator(
    private val context: Context,
    private val metadataExtractor: MetadataExtractor = MetadataExtractor(context),
    private val coverExtractor: CoverExtractor = CoverExtractor(context)
) {
    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex
    ): ImportRunResult = withContext(Dispatchers.IO) {
        val context = ImportRunContext(scanId, existingClaimIndex, inventory)

        inventory.cueFiles.sortedByStableFileKey().forEach { processCue(it, context, inventory) }
        inventory.m3u8Files.sortedByStableFileKey().forEach { processM3u8(it, context, inventory) }
        processAudioFiles(inventory.audioFiles.sortedByStableFileKey(), context)

        ImportRunResult(
            scanId = scanId,
            readyImports = context.readyImports,
            refreshedBooks = context.refreshedBooks,
            pendingActions = context.pendingActions,
            failures = context.failures
        )
    }

    private suspend fun processCue(cue: FileRef, context: ImportRunContext, inventory: FileInventory) {
        val result = CueManifestParser.parse(this.context, cue.documentFile)
        if (result == null) {
            context.failures.add(ImportCommand.RecordFailure(ImportFailure(cue.uri, "CUE parse failed")))
            return
        }

        val resolved = result.referencedFiles.distinct().mapNotNull { entry ->
            ManifestResolver.resolveRelativePath(cue.parentDocumentFile, entry)?.let { file -> entry to file.uri.toString() }
        }
        val missingCount = result.referencedFiles.distinct().count { entry ->
            resolved.none { it.first == entry } && isAudioName(entry)
        }
        if (resolved.isEmpty()) {
            context.failures.add(ImportCommand.RecordFailure(ImportFailure(cue.uri, "CUE references no resolvable audio")))
            return
        }

        val entryToUri = resolved.toMap()
        val audioRefs = resolved.map { (_, uri) -> inventory.audioFiles.firstOrNull { it.uri == uri } ?: syntheticRef(uri, cue) }
        audioRefs.forEach { context.reservedAudioIdentities.add(it.identity) }

        val source = ImportSourceRef(AudiobookSchema.SourceType.CUE, cue.uri, cue.displayName)
        val claimedIdentities = audioRefs.map { it.identity } + cue.identity
        val reservation = context.runClaimLedger.reserve(source, claimedIdentities, context.existingClaimIndex)
        if (!reservation.reserved) {
            // Re-seeing the same complete CUE claim refreshes ownership instead of creating a conflict.
            if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context)) return
            context.pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
            return
        }
        if (missingCount > 0) {
            context.pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
            return
        }

        val chapters = result.chapters.mapNotNull { chapter ->
            entryToUri[chapter.fileUri]?.let { chapter.copy(fileUri = it) }
        }
        context.readyImports.add(ImportCommand.CreateReadyBook(buildManifestDraft(
            sourceType = AudiobookSchema.SourceType.CUE,
            sourceFile = cue,
            audioFiles = audioRefs,
            chapterCandidates = chapters,
            fileTitles = emptyMap(),
            fileDurations = emptyMap(),
            title = result.metadata.title?.takeIf { it.isNotBlank() } ?: cue.displayName.substringBeforeLast('.'),
            author = result.metadata.author.orEmpty(),
            narrator = result.metadata.narrator.orEmpty(),
            year = result.metadata.year.orEmpty(),
            description = result.metadata.description.orEmpty(),
            inventory = context.inventory
        )))
    }

    private suspend fun processM3u8(m3u8: FileRef, context: ImportRunContext, inventory: FileInventory) {
        val items = M3u8ManifestParser.parse(this.context, m3u8.documentFile)
        if (items.isEmpty()) {
            context.failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.uri, "M3U8 parse failed or empty")))
            return
        }

        val resolved = items.distinctBy { it.uri }.mapNotNull { item ->
            if (item.uri.startsWith("http://", true) || item.uri.startsWith("https://", true)) return@mapNotNull null
            ManifestResolver.resolveRelativePath(m3u8.parentDocumentFile, item.uri)
                ?.let { item to it.uri.toString() }
        }
        val missingCount = items.distinctBy { it.uri }.count { item ->
            resolved.none { it.first.uri == item.uri } && isAudioName(item.uri)
        }
        if (resolved.isEmpty()) {
            context.failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.uri, "M3U8 references no resolvable local audio")))
            return
        }

        val audioRefs = resolved.map { (_, uri) -> inventory.audioFiles.firstOrNull { it.uri == uri } ?: syntheticRef(uri, m3u8) }
        audioRefs.forEach { context.reservedAudioIdentities.add(it.identity) }

        val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.uri, m3u8.displayName)
        val claimedIdentities = audioRefs.map { it.identity } + m3u8.identity
        val reservation = context.runClaimLedger.reserve(source, claimedIdentities, context.existingClaimIndex)
        if (!reservation.reserved) {
            // Re-seeing the same complete playlist claim refreshes ownership instead of creating a conflict.
            if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context)) return
            context.pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
            return
        }
        if (missingCount > 0) {
            context.pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
            return
        }

        val fileTitles = resolved.mapNotNull { (item, uri) -> item.title?.let { uri to it } }.toMap()
        val fileDurations = resolved.mapNotNull { (item, uri) -> item.durationMs?.let { uri to it } }.toMap()
        context.readyImports.add(ImportCommand.CreateReadyBook(buildManifestDraft(
            sourceType = AudiobookSchema.SourceType.M3U8,
            sourceFile = m3u8,
            audioFiles = audioRefs,
            chapterCandidates = emptyList(),
            fileTitles = fileTitles,
            fileDurations = fileDurations,
            title = m3u8.displayName.substringBeforeLast('.'),
            inventory = context.inventory
        )))
    }

    private suspend fun processAudioFiles(audioFiles: List<FileRef>, context: ImportRunContext) {
        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        suspend fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (shouldAggregate(pendingHeuristic)) {
                val files = pendingHeuristic.map { it.file }
                val source = ImportSourceRef(
                    sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
                    sourceUri = "generated://${files.first().parentUri}/${files.joinToString("-") { it.documentId.hashCode().toString() }}",
                    displayName = generatedBookTitle(pendingHeuristic)
                )
                val reservation = context.runClaimLedger.reserve(source, files.map { it.identity }, context.existingClaimIndex)
                if (reservation.reserved) {
                    context.readyImports.add(ImportCommand.CreateReadyBook(buildGeneratedDraft(source, pendingHeuristic, context.inventory)))
                } else {
                    context.pendingActions.add(createConflict(context.scanId, source, reservation))
                }
            } else {
                pendingHeuristic.forEach { createSingleAudio(it, context) }
            }
            pendingHeuristic.clear()
        }

        for (audio in audioFiles) {
            if (context.reservedAudioIdentities.contains(audio.identity)) continue
            if (context.existingClaimIndex.has(audio.identity)) continue

            val metadata = metadataExtractor.extract(audio.uri.toUri())
            if (metadata.chapters.isNotEmpty()) {
                flushHeuristic()
                createSingleAudio(AudioMetadataRef(audio, metadata), context)
            } else {
                val last = pendingHeuristic.lastOrNull()
                if (last != null && last.file.parentUri != audio.parentUri) {
                    flushHeuristic()
                }
                pendingHeuristic.add(AudioMetadataRef(audio, metadata))
            }
        }
        flushHeuristic()
    }

    private suspend fun createSingleAudio(audio: AudioMetadataRef, context: ImportRunContext) {
        val source = ImportSourceRef(AudiobookSchema.SourceType.SINGLE_AUDIO, audio.file.uri, audio.file.displayName)
        val reservation = context.runClaimLedger.reserve(source, listOf(audio.file.identity), context.existingClaimIndex)
        if (!reservation.reserved) {
            context.pendingActions.add(createConflict(context.scanId, source, reservation))
            return
        }
        context.readyImports.add(ImportCommand.CreateReadyBook(buildSingleAudioDraft(audio, context.inventory)))
    }

    private suspend fun buildSingleAudioDraft(audio: AudioMetadataRef, inventory: FileInventory): BookDraft {
        val bookId = UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val cover = resolveCover(bookId, primaryAudio = audio.file, manifestFile = null, fallbackAudio = emptyList(), inventory = inventory)
        val title = audio.metadata.title.ifBlank { audio.file.displayName.substringBeforeLast('.') }

        val book = BookEntity(
            id = bookId,
            rootId = audio.file.rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title,
            author = audio.metadata.author.trim(),
            narrator = audio.metadata.narrator.trim(),
            description = audio.metadata.description,
            year = audio.metadata.year,
            totalDurationMs = audio.metadata.durationMs,
            totalFileSize = audio.file.fileSize,
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            backgroundColorArgb = cover?.backgroundColor
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
            listOf(defaultChapter(bookId, fileId, 0, title, audio.metadata.durationMs, AudiobookSchema.ChapterSource.GENERATED))
        }
        return BookDraft(book, listOf(file), chapters)
    }

    private suspend fun buildManifestDraft(
        sourceType: String,
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
        inventory: FileInventory
    ): BookDraft {
        val bookId = UUID.randomUUID().toString()
        val cover = resolveCover(bookId, primaryAudio = null, manifestFile = sourceFile, fallbackAudio = audioFiles, inventory = inventory)
        val durationByUri = audioFiles.associate { it.uri to (fileDurations[it.uri] ?: readDuration(it.uri)) }
        val audioBookFiles = audioFiles.mapIndexed { index, ref ->
            ref.toBookFile(bookId, UUID.randomUUID().toString(), index, AudiobookSchema.FileStatus.READY, durationByUri[ref.uri] ?: 0L)
        }
        val manifestFile = sourceFile.toManifestBookFile(bookId, UUID.randomUUID().toString())
        val fileIdByUri = audioBookFiles.associate { it.uri to it.id }
        val fileStartByUri = mutableMapOf<String, Long>()
        var start = 0L
        audioBookFiles.forEach { file ->
            fileStartByUri[file.uri] = start
            start += file.durationMs
        }

        val chapters = if (chapterCandidates.isNotEmpty()) {
            chapterCandidates.mapIndexed { index, chapter ->
                val fileId = fileIdByUri[chapter.fileUri].orEmpty()
                val fileDuration = durationByUri[chapter.fileUri] ?: 0L
                val fallbackDuration = nextChapterOffset(chapterCandidates, index) ?: (fileDuration - chapter.fileOffsetMs)
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = fileId,
                    index = index,
                    title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                    startPositionMs = (fileStartByUri[chapter.fileUri] ?: 0L) + chapter.fileOffsetMs,
                    durationMs = (if (chapter.durationMs > 0L) chapter.durationMs else fallbackDuration).coerceAtLeast(0L),
                    fileOffsetMs = chapter.fileOffsetMs,
                    source = sourceType
                )
            }
        } else {
            var chapterStart = 0L
            audioBookFiles.mapIndexed { index, file ->
                val chapterTitle = fileTitles[file.uri]?.ifBlank { null } ?: file.displayName.substringBeforeLast('.')
                val chapter = ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = file.id,
                    index = index,
                    title = chapterTitle,
                    startPositionMs = chapterStart,
                    durationMs = file.durationMs,
                    fileOffsetMs = 0L,
                    source = sourceType
                )
                chapterStart += file.durationMs
                chapter
            }
        }

        val book = BookEntity(
            id = bookId,
            rootId = sourceFile.rootId,
            sourceType = sourceType,
            title = title.ifBlank { sourceFile.displayName.substringBeforeLast('.') },
            author = author.trim(),
            narrator = narrator.trim(),
            description = description,
            year = year,
            totalDurationMs = audioBookFiles.sumOf { it.durationMs },
            totalFileSize = audioBookFiles.sumOf { it.fileSize } + manifestFile.fileSize,
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            backgroundColorArgb = cover?.backgroundColor
        )
        return BookDraft(book, listOf(manifestFile) + audioBookFiles, chapters)
    }

    private suspend fun buildGeneratedDraft(source: ImportSourceRef, files: List<AudioMetadataRef>, inventory: FileInventory): BookDraft {
        val bookId = UUID.randomUUID().toString()
        val cover = resolveCover(bookId, primaryAudio = files.first().file, manifestFile = null, fallbackAudio = files.drop(1).map { it.file }, inventory = inventory)
        val manifestJson = files.joinToString(prefix = "[", postfix = "]") { "\"${it.file.uri}\"" }
        val bookFiles = files.mapIndexed { index, audio ->
            audio.toBookFile(bookId, UUID.randomUUID().toString(), index, AudiobookSchema.FileStatus.READY)
        }
        var chapterStart = 0L
        val chapters = files.mapIndexed { index, audio ->
            val file = bookFiles[index]
            val title = audio.metadata.title.ifBlank { audio.file.displayName.substringBeforeLast('.') }
            val chapter = ChapterEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                bookFileId = file.id,
                index = index,
                title = title.ifBlank { "Chapter ${index + 1}" },
                startPositionMs = chapterStart,
                durationMs = file.durationMs,
                fileOffsetMs = 0L,
                source = AudiobookSchema.ChapterSource.GENERATED
            )
            chapterStart += file.durationMs
            chapter
        }
        val book = BookEntity(
            id = bookId,
            rootId = files.first().file.rootId,
            sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
            generatedManifestJson = manifestJson,
            heuristicRuleVersion = HEURISTIC_RULE_VERSION,
            title = source.displayName,
            author = commonNonBlank(files.map { it.metadata.author }),
            narrator = commonNonBlank(files.map { it.metadata.narrator }),
            totalDurationMs = bookFiles.sumOf { it.durationMs },
            totalFileSize = bookFiles.sumOf { it.fileSize },
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            backgroundColorArgb = cover?.backgroundColor
        )
        return BookDraft(book, bookFiles, chapters)
    }

    private fun FileRef.toManifestBookFile(bookId: String, id: String): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.SOURCE_MANIFEST,
            index = 0,
            uri = uri,
            documentId = documentId,
            relativePath = relativePath,
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
        status: String,
        overrideDurationMs: Long? = null
    ): BookFileEntity = file.toAudioBookFile(bookId, id, index, status, overrideDurationMs ?: metadata.durationMs)

    private fun FileRef.toBookFile(
        bookId: String,
        id: String,
        index: Int,
        status: String,
        overrideDurationMs: Long? = null
    ): BookFileEntity = toAudioBookFile(bookId, id, index, status, overrideDurationMs ?: 0L)

    private fun FileRef.toAudioBookFile(
        bookId: String,
        id: String,
        index: Int,
        status: String,
        durationMs: Long
    ): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.AUDIO,
            index = index,
            uri = uri,
            documentId = documentId,
            relativePath = relativePath,
            displayName = displayName,
            durationMs = durationMs,
            fileSize = fileSize,
            lastModified = lastModified,
            status = status
        )

    private fun defaultChapter(bookId: String, fileId: String, index: Int, title: String, duration: Long, source: String): ChapterEntity =
        ChapterEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            bookFileId = fileId,
            index = index,
            title = title.ifBlank { "Chapter ${index + 1}" },
            startPositionMs = 0L,
            durationMs = duration,
            fileOffsetMs = 0L,
            source = source
        )

    private fun createConflict(scanId: String, source: ImportSourceRef, reservation: ReservationResult): ImportCommand.CreatePendingAction {
        val existingBookId = reservation.existingConflicts.firstOrNull()?.bookId
        val actionKey = "CONFLICT:${source.sourceUri}:${reservation.existingConflicts.joinToString { it.id }}:${reservation.runConflicts.joinToString { it.sourceUri }}"
        return ImportCommand.CreatePendingAction(PendingScanActionEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = scanId,
            actionKey = actionKey,
            type = AudiobookSchema.PendingActionType.CONFLICT,
            bookId = existingBookId,
            payloadJson = "{\"sourceUri\":\"${source.sourceUri}\"}",
            message = "Source \"${source.displayName}\" conflicts with an existing or earlier source.",
            lastSeenScanId = scanId
        ))
    }

    private fun maybeRefreshExistingBook(
        claimedIdentities: List<FileIdentity>,
        reservation: ReservationResult,
        context: ImportRunContext
    ): Boolean {
        if (reservation.runConflicts.isNotEmpty()) return false
        val claim = context.existingClaimIndex.completeExistingClaim(claimedIdentities) ?: return false
        // Existing complete manifest claims are refreshed silently so rescans stay idempotent.
        context.refreshedBooks.add(ImportCommand.RefreshExistingBook(claim.bookId, claim.files))
        return true
    }

    private fun createPartial(scanId: String, source: ImportSourceRef, missingCount: Int): ImportCommand.CreatePendingAction =
        ImportCommand.CreatePendingAction(PendingScanActionEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = scanId,
            actionKey = "PARTIAL:${source.sourceUri}:$missingCount",
            type = AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK,
            // Pending rows are current decisions only; resolving/skipping deletes the row.
            payloadJson = "{\"sourceUri\":\"${source.sourceUri}\",\"missingCount\":$missingCount}",
            message = "Source \"${source.displayName}\" is missing $missingCount referenced file(s).",
            lastSeenScanId = scanId
        ))

    private suspend fun resolveCover(
        bookId: String,
        primaryAudio: FileRef?,
        manifestFile: FileRef?,
        fallbackAudio: List<FileRef>,
        inventory: FileInventory
    ): CoverExtractor.CoverResult? {
        // Documented priority: embedded cover, same-directory sidecar image, then first usable audio cover.
        primaryAudio?.let { extractCover(it.uri, bookId)?.takeIf { cover -> cover.hasImage }?.let { cover -> return cover } }
        val sidecarAnchor = manifestFile ?: primaryAudio ?: fallbackAudio.firstOrNull()
        sidecarAnchor?.let { anchor ->
            findDirectoryCover(anchor.parentUri, inventory)?.let { image ->
                coverExtractor.processExternalImage(Uri.parse(image.uri)).takeIf { cover -> cover.hasImage }?.let { cover -> return cover }
            }
        }
        fallbackAudio.forEach { audio ->
            extractCover(audio.uri, bookId)?.takeIf { cover -> cover.hasImage }?.let { cover -> return cover }
        }
        return null
    }

    private val CoverExtractor.CoverResult.hasImage: Boolean
        get() = originalPath != null || thumbnailPath != null

    private fun findDirectoryCover(parentUri: String, inventory: FileInventory): FileRef? {
        val images = inventory.imageFilesByParent[parentUri].orEmpty()
        // Use the same priority names as CoverExtractor directory lookup for scanner-sourced images.
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        return images.firstOrNull { image ->
            val baseName = image.displayName.substringBeforeLast('.').lowercase()
            priorityNames.contains(baseName)
        } ?: images.firstOrNull()
    }

    private suspend fun extractCover(uri: String, bookId: String): CoverExtractor.CoverResult? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                coverExtractor.extractFromRetriever(retriever, bookId)
            } finally {
                retriever.release()
            }
        }.getOrNull()

    private fun readDuration(uri: String): Long =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)

    private fun nextChapterOffset(chapters: List<ChapterCandidate>, index: Int): Long? {
        val current = chapters.getOrNull(index) ?: return null
        return chapters.drop(index + 1).firstOrNull { it.fileUri == current.fileUri }?.fileOffsetMs?.minus(current.fileOffsetMs)
    }

    private fun shouldAggregate(files: List<AudioMetadataRef>): Boolean {
        if (files.size < 2) return false
        val albums = files.map { it.metadata.description.ifBlank { "" } }
        val sameAlbum = albums.all { it.isNotBlank() && it == albums.first() }
        if (files.size == 2) return sameAlbum
        val names = files.map { it.file.displayName }
        return sameAlbum || hasSequentialNames(names)
    }

    private fun hasSequentialNames(names: List<String>): Boolean {
        val regex = Regex("(\\d+)")
        val numbers = names.mapNotNull { regex.findAll(it).lastOrNull()?.value?.toIntOrNull() }
        if (numbers.size != names.size) return false
        return numbers.zipWithNext().all { (a, b) -> b == a + 1 || b > a }
    }

    private fun generatedBookTitle(files: List<AudioMetadataRef>): String {
        val parentName = files.first().file.parentUri.substringAfterLast('/').ifBlank { "Generated audiobook" }
        return commonNonBlank(files.map { it.metadata.title }).ifBlank { parentName }
    }

    private fun commonNonBlank(values: List<String>): String {
        val trimmed = values.map { it.trim() }.filter { it.isNotBlank() }
        return if (trimmed.isNotEmpty() && trimmed.all { it == trimmed.first() }) trimmed.first() else ""
    }

    private fun syntheticRef(uri: String, source: FileRef): FileRef =
        FileRef(
            uri = uri,
            rootId = source.rootId,
            documentId = Uri.parse(uri).lastPathSegment ?: uri,
            relativePath = Uri.parse(uri).lastPathSegment ?: uri,
            parentDocumentId = source.parentDocumentId,
            parentUri = source.parentUri,
            displayName = Uri.parse(uri).lastPathSegment ?: uri,
            fileSize = 0L,
            lastModified = 0L,
            documentFile = source.documentFile,
            parentDocumentFile = source.parentDocumentFile
        )

    private fun isAudioName(value: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
    }

    private data class AudioMetadataRef(
        val file: FileRef,
        val metadata: com.viel.aplayer.media.AudiobookMetadata
    )

    companion object {
        private const val HEURISTIC_RULE_VERSION = "sequence-v1"
    }
}
