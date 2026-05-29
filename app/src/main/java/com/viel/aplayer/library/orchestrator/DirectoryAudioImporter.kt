package com.viel.aplayer.library.orchestrator

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.sortedByStableFileKey
import com.viel.aplayer.logger.ImportTimingLogger
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 目录音频批量与启发式导入工位（DirectoryAudioImporter）。
 * 
 * 本类是由原 RescanCoordinator 中拆分抽离出来的核心独立组件，专门处理 ImportScopeKind.DIRECTORY_AUDIO 类型的扫描导入。
 * 它实现了有声书导入中“目录音频的分流读取”、“有章节音频的批量提前导入”以及“无章节音频的目录级启发式聚合导入”职责。
 * 
 * 通过将这部分高度复杂的流式并发和分批处理逻辑解耦出来，使得原本的 RescanCoordinator 成功实现了物理瘦身，
 * 从架构上彻底划清了“扫描会话（Session）生命周期控制”与“单目录音频物理解析入库”的边界。
 */
@UnstableApi
internal class DirectoryAudioImporter(
    private val metadataResolver: MetadataResolver,
    private val pipeline: ImportPipeline,
    private val importer: BookImporter,
    private val triggerCoverRegeneration: (BookEntity) -> Unit
) {

    // 有章节音频按有界 I/O 并发规模成批入库，让封面解析和数据库写入都从“单文件循环”变成“小批量流水”，同时避免一次塞入过多大文件。
    private val CHAPTERED_AUDIO_IMPORT_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    // 目录音频元数据也按同样的小批次被消费，第一批 metadata 读完即可进入导入，而不是等完整目录全部读完。
    private val DIRECTORY_AUDIO_METADATA_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    /**
     * 执行 DIRECTORY_AUDIO 类型 scope 的目录导入逻辑。
     * 本方法先并发读取该目录全部未认领音频的元数据，然后按是否有章节进行分流：
     * 1. 确认有内嵌章节的音频小批次批量提前导入。
     * 2. 无章节音频在目录全部扫描完成后，作为启发式聚合有声书一次性导入。
     */
    suspend fun import(
        scope: ImportScope,
        existingClaimIndex: ExistingClaimIndex,
        claimLedger: RunClaimLedger,
        scanId: String
    ): List<SubBatchResult> {
        val scopeResults = mutableListOf<SubBatchResult>()
        val timingScopeId = scope.timingScopeId()
        val heuristicRefs = mutableListOf<AudioMetadataRef>()
        val candidateAudios = scope.inventory.audioFiles
            .filterNot { existingClaimIndex.has(it.identity) }
        val metadataStartedAt = ImportTimingLogger.mark()

        coroutineScope {
            val metadataSemaphore = Semaphore(DEFAULT_SCOPE_IO_CONCURRENCY)
            // 所有 metadata 任务一次性启动并用 semaphore 限并发，后续批次会在当前批次导入时继续读取，避免小批次流式入库拖慢总吞吐。
            val metadataJobs = candidateAudios.map { audio ->
                async {
                    metadataSemaphore.withPermit {
                        // 目录音频元数据读取走 VFS FileRef 入口，不再依赖扫描期 provider URI。
                        // 目录音频子批次也复用“元数据+封面”结果，避免后续刚入库又触发一次 MP4 covr Range 读取。
                        val extracted = metadataResolver.extractWithEmbeddedCover(audio)
                        AudioMetadataRef(audio, extracted.metadata, extracted.embeddedCover)
                    }
                }
            }
            // 单独的汇总协程记录“全目录 metadata 何时全部完成”，即使前台已经开始入库，也能保持日志口径只反映 metadata wall time。
            val metadataSummaryJob = async {
                val allRefs = metadataJobs.awaitAll()
                val chapteredCount = allRefs.count { it.metadata.chapters.isNotEmpty() }
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "directoryAudio.metadataResolve",
                    elapsedMs = ImportTimingLogger.elapsedMs(metadataStartedAt),
                    detail = "input=${scope.inventory.audioFiles.size} resolved=${allRefs.size} chaptered=$chapteredCount heuristic=${allRefs.size - chapteredCount} batches=${metadataJobs.chunked(DIRECTORY_AUDIO_METADATA_BATCH_SIZE).size}"
                )
            }

            val metadataBatches = metadataJobs.chunked(DIRECTORY_AUDIO_METADATA_BATCH_SIZE)
            metadataBatches.forEachIndexed { index, batchJobs ->
                val batchMetadataStartedAt = ImportTimingLogger.mark()
                val batchRefs = batchJobs.awaitAll()
                val chapteredRefs = mutableListOf<AudioMetadataRef>()
                batchRefs.forEach { audioRef ->
                    if (existingClaimIndex.has(audioRef.file.identity)) return@forEach
                    if (audioRef.metadata.chapters.isNotEmpty()) {
                        // 当前 metadata 小批次里确认有章节的音频马上进入导入队列，不再等待同目录剩余文件完成元数据读取。
                        chapteredRefs.add(audioRef)
                    } else {
                        // 无章节音频仍累计到目录级启发式窗口，保持多文件书聚合所需的完整同目录上下文。
                        heuristicRefs.add(audioRef)
                    }
                }
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "directoryAudio.metadataResolveBatch",
                    elapsedMs = ImportTimingLogger.elapsedMs(batchMetadataStartedAt),
                    detail = "batch=${index + 1}/${metadataBatches.size} input=${batchJobs.size} resolved=${batchRefs.size} chaptered=${chapteredRefs.size} heuristic=${batchRefs.size - chapteredRefs.size}"
                )

                // 每个 metadata 小批次最多产生一个章节音频导入批次，保持稳定顺序，并让书架尽快看到第一批有章节书。
                chapteredRefs.chunked(CHAPTERED_AUDIO_IMPORT_BATCH_SIZE).forEachIndexed { chunkIndex, batchRefs ->
                    val result = importSubBatch(
                        scope = scope,
                        refs = batchRefs,
                        claimLedger = claimLedger,
                        existingClaimIndex = existingClaimIndex,
                        scanId = scanId,
                        failureMessage = "有章节音频批量提前导入失败",
                        timingSuffix = "chaptered-batch:${index + 1}/${metadataBatches.size}.${chunkIndex + 1}:files=${batchRefs.size}"
                    )
                    scopeResults.add(result)
                }
            }
            metadataSummaryJob.await()
        }

        // 同目录所有无章节音频在完整扫描后统一交给启发式聚合，避免普通多文件书被文件级流式拆散。
        val heuristicResult = importSubBatch(
            scope = scope,
            refs = heuristicRefs,
            claimLedger = claimLedger,
            existingClaimIndex = existingClaimIndex,
            scanId = scanId,
            failureMessage = "无章节音频启发式导入失败",
            timingSuffix = "heuristic"
        )
        scopeResults.add(heuristicResult)

        return scopeResults
    }

    /**
     * 导入单个分流后的子批次。
     * 本方法会对该子批次 fork 出专属的认领账本，然后调用 pipeline 流水线执行编排并交由 importer 落库。
     * 如果落库成功，则将子批次的所有权认领并入主扫描账本（claimLedger），并触发封面重建回调。
     */
    private suspend fun importSubBatch(
        scope: ImportScope,
        refs: List<AudioMetadataRef>,
        claimLedger: RunClaimLedger,
        existingClaimIndex: ExistingClaimIndex,
        scanId: String,
        failureMessage: String,
        timingSuffix: String
    ): SubBatchResult {
        if (refs.isEmpty()) {
            return SubBatchResult(
                result = ImportRunResult(
                    scanId,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList()
                ),
                success = true
            )
        }
        val timingScopeId = "${scope.timingScopeId()}#$timingSuffix"
        val batchStartedAt = ImportTimingLogger.mark()
        val scopedInventory = scope.inventory.withAudioFiles(refs.map { it.file })
        val scopeLedger = claimLedger.fork()
        
        val scopeResult = runCatching {
            pipeline.runWithPreResolvedAudio(
                scanId = scanId,
                inventory = scopedInventory,
                existingClaimIndex = existingClaimIndex,
                runClaimLedger = scopeLedger,
                looseAudioMetadataRefs = refs,
                timingScopeId = timingScopeId
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // 目录内子批次失败只记录到最终汇总，不阻塞同目录内其他已识别音频继续导入。
            ImportTimingLogger.logDuration(
                scopeId = timingScopeId,
                stage = "scope.failed",
                elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
                detail = "phase=orchestrator files=${refs.size}"
            )
            val failResult = scope.toScopeFailure(scanId, failureMessage, throwable)
            return SubBatchResult(result = failResult, success = false)
        }

        val appliedResult = runCatching {
            ImportTimingLogger.measure(
                scopeId = timingScopeId,
                stage = "db.apply",
                detail = scopeResult.timingCommandDetail()
            ) {
                importer.applyImportRun(scopeResult)
            }
            scopeResult
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // 目录内子批次入库仍由 BookImporter 事务保护，失败时不会产生半截书籍数据。
            val dbFailResult = scope.toScopeFailure(
                scanId = scanId,
                message = "目录音频子批次入库失败",
                throwable = throwable,
                inheritedFailures = scopeResult.failures
            )
            return SubBatchResult(result = dbFailResult, success = false)
        }

        // 只有成功入库的子批次才提交 claim，防止失败音频把后续启发式批次错误占住。
        claimLedger.commitFrom(scopeLedger)
        // 子批次成功入库后立刻异步调度封面重建，让书架先显示新书。
        appliedResult.triggerCoverRegenerationForReadyBooks()

        ImportTimingLogger.logDuration(
            scopeId = timingScopeId,
            stage = "scope.total",
            elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
            detail = "files=${refs.size} ${appliedResult.timingCommandDetail()}"
        )

        return SubBatchResult(result = appliedResult, success = true)
    }

    /**
     * 目录音频子批次只携带参与本次裁决的音频，但保留对应父目录图片，保证封面 sidecar 仍可匹配。
     */
    private fun FileInventory.withAudioFiles(audioFiles: List<FileRef>): FileInventory {
        val parentKeys = audioFiles.map { it.parentSourceKey }.toSet()
        val rootIds = audioFiles.map { it.rootId }.toSet()
        return FileInventory(
            roots = roots.filter { it.id in rootIds }.ifEmpty { roots },
            cueFiles = emptyList(),
            m3u8Files = emptyList(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imageFilesByParent
                .filterKeys { it in parentKeys }
                .mapValues { (_, images) -> images.sortedByStableFileKey() },
            // 目录音频子批次在保留图片侧车的同时，也保留对应父目录的 txt 侧车，
            // 这样后续如果需要在子批次上做描述兜底，仍然拥有完整目录上下文。
            textFilesByParent = textFilesByParent
                .filterKeys { it in parentKeys }
                .mapValues { (_, texts) -> texts.sortedByStableFileKey() }
        )
    }

    private fun ImportScope.toScopeFailure(
        scanId: String,
        message: String,
        throwable: Throwable,
        inheritedFailures: List<ImportCommand.RecordFailure> = emptyList()
    ): ImportRunResult =
        ImportRunResult(
            scanId = scanId,
            readyImports = emptyList(),
            refreshedBooks = emptyList(),
            pendingActions = emptyList(),
            failures = inheritedFailures + ImportCommand.RecordFailure(
                ImportFailure(
                    sourceUri = displayUri(),
                    message = message,
                    throwableMessage = throwable.localizedMessage ?: throwable.message
                )
            )
        )

    private fun ImportScope.displayUri(): String =
        inventory.cueFiles.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.m3u8Files.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.audioFiles.firstOrNull()?.parentSourceKey
            ?: inventory.imageFilesByParent.keys.firstOrNull()
            ?: inventory.roots.firstOrNull()?.sourceUri
            ?: id

    private fun ImportScope.timingScopeId(): String = "${kind.name}:${displayUri()}"

    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} pending=${pendingActions.size} failures=${failures.size}"

    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }
}

/**
 * 用于描述目录音频导入过程中，子批次导入与事务入库执行结果的承载实体。
 */
internal data class SubBatchResult(
    val result: ImportRunResult,
    val success: Boolean  // 入库是否成功（决定主 claim ledger 是否 commit 对应的子批次占用）
)
