package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity
import com.viel.aplayer.library.BookDraft
import com.viel.aplayer.library.ChapterCandidate
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.ImportCommand
import com.viel.aplayer.library.ImportFailure
import com.viel.aplayer.library.ImportRunResult
import com.viel.aplayer.library.ImportSourceRef
import com.viel.aplayer.library.MetadataSuggestion
import com.viel.aplayer.library.ReservationResult
import com.viel.aplayer.library.mapWithBoundedConcurrency
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.vfsFileKey
import com.viel.aplayer.media.AudiobookMetadata
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.parser.Mp4MetadataFrameReader
import java.util.UUID

// 修复 ChapterCandidate 与 MetadataSuggestion 的包名导入错误，以正确找到在 com.viel.aplayer.library 下定义的类型

/**
 * 冲突所有权认领决策工位
 * 
 * 本工位不修改任何真实的数据库数据。它只负责判定内存认领账本（ClaimLedger）。
 * 决定当前导入的有声书是能够一路绿灯通过认领直接落库，还是默默刷新所有权，
 * 抑或是进入 PendingAction 冲突挂起队列由用户后续决定。
 * 此外，本步骤也完成了 BookDraft/BookEntity/BookFileEntity/ChapterEntity 的全部映射构建，
 * 并打包返回底盘所需的 ImportRunResult 实例，无缝适配 BookImporter 和 RescanCoordinator。
 */
