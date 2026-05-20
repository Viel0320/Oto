package com.viel.aplayer.library.orchestrator.steps

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
import com.viel.aplayer.library.AudioMetadataRef
import com.viel.aplayer.library.BookDraft
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.HeuristicAggregationPlan
import com.viel.aplayer.library.ImportCommand
import com.viel.aplayer.library.ImportFailure
import com.viel.aplayer.library.ImportRunResult
import com.viel.aplayer.library.ImportSourceRef
import com.viel.aplayer.library.manifest.CueManifestParser
// 详尽的中文注释：修复 ChapterCandidate 与 MetadataSuggestion 的包名导入错误，以正确找到在 com.viel.aplayer.library 下定义的类型
import com.viel.aplayer.library.ChapterCandidate
import com.viel.aplayer.library.MetadataSuggestion
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.media.CoverExtractor
import com.viel.aplayer.media.MetadataExtractor
import java.io.BufferedInputStream
import java.nio.charset.Charset
import java.util.UUID

/**
 * 冲突所有权认领决策工位
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本工位不修改任何真实的数据库数据。它只负责判定内存认领账本（ClaimLedger）。
 * 决定当前导入的有声书是能够一路绿灯通过认领直接落库，还是默默刷新所有权，
 * 抑或是进入 PendingAction 冲突挂起队列由用户后续决定。
 * 此外，本步骤也完成了 BookDraft/BookEntity/BookFileEntity/ChapterEntity 的全部映射构建，
 * 并打包返回底盘所需的 ImportRunResult 实例，无缝适配 BookImporter 和 RescanCoordinator。
 */
