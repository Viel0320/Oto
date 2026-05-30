package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.PendingScanActionEntity
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.BookDraftFactory
// 详尽的中文注释：导入包级可见的 String.escapeJson 扩展函数以实现 JSON 敏感字符的统一安全转义
import com.viel.aplayer.library.orchestrator.escapeJson
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportSourceRef
import com.viel.aplayer.library.orchestrator.ReservationResult
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import java.util.UUID

// 修复 ChapterCandidate 与 MetadataSuggestion 的包名导入错误，以正确找到在 com.viel.aplayer.library 下定义的类型

/**
 * 冲突所有权认领决策工位
 * 
 * 本工位不修改任何真实的数据库数据。它只负责判定内存认领账本（ClaimLedger）。
 * 决定当前导入的有声书是能够一路绿灯通过认领直接落库，还是默默刷新所有权，
 * 抑或是进入 PendingAction 冲突挂起队列由用户后续决定。
 * 此外，本步骤也完成了 BookDraft/BookEntity/BookFileEntity/ChapterEntity 的全部映射构建，
 * 并打包返回底盘所需的 ImportRunResult 实例，无缝适配 BookImporter 和 ScanSessionRunner。
 */
// 将类可见性声明为 internal，收紧其在本模块内的使用范围，防止由于引用其他 internal 类型而报 public 泄漏错误
@OptIn(UnstableApi::class)
/**
 * 冲突所有权认领决策工位。
 * 本类已被重构，去除了原有的泛型接口 ImportStep<I, O> 和 StepResult 密封类包装。
 * 现在 execute 方法直接返回具体的 ImportRunResult 结果，当遇到异常时将自然向上抛出。
 */
internal class OwnershipClaimStep(
    private val context: Context,
    // 注入 BookDraftFactory 工厂以委托实体映射与 Draft 构建逻辑
    private val draftFactory: BookDraftFactory
) {

    /**
     * 执行所有权认领决策。直接接收 CoverExtractedResult 并返回 ImportRunResult，不再包装在 StepResult 中。
     */
    suspend fun execute(
        input: CoverExtractedResult,
        context: ImportContext
    ): ImportRunResult {
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
            val firstAudioMeta = draftFactory.firstManifestAudioMetadata(cueBook.audioRefs)
            val mergedMeta = draftFactory.resolveManifestBookMetadata(
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

            val draft = draftFactory.buildManifestDraft(
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

            val firstAudioMeta = draftFactory.firstManifestAudioMetadata(m3u8Book.audioRefs)
            val mergedMeta = draftFactory.resolveManifestBookMetadata(
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

            val draft = draftFactory.buildManifestDraft(
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

                val draft = draftFactory.buildGeneratedDraft(aggBook.bookId, source, aggBook.plan, description, aggBook.coverResult)
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

            val draft = draftFactory.buildSingleAudioDraft(singleBook.bookId, audio, description, singleBook.coverResult)
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        val runResult = ImportRunResult(
            scanId = context.scanId,
            readyImports = readyImports,
            refreshedBooks = refreshedBooks,
            pendingActions = pendingActions,
            failures = failures
        )
        return runResult
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
            // 详尽的中文注释：对拼接 JSON 字符串的 source.sourceUri 进行基础安全转义（通过 escapeJson 扩展函数），
            // 确保即使 URI 中带有反斜杠或双引号等敏感字符，也不会导致 JSON 解析撕裂以及运行期异常崩溃
            payloadJson = "{\"sourceUri\":\"${source.sourceUri.escapeJson()}\"}",
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
            // 详尽的中文注释：对拼接 JSON 字符串的 source.sourceUri 进行基础安全转义（通过 escapeJson 扩展函数），
            // 确保即使 URI 中带有反斜杠或双引号等敏感字符，也不会导致 JSON 解析撕裂以及运行期异常崩溃
            payloadJson = "{\"sourceUri\":\"${source.sourceUri.escapeJson()}\",\"missingCount\":$missingCount}",
            message = "Source \"${source.displayName}\" is missing $missingCount referenced file(s).",
            lastSeenScanId = scanId
        ))

    private fun FileRef.vfsDisplayId(): String =
        // 用户可见/日志来源标识使用 rootId/sourcePath，避免继续输出 provider URI。
        "vfs://$rootId/$sourcePath"

    companion object {
        private const val TAG = "OwnershipClaimStep"
    }
}
