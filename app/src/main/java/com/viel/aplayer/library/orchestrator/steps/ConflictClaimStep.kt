package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.io.BufferedInputStream
import java.nio.charset.Charset
import java.util.UUID
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity
import com.viel.aplayer.library.AudioMetadataRef
import com.viel.aplayer.library.BookDraft
import com.viel.aplayer.library.ChapterCandidate
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.HeuristicAggregationPlan
import com.viel.aplayer.library.ImportCommand
import com.viel.aplayer.library.ImportFailure
import com.viel.aplayer.library.ImportRunResult
import com.viel.aplayer.library.ImportSourceRef
import com.viel.aplayer.library.MetadataSuggestion
import com.viel.aplayer.library.ReservationResult
import com.viel.aplayer.library.mapWithBoundedConcurrency
import com.viel.aplayer.library.vfsFileKey
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.vfs.VfsFileReader
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.media.AudiobookMetadata
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.parser.Mp4MetadataFrameReader

// 详尽的中文注释：修复 ChapterCandidate 与 MetadataSuggestion 的包名导入错误，以正确找到在 com.viel.aplayer.library 下定义的类型

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
@OptIn(UnstableApi::class)
internal class ConflictClaimStep(
    private val context: Context,
    private val MetadataResolver: MetadataResolver = MetadataResolver(context)
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
        // 为每一次改动添加详尽的中文注释：TXT 描述读取必须用当前扫描 scope 的 roots 映射初始化 VFS reader，否则 listChildren/open 会因找不到 root 直接空返回。
        val fileReader = VfsFileReader(this.context.applicationContext, rootsById = inventory.roots.associateBy { it.id })

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
            // 为每一次改动添加详尽的中文注释：对 CUE 书籍进行所有权抢占检测时，透传其 VFS 父目录路径以限制在同目录下进行冲突判定。
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
            val sidecarDesc = readSameNameTxtDescription(fileReader, cue)
            val firstAudioMeta = firstManifestAudioMetadata(cueBook.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = cueBook.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = cue,
                sidecarDescription = sidecarDesc
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
                failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.vfsDisplayId(), "M3U8 references no resolvable local audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.vfsDisplayId(), m3u8.displayName)
            val claimedIdentities = m3u8Book.audioRefs.map { it.identity } + m3u8.identity
            // 为每一次改动添加详尽的中文注释：对 M3U8 书籍进行所有权抢占检测时，透传其 VFS 父目录路径以限制在同目录下进行冲突判定。
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

            val sidecarDesc = readSameNameTxtDescription(fileReader, m3u8)
            val firstAudioMeta = firstManifestAudioMetadata(m3u8Book.audioRefs)
            val mergedMeta = resolveManifestBookMetadata(
                manifestMetadata = m3u8Book.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = m3u8,
                sidecarDescription = sidecarDesc
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
                sourceUri = "generated://${firstChapter.file.parentSourceKey}/${orderedFiles.joinToString("-") { it.file.sourceIdentity.hashCode().toString() }}",
                displayName = aggBook.plan.title
            )
            
            // 为每一次改动添加详尽的中文注释：对启发式聚合书籍进行所有权抢占检测时，透传首轨音频所在的 VFS 父目录路径。
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = orderedFiles.map { it.file.identity },
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = firstChapter.file.parentSourcePath
            )
            if (reservation.reserved) {
                // 详尽的中文注释：如果首个音频的 metadata 中的 description 为空，则采用增强匹配与模糊兜底机制读取该文件夹下的 txt 文件作为简介
                val firstAudioMeta = firstChapter.metadata
                val description = firstAudioMeta.description.ifBlank {
                    readTxtDescription(
                        fileReader,
                        firstChapter.file,
                        baseName = null,
                        strictSameNameOnly = false
                    ) ?: ""
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
            // 为每一次改动添加详尽的中文注释：对单音频书籍进行所有权抢占检测时，透传其所在的 VFS 父目录路径。
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

            // 详尽的中文注释：针对单音频模式，若音频内置描述为空，则仅查找并匹配与该音频文件严格同名的 txt 描述文件，而不做任何模糊或唯一性兜底
            val description = audio.metadata.description.ifBlank {
                val baseName = audio.file.displayName.substringBeforeLast(
                    '.',
                    missingDelimiterValue = audio.file.displayName
                )
                readTxtDescription(
                    fileReader,
                    audio.file,
                    baseName,
                    strictSameNameOnly = true
                ) ?: ""
            }

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

    // 详尽的中文注释：重写单音频草稿构建方法，接收读取到的描述（description）并写入逻辑书籍实体的描述字段中
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
            // 为每一次改动添加详尽的中文注释：单音频模式下使用 VFS 父目录键作为 sourceRoot，不再保存 SAF 父目录 Uri。
            sourceRoot = audio.file.parentSourceKey,
            title = title,
            author = audio.metadata.author.trim(),
            narrator = audio.metadata.narrator.trim(),
            description = description.trim(), // 详尽的中文注释：显式将从 txt 检索到或从 ID3 元数据中读取到的描述（description）赋给逻辑书籍的 description 字段
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
        // 为每一次改动添加详尽的中文注释：manifest 已经完成 claim 预留后，再按 VFS 文件键并发读取缺失时长，不再通过 URI 定位音频。
        val durationByKey = audioFiles
            .mapWithBoundedConcurrency { file ->
                file.vfsKey to (fileDurations[file.vfsKey] ?: readDuration(file, inventory))
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
            // 为每一次改动添加详尽的中文注释：清单书籍模式下使用源清单所在 VFS 父目录键作为 sourceRoot。
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

    // 详尽的中文注释：重写启发式聚合草稿构建方法，接收读取到的描述（description）并将其写入 logical 书籍实体的描述字段中
    private fun buildGeneratedDraft(
        bookId: String,
        source: ImportSourceRef,
        plan: HeuristicAggregationPlan,
        description: String,
        cover: CoverExtractor.CoverResult?
    ): BookDraft {
        val orderedFiles = plan.chapters.map { it.audio }
        val firstChapterMetadata = orderedFiles.first().metadata
        // 为每一次改动添加详尽的中文注释：生成清单只记录 VFS 文件键，避免 generatedManifestJson 再持久化 provider URI。
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
            // 为每一次改动添加详尽的中文注释：启发式聚合书籍模式下以首轨音频文件所在 VFS 父目录键作为 sourceRoot。
            sourceRoot = orderedFiles.first().file.parentSourceKey,
            generatedManifestJson = manifestJson,
            heuristicRuleVersion = plan.ruleVersion,
            title = plan.title,
            author = firstChapterMetadata.author.trim(),
            narrator = firstChapterMetadata.narrator.trim(),
            description = description.trim(), // 详尽的中文注释：显式将从 txt 检索到或从 ID3 元数据中读取到的描述（description）赋给逻辑书籍的 description 字段
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
            // 为每一次改动添加详尽的中文注释：BookFileEntity 只写入 VFS sourcePath/sourceIdentity，不再持久化 SAF 专属旧列。
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
            // 为每一次改动添加详尽的中文注释：音频 BookFileEntity 同样只持久化 VFS 定位字段。
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
    private suspend fun readDuration(file: FileRef, inventory: FileInventory): Long =
        // 为每一次改动添加详尽的中文注释：清单音频补时长复用 VFS 元数据入口，不再把 FileRef 还原成 provider URI。
        runCatching {
            // 为每一次改动添加详尽的中文注释：补时长优先走 MetadataResolver 的 MP4/M4B Range 元数据帧解析，避免 WebDAV 清单音频回落到整文件 FD。
            val metadataDuration = MetadataResolver.extract(file).durationMs
            if (Mp4MetadataFrameReader.supports(file.displayName)) {
                // 为每一次改动添加详尽的中文注释：MP4 家族补时长不再使用 FD/retriever 后备，避免本地 m4b 因清单兜底路径扫描整文件。
                return@runCatching metadataDuration
            }
            metadataDuration.takeIf { it > 0L }?.let { return@runCatching it }
            /*
                // 详尽的中文注释：局部代理只为了平滑去掉 VfsFileReader 的兼容 helper；
                // 实际时长仍然直接回到 MetadataResolver，再由各格式 parser 内部自行做范围读取。
                suspend fun readAudioDuration(target: FileRef): Long = MetadataResolver.extract(target).durationMs
            }
            // 为每一次改动添加详尽的中文注释：清单补时长只向 VFS 请求 duration 结果，不再在冲突认领层持有 FD 或 retriever。
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
            // 为每一次改动添加详尽的中文注释：manifest 兜底元数据从首个音频的 VFS 路径读取，避免 URI 旁路。
            ManifestAudioMetadata(firstAudio, MetadataResolver.extract(firstAudio))
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

    // 详尽的中文注释：此方法保留原有清单导入对同名 txt 描述文件的检索接口，在内部使用 VFS 目录枚举进行匹配
    private suspend fun readSameNameTxtDescription(fileReader: VfsFileReader, sourceFile: FileRef): String? {
        val baseName = sourceFile.displayName.substringBeforeLast('.', missingDelimiterValue = sourceFile.displayName)
        return readTxtDescription(fileReader, sourceFile, baseName, strictSameNameOnly = false)
    }

    // 为每一次改动添加详尽的中文注释：txt 描述检索改为 VFS 同目录枚举，支持同名优先、简介文件名和单 txt 兜底。
    private suspend fun readTxtDescription(
        fileReader: VfsFileReader,
        sourceFile: FileRef,
        baseName: String? = null,
        strictSameNameOnly: Boolean = false
    ): String? {
        val files = fileReader.listChildren(sourceFile.rootId, sourceFile.parentSourcePath).filter { node ->
            !node.metadata.isDirectory && node.metadata.displayName.substringAfterLast('.', missingDelimiterValue = "").equals("txt", ignoreCase = true)
        }
        if (files.isEmpty()) return null

        // 1. 如果指定了 baseName，优先寻找与 baseName 完全同名（不含后缀）的 txt 文件
        if (!baseName.isNullOrBlank()) {
            val sameNameFile = files.firstOrNull { file ->
                file.metadata.displayName.substringBeforeLast('.', missingDelimiterValue = "").equals(baseName, ignoreCase = true)
            }
            if (sameNameFile != null) {
                return readTxtFileAndLog(fileReader, sameNameFile, "same-name (baseName=$baseName)")
            }
        }

        // 如果开启了 strictSameNameOnly，则只查找同名文件，直接返回 null 不进行兜底匹配
        if (strictSameNameOnly) {
            Log.i(TAG, "Strict same-name match enabled but not found for baseName=$baseName")
            return null
        }

        // 2. 模糊查找常见前缀的 txt 文件
        val commonNames = listOf("desc", "description", "info", "book", "readme", "简介", "有声书简介")
        val commonNameFile = files.firstOrNull { file ->
            val nameWithoutExt = file.metadata.displayName.substringBeforeLast('.', missingDelimiterValue = "").lowercase()
            commonNames.any { common -> nameWithoutExt == common || nameWithoutExt.contains(common) }
        }
        if (commonNameFile != null) {
            return readTxtFileAndLog(fileReader, commonNameFile, "common-name")
        }

        // 3. 兜底匹配：当且仅当目录下只有一个 txt 文件时，直接选用该文件
        if (files.size == 1) {
            return readTxtFileAndLog(fileReader, files.first(), "single-txt-in-folder")
        }

        return null
    }

    // 为每一次改动添加详尽的中文注释：提取通用的 txt 文本读取及日志打印辅助方法，读取入口统一走 VFS stream。
    private suspend fun readTxtFileAndLog(fileReader: VfsFileReader, txtFile: VfsNode, matchedBy: String): String? {
        return runCatching {
            readTextFile(fileReader, txtFile)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read txt description ($matchedBy): ${txtFile.vfsLogId()}", error)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.also { description ->
            Log.i(TAG, "Loaded txt description ($matchedBy): txt=${txtFile.metadata.displayName.logValue()}, chars=${description.length}")
        }
    }

    private suspend fun readTextFile(fileReader: VfsFileReader, file: VfsNode): String {
        var isTruncated = false
        val bytes = fileReader.open(file)?.use { input ->
            BufferedInputStream(input).use { buffered ->
                val result = readLimitedBytes(buffered, MAX_DESCRIPTION_BYTES)
                // 详尽的中文注释：判断是否发生物理字节截断：如果读入的字节数刚好达到 MAX_DESCRIPTION_BYTES，且输入流中还有更多字节，则标记为已截断
                if (result.size == MAX_DESCRIPTION_BYTES) {
                    buffered.mark(1)
                    val nextByte = buffered.read()
                    if (nextByte != -1) {
                        isTruncated = true
                    }
                }
                result
            }
        } ?: return ""
        if (isTruncated) {
            Log.i(TAG, "Manifest txt description reached ${MAX_DESCRIPTION_BYTES}B read limit: ${file.vfsLogId()}")
        }
        val utf8Text = bytes.decodeUtf8PossiblyTruncated()
        val decoded = when {
            bytes.hasUtf8Bom() -> bytes.copyOfRange(3, bytes.size).decodeUtf8PossiblyTruncated() ?: ""
            utf8Text != null -> utf8Text
            bytes.isValidBig5() -> bytes.toString(Charset.forName("Big5"))
            else -> bytes.toString(Charset.forName("Shift-JIS"))
        }

        // 详尽的中文注释：截断判定联动：如果已经发生物理截断，或者解码后的字符总数超过了 MAX_DESCRIPTION_CHARS 上限，均视为发生截断
        val finalIsTruncated = isTruncated || decoded.length > MAX_DESCRIPTION_CHARS
        val baseDescription = decoded.limitDescriptionChars(file.vfsLogId())

        // 详尽的中文注释：如果在物理或逻辑层级发生了截断，则在保留前 500 字符的段尾部分拼接上英文省略号 "..."
        return if (finalIsTruncated) {
            "${baseDescription.trimEnd()}..."
        } else {
            baseDescription
        }
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

    private fun String.limitDescriptionChars(uri: String): String {
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
            val continuationCount = when (byte) {
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
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

    private fun FileRef.vfsDisplayId(): String =
        // 为每一次改动添加详尽的中文注释：用户可见/日志来源标识使用 rootId/sourcePath，避免继续输出 provider URI。
        "vfs://$rootId/$sourcePath"

    private fun VfsNode.vfsLogId(): String =
        // 为每一次改动添加详尽的中文注释：VFS 节点日志只暴露标准 rootId/path，不再把 provider 原生地址泄漏到业务层。
        "vfs://${root.id}/${path.value}"

    private fun BookFileEntity.vfsKey(): String =
        // 为每一次改动添加详尽的中文注释：入库后的章节映射也用 rootId/sourcePath 还原同一个 VFS 文件键。
        vfsFileKey(rootId, sourcePath)

    private fun String.escapeJson(): String =
        // 为每一次改动添加详尽的中文注释：生成的 VFS manifest JSON 对路径分隔符和引号做最小转义，避免 sourcePath 中特殊字符破坏 JSON。
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
        private const val MAX_DESCRIPTION_BYTES = 10000
        private const val MAX_DESCRIPTION_CHARS = 2000
        private const val MAX_UTF8_CODE_POINT_BYTES = 4
    }
}