// 将类可见性声明为 internal，收紧其在本模块内的使用范围，防止由于引用其他 internal 类型而报 public 泄漏错误
@OptIn(UnstableApi::class)
internal class ConflictClaimStep(
    private val context: Context,
    private val metadataResolver: MetadataResolver = MetadataResolver(context)
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


        // ==========================================
        // 1. 处理 CUE 类型的有声书草稿冲突认领决策
        // ==========================================
        input.cueBooks.forEach { cueBook ->
            val cue = cueBook.draft.sourceFile
            val missingCount = cueBook.draft.missingCount

            if (cueBook.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(cue.vfsDisplayId(), "CUE references no resolvable audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.CUE, cue.vfsDisplayId(), cue.displayName)
            val claimedIdentities = cueBook.audioRefs.map { it.identity } + cue.identity
            // 对 CUE 书籍进行所有权抢占检测时，透传其 VFS 父目录路径以限制在同目录下进行冲突判定。
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = claimedIdentities,
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = cue.parentSourcePath
            )
            
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
            val firstAudioMeta = firstManifestAudioMetadata(cueBook.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = cueBook.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = cue,
                // manifest 书籍的 txt 简介已经在 parser 内部通过目录快照选出，
                // 这里直接消费 parser 结果，不再重新 listChildren。
                sidecarDescription = cueBook.draft.result.sidecarDescription
            )

            val entryToKey = cueBook.draft.resolvedAudioKeys
            val chapters = cueBook.draft.result.chapters.mapNotNull { chapter ->
                entryToKey[chapter.fileKey]?.let { chapter.copy(fileKey = it) }
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
                // 修复形参名字拼写错误，由 coverResult 修正为函数声明的 cover
                cover = cueBook.coverResult
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
                failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.vfsDisplayId(), "M3U8 references no resolvable local audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.vfsDisplayId(), m3u8.displayName)
            val claimedIdentities = m3u8Book.audioRefs.map { it.identity } + m3u8.identity
            // 对 M3U8 书籍进行所有权抢占检测时，透传其 VFS 父目录路径以限制在同目录下进行冲突判定。
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = claimedIdentities,
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = m3u8.parentSourcePath
            )
            
            if (!reservation.reserved) {
                if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context, refreshedBooks)) return@forEach
                pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
                return@forEach
            }
            if (missingCount > 0) {
                pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
                return@forEach
            }

            val firstAudioMeta = firstManifestAudioMetadata(m3u8Book.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = m3u8Book.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = m3u8,
                sidecarDescription = m3u8Book.draft.result.sidecarDescription
            )

            val resolved = m3u8Book.draft.result.items.distinctBy { it.uri }.mapNotNull { item ->
                m3u8Book.draft.resolvedAudioKeys[item.uri]?.let { fileKey -> item to fileKey }
            }
            val fileTitles = resolved.mapNotNull { (item, fileKey) -> item.title?.let { fileKey to it } }.toMap()
            val fileDurations = resolved.mapNotNull { (item, fileKey) -> item.durationMs?.let { fileKey to it } }.toMap()

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
                // 修复形参名字拼写错误，由 coverResult 修正为函数声明的 cover
                cover = m3u8Book.coverResult
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
                sourceUri = "generated://${firstChapter.file.parentSourceKey}/${orderedFiles.joinToString("-") { it.file.sourceIdentity.hashCode().toString() }}",
                displayName = aggBook.plan.title
            )
            
            // 对启发式聚合书籍进行所有权抢占检测时，透传首轨音频所在的 VFS 父目录路径。
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = orderedFiles.map { it.file.identity },
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = firstChapter.file.parentSourcePath
            )
            if (reservation.reserved) {
                // 启发式聚合书籍的 sidecar 描述现在由 HeuristicAudioAggregator 在 parser 内部统一产出，
                // 冲突认领阶段只做“已有音频 metadata -> parser sidecar”的最终合并，不再自己回头读 txt。
                val firstAudioMeta = firstChapter.metadata
                val description = firstAudioMeta.description.ifBlank {
                    aggBook.plan.sidecarDescription.orEmpty()
                }

                val draft = buildGeneratedDraft(aggBook.bookId, source, aggBook.plan, description, aggBook.coverResult)
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
            val source = ImportSourceRef(AudiobookSchema.SourceType.SINGLE_AUDIO, audio.file.vfsDisplayId(), audio.file.displayName)
            // 对单音频书籍进行所有权抢占检测时，透传其所在的 VFS 父目录路径。
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = listOf(audio.file.identity),
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = audio.file.parentSourcePath
            )
            
            if (!reservation.reserved) {
                pendingActions.add(createConflict(context.scanId, source, reservation))
                return@forEach
            }

            // 单音频模式现在显式禁止 sidecar 描述兜底，
            // 最终 description 只来自音频自身 metadata，不再读取任何同目录 txt 文件。
            val description = audio.metadata.description

            val draft = buildSingleAudioDraft(singleBook.bookId, audio, description, singleBook.coverResult)
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
        reservation: ReservationResult,
        context: ImportContext,
        refreshedBooks: MutableList<ImportCommand.RefreshExistingBook>
    ): Boolean {
        if (reservation.runConflicts.isNotEmpty()) return false
        val claim = context.existingClaimIndex.completeExistingClaim(claimedIdentities) ?: return false
        refreshedBooks.add(ImportCommand.RefreshExistingBook(claim.bookId, claim.files))
        return true
    }

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

    // 重写单音频草稿构建方法，接收读取到的描述（description）并写入逻辑书籍实体的描述字段中
    private fun buildSingleAudioDraft(
        bookId: String,
        audio: AudioMetadataRef,
        description: String,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val fileId = UUID.randomUUID().toString()
        val title = singleAudioBookTitle(audio.metadata, audio.file.displayName)

        val book = BookEntity(
            id = bookId,
            rootId = audio.file.rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            // 单音频模式下使用 VFS 父目录键作为 sourceRoot，不再保存 SAF 父目录 Uri。
            sourceRoot = audio.file.parentSourceKey,
            title = title,
            author = audio.metadata.author.trim(),
            narrator = audio.metadata.narrator.trim(),
            description = description.trim(), // 显式将从 txt 检索到或从 ID3 元数据中读取到的描述（description）赋给逻辑书籍的 description 字段
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
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        // manifest 已经完成 claim 预留后，再按 VFS 文件键并发读取缺失时长，不再通过 URI 定位音频。
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
                    source = sourceType
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
            // 清单书籍模式下使用源清单所在 VFS 父目录键作为 sourceRoot。
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
            backgroundColorArgb = cover?.backgroundColor
        )
        return BookDraft(book, listOf(manifestFile) + audioBookFiles, chapters)
    }

    // 重写启发式聚合草稿构建方法，接收读取到的描述（description）并将其写入 logical 书籍实体的描述字段中
    private fun buildGeneratedDraft(
        bookId: String,
        source: ImportSourceRef,
        plan: HeuristicAggregationPlan,
        description: String,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val orderedFiles = plan.chapters.map { it.audio }
        val firstChapterMetadata = orderedFiles.first().metadata
        // 生成清单只记录 VFS 文件键，避免 generatedManifestJson 再持久化 provider URI。
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
        val book = BookEntity(
            id = bookId,
            rootId = orderedFiles.first().file.rootId,
            sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
            // 启发式聚合书籍模式下以首轨音频文件所在 VFS 父目录键作为 sourceRoot。
            sourceRoot = orderedFiles.first().file.parentSourceKey,
            generatedManifestJson = manifestJson,
            heuristicRuleVersion = plan.ruleVersion,
            title = plan.title,
            author = firstChapterMetadata.author.trim(),
            narrator = firstChapterMetadata.narrator.trim(),
            description = description.trim(), // 显式将从 txt 检索到或从 ID3 元数据中读取到的描述（description）赋给逻辑书籍的 description 字段
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
            // BookFileEntity 只写入 VFS sourcePath/sourceIdentity，不再持久化 SAF 专属旧列。
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
            // 音频 BookFileEntity 同样只持久化 VFS 定位字段。
            sourcePath = sourcePath,
            sourceIdentity = sourceIdentity,
            etag = etag,
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

    @OptIn(UnstableApi::class)
    private suspend fun readDuration(file: FileRef): Long =
        // 清单音频补时长复用 VFS 元数据入口，不再把 FileRef 还原成 provider URI。
        runCatching {
            // 补时长优先走 MetadataResolver 的 MP4/M4B Range 元数据帧解析，避免 WebDAV 清单音频回落到整文件 FD。
            val metadataDuration = metadataResolver.extract(file).durationMs
            if (Mp4MetadataFrameReader.supports(file.displayName)) {
                // MP4 家族补时长不再使用 FD/retriever 后备，避免本地 m4b 因清单兜底路径扫描整文件。
                return@runCatching metadataDuration
            }
            metadataDuration.takeIf { it > 0L }?.let { return@runCatching it }
            /*
                // 局部代理只为了平滑去掉 VfsFileReader 的兼容 helper；
                // 实际时长仍然直接回到 MetadataResolver，再由各格式 parser 内部自行做范围读取。
                suspend fun readAudioDuration(target: FileRef): Long = MetadataResolver.extract(target).durationMs
            }
            // 清单补时长只向 VFS 请求 duration 结果，不再在冲突认领层持有 FD 或 retriever。
            reader.readAudioDuration(file)
            */ metadataDuration
        }.getOrDefault(0L)

    private fun nextChapterOffset(chapters: List<ChapterCandidate>, index: Int): Long? {
        val current = chapters.getOrNull(index) ?: return null
        return chapters.drop(index + 1).firstOrNull { it.fileKey == current.fileKey }?.fileOffsetMs?.minus(current.fileOffsetMs)
    }

    private fun singleAudioBookTitle(metadata: AudiobookMetadata, displayName: String): String =
        metadata.album.trim()
            .ifBlank { metadata.title.trim() }
            .ifBlank { displayName.substringBeforeLast('.') }

    @OptIn(UnstableApi::class)
    private suspend fun firstManifestAudioMetadata(audioRefs: List<FileRef>): ManifestAudioMetadata? {
        val firstAudio = audioRefs.firstOrNull() ?: return null
        return runCatching {
            // manifest 兜底元数据从首个音频的 VFS 路径读取，避免 URI 旁路。
            ManifestAudioMetadata(firstAudio, metadataResolver.extract(firstAudio))
        }.onFailure { error ->
            Log.w(TAG, "Failed to read manifest fallback metadata: ${firstAudio.vfsDisplayId()}", error)
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


    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun FileRef.vfsDisplayId(): String =
        // 用户可见/日志来源标识使用 rootId/sourcePath，避免继续输出 provider URI。
        "vfs://$rootId/$sourcePath"

    private fun BookFileEntity.vfsKey(): String =
        // 入库后的章节映射也用 rootId/sourcePath 还原同一个 VFS 文件键。
        vfsFileKey(rootId, sourcePath)

    private fun String.escapeJson(): String =
        // 生成的 VFS manifest JSON 对路径分隔符和引号做最小转义，避免 sourcePath 中特殊字符破坏 JSON。
        replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.logValue(): String =
        ifBlank { "<blank>" }
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private data class ManifestAudioMetadata(
        val file: FileRef,
        val metadata: AudiobookMetadata
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
    }
}
