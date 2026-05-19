package com.viel.aplayer.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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
import java.io.BufferedInputStream
import java.nio.charset.Charset
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

        // Same-name txt is only read for a newly created CUE book so rescans do not rewrite description.
        val metadata = resolveManifestBookMetadata(
            manifestMetadata = result.metadata,
            firstAudio = firstManifestAudioMetadata(audioRefs),
            sourceFile = cue,
            sidecarDescription = readSameNameTxtDescription(cue)
        )
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
            title = metadata.title,
            author = metadata.author,
            narrator = metadata.narrator,
            year = metadata.year,
            description = metadata.description,
            inventory = context.inventory
        )))
    }

    private suspend fun processM3u8(m3u8: FileRef, context: ImportRunContext, inventory: FileInventory) {
        val result = M3u8ManifestParser.parse(this.context, m3u8.documentFile)
        val items = result.items
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

        // Same-name txt is only read for a newly created M3U8 book so rescans do not rewrite description.
        val metadata = resolveManifestBookMetadata(
            manifestMetadata = result.metadata,
            firstAudio = firstManifestAudioMetadata(audioRefs),
            sourceFile = m3u8,
            sidecarDescription = readSameNameTxtDescription(m3u8)
        )
        val fileTitles = resolved.mapNotNull { (item, uri) -> item.title?.let { uri to it } }.toMap()
        val fileDurations = resolved.mapNotNull { (item, uri) -> item.durationMs?.let { uri to it } }.toMap()
        context.readyImports.add(ImportCommand.CreateReadyBook(buildManifestDraft(
            sourceType = AudiobookSchema.SourceType.M3U8,
            sourceFile = m3u8,
            audioFiles = audioRefs,
            chapterCandidates = emptyList(),
            fileTitles = fileTitles,
            fileDurations = fileDurations,
            title = metadata.title,
            author = metadata.author,
            narrator = metadata.narrator,
            year = metadata.year,
            description = metadata.description,
            inventory = context.inventory
        )))
    }

    private suspend fun processAudioFiles(audioFiles: List<FileRef>, context: ImportRunContext) {
        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        suspend fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (HeuristicAudioAggregator.shouldAggregate(pendingHeuristic)) {
                val files = pendingHeuristic.map { it.file }
                val plan = HeuristicAudioAggregator.buildPlan(pendingHeuristic)
                val source = ImportSourceRef(
                    sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
                    sourceUri = "generated://${files.first().parentUri}/${files.joinToString("-") { it.documentId.hashCode().toString() }}",
                    displayName = plan.title
                )
                val reservation = context.runClaimLedger.reserve(source, files.map { it.identity }, context.existingClaimIndex)
                if (reservation.reserved) {
                    context.readyImports.add(ImportCommand.CreateReadyBook(buildGeneratedDraft(source, plan, context.inventory)))
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
            // Debug scan metadata so Logcat can verify the filename-to-tag mapping used by aggregation.
            logScannedAudioMetadata(audio, metadata)
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

    private fun logScannedAudioMetadata(file: FileRef, metadata: com.viel.aplayer.media.AudiobookMetadata) {
        // Keep the log to one physical line so each scanned file is easy to grep in Logcat.
        Log.i(
            TAG,
            "Scanned audio metadata: " +
                "file=${file.displayName.logValue()}, " +
                "uri=${file.uri.logValue()}, " +
                "title=${metadata.title.logValue()}, " +
                "author=${metadata.author.logValue()}, " +
                "narrator=${metadata.narrator.logValue()}, " +
                "album=${metadata.album.logValue()}, " +
                "trackIndex=${metadata.trackIndex ?: "<blank>"}, " +
                "description=${metadata.description.logValue()}, " +
                "year=${metadata.year.logValue()}, " +
                "durationMs=${metadata.durationMs}, " +
                "chapters=${metadata.chapters.size}"
        )
    }

    private fun String.logValue(): String =
        // Metadata can contain line breaks; escape them instead of splitting one file across many Logcat rows.
        ifBlank { "<blank>" }
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

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
        // Single-file books display album first, then title, then filename without extension.
        val title = singleAudioBookTitle(audio.metadata, audio.file.displayName)

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

    private suspend fun buildGeneratedDraft(source: ImportSourceRef, plan: HeuristicAggregationPlan, inventory: FileInventory): BookDraft {
        val bookId = UUID.randomUUID().toString()
        val orderedFiles = plan.chapters.map { it.audio }
        // Generated books promote author/narrator/year from the first ordered chapter file.
        val firstChapterMetadata = orderedFiles.first().metadata
        val cover = resolveCover(bookId, primaryAudio = orderedFiles.first().file, manifestFile = null, fallbackAudio = orderedFiles.drop(1).map { it.file }, inventory = inventory)
        val manifestJson = orderedFiles.joinToString(prefix = "[", postfix = "]") { "\"${it.file.uri}\"" }
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
        val book = BookEntity(
            id = bookId,
            rootId = orderedFiles.first().file.rootId,
            sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
            generatedManifestJson = manifestJson,
            heuristicRuleVersion = plan.ruleVersion,
            title = plan.title,
            author = firstChapterMetadata.author.trim(),
            narrator = firstChapterMetadata.narrator.trim(),
            year = firstChapterMetadata.year.trim(),
            totalDurationMs = bookFiles.sumOf { it.durationMs },
            totalFileSize = bookFiles.sumOf { it.fileSize },
            coverPath = cover?.originalPath,
            thumbnailPath = cover?.thumbnailPath,
            backgroundColorArgb = cover?.backgroundColor
        )
        // Log the final persisted title so scan metadata logs can be compared with the created book row.
        Log.i(TAG, "Generated audiobook draft: title=${plan.title.logValue()}, source=${source.displayName.logValue()}, files=${orderedFiles.size}")
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

    private fun commonNonBlank(values: List<String>): String {
        val trimmed = values.map { it.trim() }.filter { it.isNotBlank() }
        return if (trimmed.isNotEmpty() && trimmed.all { it == trimmed.first() }) trimmed.first() else ""
    }

    private fun singleAudioBookTitle(metadata: com.viel.aplayer.media.AudiobookMetadata, displayName: String): String =
        // Keep this priority local to single-file books so generated chapter titles can still use ID3 title.
        metadata.album.trim()
            .ifBlank { metadata.title.trim() }
            .ifBlank { displayName.substringBeforeLast('.') }

    private suspend fun firstManifestAudioMetadata(audioRefs: List<FileRef>): ManifestAudioMetadata? {
        // Manifest imports must not pre-parse the whole book; one audio file is enough for metadata fallback.
        val firstAudio = audioRefs.firstOrNull() ?: return null
        return runCatching {
            ManifestAudioMetadata(firstAudio, metadataExtractor.extract(firstAudio.uri.toUri()))
        }.onFailure { error ->
            // A failed fallback read should not block a valid CUE/M3U8 import.
            Log.w(TAG, "Failed to read manifest fallback metadata: ${firstAudio.uri}", error)
        }.getOrNull()
    }

    private fun resolveManifestBookMetadata(
        manifestMetadata: MetadataSuggestion,
        firstAudio: ManifestAudioMetadata?,
        sourceFile: FileRef,
        sidecarDescription: String? = null
    ): ResolvedManifestMetadata =
        // Per-field merge: manifest content wins; same-name txt is a manifest sidecar before audio fallback.
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
            description = firstNonBlank(manifestMetadata.description, sidecarDescription, firstAudio?.metadata?.description)
        )

    private fun readSameNameTxtDescription(sourceFile: FileRef): String? {
        // CUE/M3U8 description can live in a sibling txt named exactly like the manifest basename.
        val baseName = sourceFile.displayName.substringBeforeLast('.', missingDelimiterValue = sourceFile.displayName)
        val txtFile = sourceFile.parentDocumentFile.listFiles().firstOrNull { file ->
            file.isFile &&
                file.name?.substringBeforeLast('.', missingDelimiterValue = file.name.orEmpty()).equals(baseName, ignoreCase = true) &&
                file.name?.substringAfterLast('.', missingDelimiterValue = "").equals("txt", ignoreCase = true)
        } ?: run {
            // Log the miss so filename mismatches are visible while testing sidecar descriptions.
            Log.i(TAG, "No same-name txt description for manifest=${sourceFile.displayName.logValue()}, base=${baseName.logValue()}")
            return null
        }
        return runCatching {
            readTextFile(txtFile.uri)
        }.onFailure { error ->
            // A broken sidecar description should not block a valid manifest import.
            Log.w(TAG, "Failed to read manifest txt description: ${txtFile.uri}", error)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.also { description ->
            // Log only size and names; the description itself may be long user-facing text.
            Log.i(TAG, "Loaded manifest txt description: manifest=${sourceFile.displayName.logValue()}, txt=${txtFile.name.orEmpty().logValue()}, chars=${description.length}")
        } ?: run {
            Log.i(TAG, "Same-name txt description is empty: manifest=${sourceFile.displayName.logValue()}, txt=${txtFile.name.orEmpty().logValue()}")
            null
        }
    }

    private fun readTextFile(uri: Uri): String {
        // Text sidecars are capped before decoding so a mistaken full-book txt cannot slow down scanning.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input).use { buffered ->
                readLimitedBytes(buffered, MAX_DESCRIPTION_BYTES)
            }
        } ?: return ""
        if (bytes.size == MAX_DESCRIPTION_BYTES) {
            // Hitting the byte cap is expected for oversized descriptions; log the truncation point for debugging.
            Log.i(TAG, "Manifest txt description reached ${MAX_DESCRIPTION_BYTES}B read limit: $uri")
        }
        val utf8Text = bytes.decodeUtf8PossiblyTruncated()
        val decoded = when {
            bytes.hasUtf8Bom() -> bytes.copyOfRange(3, bytes.size).decodeUtf8PossiblyTruncated() ?: ""
            utf8Text != null -> utf8Text
            bytes.isValidBig5() -> bytes.toString(Charset.forName("Big5"))
            else -> bytes.toString(Charset.forName("Shift-JIS"))
        }
        return decoded.limitDescriptionChars(uri)
    }

    private fun readLimitedBytes(input: BufferedInputStream, limitBytes: Int): ByteArray {
        // Manual bounded read avoids ByteArray.readBytes() loading an oversized sidecar into memory.
        val buffer = ByteArray(limitBytes)
        var total = 0
        while (total < limitBytes) {
            val count = input.read(buffer, total, limitBytes - total)
            if (count == -1) break
            total += count
        }
        return buffer.copyOf(total)
    }

    private fun String.limitDescriptionChars(uri: Uri): String {
        // The database stores only a short summary; longer txt sidecars are clipped deterministically.
        if (length <= MAX_DESCRIPTION_CHARS) return this
        Log.i(TAG, "Manifest txt description truncated to $MAX_DESCRIPTION_CHARS chars: $uri")
        return take(MAX_DESCRIPTION_CHARS)
    }

    private fun ByteArray.decodeUtf8PossiblyTruncated(): String? {
        // The 2KB byte cap can cut a UTF-8 character at the end, so retry after dropping up to one partial code point.
        if (isEmpty()) return ""
        for (endExclusive in size downTo maxOf(1, size - MAX_UTF8_CODE_POINT_BYTES + 1)) {
            val candidate = copyOfRange(0, endExclusive)
            if (candidate.isValidUtf8()) return candidate.toString(Charsets.UTF_8)
        }
        return null
    }

    private fun ByteArray.hasUtf8Bom(): Boolean =
        // UTF-8 BOM is stripped so Book.description does not start with an invisible marker.
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

    private fun ByteArray.isValidUtf8(): Boolean {
        // Small UTF-8 validator keeps txt sidecar decoding deterministic without adding dependencies.
        var index = 0
        while (index < size) {
            val byte = this[index].toInt() and 0xFF
            if (byte < 0x80) {
                index++
                continue
            }
            val continuationCount = when {
                byte in 0xC2..0xDF -> 1
                byte in 0xE0..0xEF -> 2
                byte in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (index + continuationCount >= size) return false
            for (offset in 1..continuationCount) {
                if ((this[index + offset].toInt() and 0xC0) != 0x80) return false
            }
            index += continuationCount + 1
        }
        return true
    }

    private fun ByteArray.isValidBig5(): Boolean {
        // Big5 is tried after UTF-8 and before Shift-JIS for Traditional Chinese sidecar text.
        var index = 0
        var hasBig5Pair = false
        while (index < size) {
            val first = this[index].toInt() and 0xFF
            if (first <= 0x7F) {
                index++
                continue
            }
            if (first !in 0x81..0xFE || index + 1 >= size) return false
            val second = this[index + 1].toInt() and 0xFF
            if (second !in 0x40..0x7E && second !in 0xA1..0xFE) return false
            hasBig5Pair = true
            index += 2
        }
        return hasBig5Pair
    }

    private fun firstNonBlank(vararg values: String?): String =
        // Trimming here keeps BookEntity fields clean no matter whether they came from manifest or audio tags.
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private data class ManifestAudioMetadata(
        // Keep the file with the metadata so fallback behavior can stay tied to the first manifest item.
        val file: FileRef,
        val metadata: com.viel.aplayer.media.AudiobookMetadata
    )

    private data class ResolvedManifestMetadata(
        // Resolved values are non-null because buildManifestDraft writes them directly into BookEntity.
        val title: String,
        val author: String,
        val narrator: String,
        val year: String,
        val description: String
    )

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

    companion object {
        // Stable tag for filtering scan metadata lines in Logcat.
        private const val TAG = "ImportOrchestrator"
        // Same-name txt sidecars are descriptions only, so scanning reads at most 2 KiB.
        private const val MAX_DESCRIPTION_BYTES = 2 * 1024
        // Description text persisted to books.description is capped to keep DB/UI rendering lightweight.
        private const val MAX_DESCRIPTION_CHARS = 2_000
        // UTF-8 code points can span four bytes; this bounds truncation repair at the read limit.
        private const val MAX_UTF8_CODE_POINT_BYTES = 4
    }
}