// 详尽的中文注释：将类可见性声明为 internal，收紧其在本模块内的使用范围，防止由于引用其他 internal 类型而报 public 泄漏错误
internal class ConflictClaimStep(
    private val context: Context,
    private val metadataExtractor: MetadataExtractor = MetadataExtractor(context)
) : ImportStep<CoverExtractedResult, ImportRunResult> {

    override val stepName: String = "ConflictClaimStep"

    override suspend fun execute(
        input: CoverExtractedResult,
        context: ImportContext
    ): StepResult<ImportRunResult> = runCatching {
        val readyImports = mutableListOf<ImportCommand.CreateReadyBook>()
        val refreshedBooks = mutableListOf<ImportCommand.RefreshExistingBook>()
        val pendingActions = mutableListOf<ImportCommand.CreatePendingAction>()
        val failures = mutableListOf<ImportCommand.RecordFailure>()

        // 详尽的中文注释：FileInventory 没有无参的默认构造函数，需要使用其带 5 个参数的显示构造函数传入空数据降级
        val inventory = context.sharedInventory ?: FileInventory(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        // ==========================================
        // 1. 处理 CUE 类型的有声书草稿冲突认领决策
        // ==========================================
        input.cueBooks.forEach { cueBook ->
            val cue = cueBook.draft.sourceFile
            val missingCount = cueBook.draft.missingCount

            if (cueBook.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(cue.uri, "CUE references no resolvable audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.CUE, cue.uri, cue.displayName)
            val claimedIdentities = cueBook.audioRefs.map { it.identity } + cue.identity
            val reservation = context.runClaimLedger.reserve(source, claimedIdentities, context.existingClaimIndex)
            
            if (!reservation.reserved) {
                // 如果发现是已扫描的完整 CUE 并且未发生其他批次内冲突，静默更新所有权
                if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context, refreshedBooks)) return@forEach
                pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
                return@forEach
            }
            if (missingCount > 0) {
                pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
                return@forEach
            }

            // 合并元数据
            val sidecarDesc = readSameNameTxtDescription(cue)
            val firstAudioMeta = firstManifestAudioMetadata(cueBook.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = cueBook.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = cue,
                sidecarDescription = sidecarDesc
            )

            val entryToUri = cueBook.draft.resolvedAudioUris
            val chapters = cueBook.draft.result.chapters.mapNotNull { chapter ->
                entryToUri[chapter.fileUri]?.let { chapter.copy(fileUri = it) }
            }

            val draft = buildManifestDraft(
                bookId = cueBook.bookId,
                sourceType = AudiobookSchema.SourceType.CUE,
                sourceFile = cue,
                audioFiles = cueBook.audioRefs,
                chapterCandidates = chapters,
                fileTitles = emptyMap(),
                fileDurations = emptyMap(),
                title = mergedMeta.title,
                author = mergedMeta.author,
                narrator = mergedMeta.narrator,
                year = mergedMeta.year,
                description = mergedMeta.description,
                // 详尽的中文注释：修复形参名字拼写错误，由 coverResult 修正为函数声明的 cover
                cover = cueBook.coverResult,
                inventory = inventory
            )
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        // ==========================================
        // 2. 处理 M3U8 类型的有声书草稿冲突认领决策
        // ==========================================
        input.m3u8Books.forEach { m3u8Book ->
            val m3u8 = m3u8Book.draft.sourceFile
            val missingCount = m3u8Book.draft.missingCount

            if (m3u8Book.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.uri, "M3U8 references no resolvable local audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.uri, m3u8.displayName)
            val claimedIdentities = m3u8Book.audioRefs.map { it.identity } + m3u8.identity
            val reservation = context.runClaimLedger.reserve(source, claimedIdentities, context.existingClaimIndex)
            
            if (!reservation.reserved) {
                if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context, refreshedBooks)) return@forEach
                pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
                return@forEach
            }
            if (missingCount > 0) {
                pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
                return@forEach
            }

            val sidecarDesc = readSameNameTxtDescription(m3u8)
            val firstAudioMeta = firstManifestAudioMetadata(m3u8Book.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = m3u8Book.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = m3u8,
                sidecarDescription = sidecarDesc
            )

            val resolved = m3u8Book.draft.result.items.distinctBy { it.uri }.mapNotNull { item ->
                m3u8Book.draft.resolvedAudioUris[item.uri]?.let { uri -> item to uri }
            }
            val fileTitles = resolved.mapNotNull { (item, uri) -> item.title?.let { uri to it } }.toMap()
            val fileDurations = resolved.mapNotNull { (item, uri) -> item.durationMs?.let { uri to it } }.toMap()

            val draft = buildManifestDraft(
                bookId = m3u8Book.bookId,
                sourceType = AudiobookSchema.SourceType.M3U8,
                sourceFile = m3u8,
                audioFiles = m3u8Book.audioRefs,
                chapterCandidates = emptyList(),
                fileTitles = fileTitles,
                fileDurations = fileDurations,
                title = mergedMeta.title,
                author = mergedMeta.author,
                narrator = mergedMeta.narrator,
                year = mergedMeta.year,
                description = mergedMeta.description,
                // 详尽的中文注释：修复形参名字拼写错误，由 coverResult 修正为函数声明的 cover
                cover = m3u8Book.coverResult,
                inventory = inventory
            )
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        // ==========================================
        // 3. 处理 Heuristic 启发式合并有声书冲突认领决策
        // ==========================================
        input.aggregatedBooks.forEach { aggBook ->
            val orderedFiles = aggBook.plan.chapters.map { it.audio }
            val firstChapter = orderedFiles.first()
            val source = ImportSourceRef(
                sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
                sourceUri = "generated://${firstChapter.file.parentUri}/${orderedFiles.joinToString("-") { it.file.documentId.hashCode().toString() }}",
                displayName = aggBook.plan.title
            )
            
            val reservation = context.runClaimLedger.reserve(source, orderedFiles.map { it.file.identity }, context.existingClaimIndex)
            if (reservation.reserved) {
                val draft = buildGeneratedDraft(aggBook.bookId, source, aggBook.plan, aggBook.coverResult)
                readyImports.add(ImportCommand.CreateReadyBook(draft))
            } else {
                pendingActions.add(createConflict(context.scanId, source, reservation))
            }
        }

        // ==========================================
        // 4. 处理 Single Audio 独立单音频有声书冲突认领决策
        // ==========================================
        input.singleBooks.forEach { singleBook ->
            val audio = singleBook.audioRef
            val source = ImportSourceRef(AudiobookSchema.SourceType.SINGLE_AUDIO, audio.file.uri, audio.file.displayName)
            val reservation = context.runClaimLedger.reserve(source, listOf(audio.file.identity), context.existingClaimIndex)
            
            if (!reservation.reserved) {
                pendingActions.add(createConflict(context.scanId, source, reservation))
                return@forEach
            }

            val draft = buildSingleAudioDraft(singleBook.bookId, audio, singleBook.coverResult)
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        val runResult = ImportRunResult(
            scanId = context.scanId,
            readyImports = readyImports,
            refreshedBooks = refreshedBooks,
            pendingActions = pendingActions,
            failures = failures
        )
        StepResult.Success(runResult)
    }.getOrElse { e ->
        StepResult.Failure(e, "所有权认领决策引擎遭遇未捕获异常，详情: ${e.localizedMessage}")
    }

    // =========================================================================
    // 5. 以下为与原 ImportOrchestrator 严格等价的所有权与 Draft 拼装底层逻辑
    // =========================================================================

    private fun maybeRefreshExistingBook(
        claimedIdentities: List<FileIdentity>,
        reservation: com.viel.aplayer.library.ReservationResult,
        context: ImportContext,
        refreshedBooks: MutableList<ImportCommand.RefreshExistingBook>
    ): Boolean {
        if (reservation.runConflicts.isNotEmpty()) return false
        val claim = context.existingClaimIndex.completeExistingClaim(claimedIdentities) ?: return false
        refreshedBooks.add(ImportCommand.RefreshExistingBook(claim.bookId, claim.files))
        return true
    }

    private fun createConflict(scanId: String, source: ImportSourceRef, reservation: com.viel.aplayer.library.ReservationResult): ImportCommand.CreatePendingAction {
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

    private fun createPartial(scanId: String, source: ImportSourceRef, missingCount: Int): ImportCommand.CreatePendingAction =
        ImportCommand.CreatePendingAction(PendingScanActionEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = scanId,
            actionKey = "PARTIAL:${source.sourceUri}:$missingCount",
            type = AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK,
            payloadJson = "{\"sourceUri\":\"${source.sourceUri}\",\"missingCount\":$missingCount}",
            message = "Source \"${source.displayName}\" is missing $missingCount referenced file(s).",
            lastSeenScanId = scanId
        ))

    private fun buildSingleAudioDraft(bookId: String, audio: AudioMetadataRef, cover: CoverExtractor.CoverResult?): BookDraft {
        val fileId = UUID.randomUUID().toString()
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
        bookId: String,
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
        cover: CoverExtractor.CoverResult?,
        inventory: FileInventory
    ): BookDraft {
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

    private fun buildGeneratedDraft(bookId: String, source: ImportSourceRef, plan: HeuristicAggregationPlan, cover: CoverExtractor.CoverResult?): BookDraft {
        val orderedFiles = plan.chapters.map { it.audio }
        val firstChapterMetadata = orderedFiles.first().metadata
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
        Log.i(TAG, "Generated audiobook draft: title=${plan.title.logValue()}, source=${source.displayName.logValue()}, files=${orderedFiles.size}")
        return BookDraft(book, bookFiles, chapters)
    }

    // ==========================================
    // 6. 辅助转化扩展方法与辅助计算
    // ==========================================

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

    private fun singleAudioBookTitle(metadata: com.viel.aplayer.media.AudiobookMetadata, displayName: String): String =
        metadata.album.trim()
            .ifBlank { metadata.title.trim() }
            .ifBlank { displayName.substringBeforeLast('.') }

    private suspend fun firstManifestAudioMetadata(audioRefs: List<FileRef>): ManifestAudioMetadata? {
        val firstAudio = audioRefs.firstOrNull() ?: return null
        return runCatching {
            ManifestAudioMetadata(firstAudio, metadataExtractor.extract(firstAudio.uri.toUri()))
        }.onFailure { error ->
            Log.w(TAG, "Failed to read manifest fallback metadata: ${firstAudio.uri}", error)
        }.getOrNull()
    }

    private fun resolveManifestBookMetadata(
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
            description = firstNonBlank(manifestMetadata.description, sidecarDescription, firstAudio?.metadata?.description)
        )

    private fun readSameNameTxtDescription(sourceFile: FileRef): String? {
        val baseName = sourceFile.displayName.substringBeforeLast('.', missingDelimiterValue = sourceFile.displayName)
        val txtFile = sourceFile.parentDocumentFile.listFiles().firstOrNull { file ->
            file.isFile &&
                file.name?.substringBeforeLast('.', missingDelimiterValue = file.name.orEmpty()).equals(baseName, ignoreCase = true) &&
                file.name?.substringAfterLast('.', missingDelimiterValue = "").equals("txt", ignoreCase = true)
        } ?: run {
            Log.i(TAG, "No same-name txt description for manifest=${sourceFile.displayName.logValue()}, base=${baseName.logValue()}")
            return null
        }
        return runCatching {
            readTextFile(txtFile.uri)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read manifest txt description: ${txtFile.uri}", error)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.also { description ->
            Log.i(TAG, "Loaded manifest txt description: manifest=${sourceFile.displayName.logValue()}, txt=${txtFile.name.orEmpty().logValue()}, chars=${description.length}")
        } ?: run {
            Log.i(TAG, "Same-name txt description is empty: manifest=${sourceFile.displayName.logValue()}, txt=${txtFile.name.orEmpty().logValue()}")
            null
        }
    }

    private fun readTextFile(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input).use { buffered ->
                readLimitedBytes(buffered, MAX_DESCRIPTION_BYTES)
            }
        } ?: return ""
        if (bytes.size == MAX_DESCRIPTION_BYTES) {
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
        if (length <= MAX_DESCRIPTION_CHARS) return this
        Log.i(TAG, "Manifest txt description truncated to $MAX_DESCRIPTION_CHARS chars: $uri")
        return take(MAX_DESCRIPTION_CHARS)
    }

    private fun ByteArray.decodeUtf8PossiblyTruncated(): String? {
        if (isEmpty()) return ""
        for (endExclusive in size downTo maxOf(1, size - MAX_UTF8_CODE_POINT_BYTES + 1)) {
            val candidate = copyOfRange(0, endExclusive)
            if (candidate.isValidUtf8()) return candidate.toString(Charsets.UTF_8)
        }
        return null
    }

    private fun ByteArray.hasUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

    private fun ByteArray.isValidUtf8(): Boolean {
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
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun String.logValue(): String =
        ifBlank { "<blank>" }
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private data class ManifestAudioMetadata(
        val file: FileRef,
        val metadata: com.viel.aplayer.media.AudiobookMetadata
    )

    private data class ResolvedManifestMetadata(
        val title: String,
        val author: String,
        val narrator: String,
        val year: String,
        val description: String
    )

    companion object {
        private const val TAG = "ConflictClaimStep"
        private const val MAX_DESCRIPTION_BYTES = 2 * 1024
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private const val MAX_UTF8_CODE_POINT_BYTES = 4
    }
}
