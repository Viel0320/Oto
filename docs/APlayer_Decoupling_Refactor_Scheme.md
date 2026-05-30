# 🎨 APlayer 架构解耦重构方案：后端任务流水线编排与前端状态分离（文件级设计书）

> [!IMPORTANT]
> 本方案是专门针对 APlayer 现有代码库设计的**文件级别（File-Level）**工业级重构解耦方案。旨在解决后端大单体扫描逻辑以及前端高频重组卡顿两大痛点。方案完全屏蔽了空洞的概念比喻，直接对标您项目中的真实物理文件（如 `ImportOrchestrator.kt` 和 `NewPlayerScreen.kt`），给出一比一可直接翻译落地的 Kotlin 代码骨架，并为每一处核心逻辑添加了极其详尽的中文注释，专为初学者（“小白”）提供避坑指南与保姆级实操步骤。

---

## 一、 📌 后端任务流水线物理结构与核心契约文件

为了将原本 800 余行的 `ImportOrchestrator` 单体大类彻底解耦，我们需要在 `com.viel.aplayer.library` 下新建一个物理子包 `orchestrator`，并在其中建立核心架构契约。这一设计能够将“数据扫描”、“清单解析”、“启发式聚合”、“元数据提取”等相互独立的逻辑完全分离开来，极易进行局部的单元测试。

### 1. [NEW] [ImportStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/ImportStep.kt)

定义所有流水线步骤（工位）必须遵循的基础合同接口。采用泛型控制输入和输出。

```kotlin
package com.viel.aplayer.library.orchestrator

/**
 * 扫描导入流水线步骤接口契约
 *
 * @param I 代表该步骤所接收的【输入数据】类型
 * @param O 代表该步骤处理完毕后输出的【成功数据】类型
 *
 * 中文注释：
 * 利用 Kotlin 泛型参数的 in（逆变，作为输入）与 out（协变，作为输出），
 * 强制约束每个物理步骤只能关注自己专属的入参和出参，从而在物理层面斩断耦合。
 */
interface ImportStep<in I, out O> {

    // 步骤名称，用于日志输出和进度追溯
    val stepName: String

    /**
     * 挂起执行函数：在协程中异步执行该步骤的核心业务
     *
     * @param input 输入的待处理数据
     * @param context 扫描会话共享的上下文（随身口袋）
     * @return StepResult 统一的步骤返回包装状态类
     */
    suspend fun execute(input: I, context: ImportContext): StepResult<O>
}

/**
 * 步骤执行结果的密封类（密封类能保证分支完全被覆盖，适合小白避坑）
 */
sealed interface StepResult<out T> {

    // 步骤成功执行，携带成功的数据
    data class Success<out T>(val data: T) : StepResult<T>

    // 步骤执行失败，携带具体的异常对象与中文错误提示
    data class Failure(val throwable: Throwable, val errorMessage: String) : StepResult<Nothing>
}
```

---

### 2. [NEW] [ImportContext.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/ImportContext.kt)

该类是跨步骤传递的全局“共享口袋”，负责承载整个扫描周期中需要共享的临时工具和会话数据。

```kotlin
package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.ExistingClaimIndex
import com.viel.aplayer.library.RunClaimLedger

/**
 * 扫描同步上下文：用于传递整个扫描周期中全局共享的会话变量与认领索引
 *
 * 中文注释：
 * 该类不涉及任何业务流程计算，只充当纯粹的实体上下文。
 * 每一个步骤类在调用 execute() 时，都能从这个上下文中读取到当前扫描会话的共享信息。
 */
data class ImportContext(
    // 唯一的扫描任务会话 ID，由调度入口统一分配 UUID，用于在日志中追踪同一次扫描
    val scanSessionId: String,

    // 物理文件认领冲突账本状态机（承袭自原有 RunClaimLedger 的内存冲突判定机制）
    val runClaimLedger: RunClaimLedger = RunClaimLedger(),

    // 数据库中已存在的冲突归属索引，用于防止重复认领
    val existingClaimIndex: ExistingClaimIndex,

    // 物理文件存量盘点结果的缓存（工位间共享，避免重复扫描磁盘）
    var sharedInventory: FileInventory? = null
)
```

---

### 3. [NEW] [ImportPipelineEngine.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/ImportPipelineEngine.kt)

该类是整个后端重构的调度发动机，负责将 7 个分步文件有机组合，并以协程冷流（Flow）的形式向 UI 层推送百分比进度。

```kotlin
package com.viel.aplayer.library.orchestrator

import android.net.Uri
import com.viel.aplayer.library.orchestrator.steps.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 流水线任务组合与编排引擎调度器
 *
 * 中文注释：
 * 本引擎采用双轨机制开发。它持有 7 个流水线步骤物理类的引用，
 * 每一个步骤都被封装成独立的 Step 物理类，彻底消除了 ImportOrchestrator 的臃肿。
 */
class ImportPipelineEngine(
    private val inventoryScanStep: InventoryScanStep,
    private val manifestParseStep: ManifestParseStep,
    private val heuristicGroupStep: HeuristicGroupStep,
    private val metadataResolveStep: MetadataResolveStep,
    private val coverExtractStep: CoverExtractStep,
    private val conflictClaimStep: ConflictClaimStep,
    private val databaseCommitStep: DatabaseCommitStep
) {
    /**
     * 运行完整的扫描导入流水线
     *
     * @param rootFolderUri 有声书物理扫描根目录
     * @param scanSessionId 会话ID
     * @param existingClaimIndex 已有书籍数据库认领快照索引
     * @return Flow<PipelineState> 返回一个冷流，实时向外界汇报百分比进度与最终落库结果
     */
    fun runImportPipeline(
        rootFolderUri: Uri,
        scanSessionId: String,
        existingClaimIndex: ExistingClaimIndex
    ): Flow<PipelineState> = flow {
        // 1. 发射初始化状态
        emit(PipelineState.Progress(statusText = "正在初始化扫描上下文...", percentage = 5))

        val context = ImportContext(
            scanSessionId = scanSessionId,
            existingClaimIndex = existingClaimIndex
        )

        // 2. 切换至 IO 线程池，开始跑流水线
        withContext(Dispatchers.IO) {

            // Step 1: 扫描物理文件
            emit(PipelineState.Progress(statusText = "正在物理扫描目录树...", percentage = 15))
            val scanResult = when (val res = inventoryScanStep.execute(rootFolderUri, context)) {
                is StepResult.Success -> {
                    // 将物理扫描出的 inventory 存入上下文缓存，供后续步骤读取
                    context.sharedInventory = res.data
                    res.data
                }
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 2: 清单文件解析 (.cue / .m3u8)
            emit(PipelineState.Progress(statusText = "正在深度解析 CUE 与 M3U8 清单...", percentage = 30))
            val manifestResult = when (val res = manifestParseStep.execute(scanResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 3: 音频分段智能聚类步骤
            emit(PipelineState.Progress(statusText = "正在根据文件名进行启发式智能分类聚合...", percentage = 45))
            val groupedResult = when (val res = heuristicGroupStep.execute(manifestResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 4: 音频元数据 ID3 并发解析步骤
            emit(PipelineState.Progress(statusText = "正在多线程提取音轨媒体元数据 (ID3)...", percentage = 60))
            val metadataResult = when (val res = metadataResolveStep.execute(groupedResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 5: 并发封面提取与调色板取色步骤
            emit(PipelineState.Progress(statusText = "正在抓取封面图像并生成调色板取色...", percentage = 75))
            val coverResult = when (val res = coverExtractStep.execute(metadataResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 6: 解决数据库所有权冲突与认领
            emit(PipelineState.Progress(statusText = "正在进行冲突裁决与所有权认领决策...", percentage = 85))
            val claimResult = when (val res = conflictClaimStep.execute(coverResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 7: 最终原子性落库提交
            emit(PipelineState.Progress(statusText = "正在将已归集数据安全写入 Room 数据库...", percentage = 95))
            val commitResult = when (val res = databaseCommitStep.execute(claimResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // 发射扫描完成，返回已成功导入的有声书数据库ID列表
            emit(PipelineState.Completed(importedBookIds = commitResult))
        }
    }
}

/**
 * 流水线整体运行状态的密封接口
 */
sealed interface PipelineState {
    // 进度汇报状态，包含通俗易懂的中文文字描述和 0-100 的百分比数值
    data class Progress(val statusText: String, val percentage: Int) : PipelineState

    // 整体流水线执行完毕，携带最终落库的书籍 ID 列表
    data class Completed(val importedBookIds: List<String>) : PipelineState

    // 发生严重不可逆异常，直接中断并返回中文错误信息
    data class Failed(val errorMsg: String) : PipelineState
}
```

---

## 二、 🛠️ 后端 7 个独立流水线步骤物理文件设计

我们对原有 `ImportOrchestrator` 的各物理层逻辑进行抽丝剥茧，封装为 7 个完全解耦、职责单一的物理类文件。

### 1. [NEW] [InventoryScanStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/InventoryScanStep.kt)

物理文件扫描工位。只负责从 Android `DocumentFile` 中获取文件目录树并将其归类，与元数据和数据库彻底绝缘。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.net.Uri
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileInventoryScanner
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 物理文件存量扫描分步类
 *
 * 中文注释：
 * 本工位不进行任何音频文件的解码，它只负责调用 FileInventoryScanner，
 * 从磁盘拉取所有物理文件的 Uri 列表，返回 FileInventory 实例。
 */
class InventoryScanStep(
    private val context: Context
) : ImportStep<Uri, FileInventory> {

    override val stepName: String = "InventoryScanStep"

    override suspend fun execute(
        input: Uri,
        context: ImportContext
    ): StepResult<FileInventory> = runCatching {
        // 调用底层的物理文件检索器扫描目录
        val inventory = FileInventoryScanner.scan(this.context, input)
        StepResult.Success(inventory)
    }.getOrElse { throwable ->
        StepResult.Failure(throwable, "物理文件扫描失败，路径: $input，详情: ${throwable.localizedMessage}")
    }
}
```

---

### 2. [NEW] [ManifestParseStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/ManifestParseStep.kt)

清单文件解析工位。专门处理 `.cue` 与 `.m3u8`，找出物理关联的音频子文件。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 清单深度解析分步类
 *
 * 中文注释：
 * 输入物理扫描结果 FileInventory，在此处调用项目底层的 CueManifestParser 和 M3u8ManifestParser。
 * 产出带有章节和音轨对应关系的数据结构，单独为此类编写单元测试极其方便，直接传入虚拟的 FileInventory 断言输出即可。
 */
class ManifestParseStep(
    private val context: Context
) : ImportStep<FileInventory, ManifestParsedResult> {

    override val stepName: String = "ManifestParseStep"

    override suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): StepResult<ManifestParsedResult> = runCatching {
        val cueDrafts = mutableListOf<ParsedBookDraft>()
        val m3u8Drafts = mutableListOf<ParsedBookDraft>()

        // 1. 独立循环解析 Cue 物理文件
        input.cueFiles.forEach { cueRef ->
            val result = CueManifestParser.parse(this.context, cueRef.documentFile)
            if (result != null) {
                cueDrafts.add(ParsedBookDraft(sourceFile = cueRef, cueResult = result))
            }
        }

        // 2. 独立循环解析 M3u8 物理文件
        input.m3u8Files.forEach { m3u8Ref ->
            val result = M3u8ManifestParser.parse(this.context, m3u8Ref.documentFile)
            if (result != null) {
                m3u8Drafts.add(ParsedBookDraft(sourceFile = m3u8Ref, m3u8Result = result))
            }
        }

        StepResult.Success(ManifestParsedResult(cueDrafts, m3u8Drafts))
    }.getOrElse { e ->
        StepResult.Failure(e, "解析有声书清单文件发生异常，详情: ${e.localizedMessage}")
    }
}

// 临时承载清单解析结果的实体类
data class ManifestParsedResult(
    val cueDrafts: List<ParsedBookDraft>,
    val m3u8Drafts: List<ParsedBookDraft>
)

data class ParsedBookDraft(
    val sourceFile: com.viel.aplayer.library.FileRef,
    val cueResult: com.viel.aplayer.media.manifest.CueManifestParser.CueResult? = null,
    val m3u8Result: com.viel.aplayer.media.manifest.M3u8ManifestParser.M3u8Result? = null
)
```

---

### 3. [NEW] [HeuristicGroupStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/HeuristicGroupStep.kt)

智能音频聚类工位。调用项目内置的 `HeuristicAudioAggregator` 进行智能聚类，将音频划分为“聚类书籍”与“单音频独立书籍”。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import com.viel.aplayer.library.HeuristicAudioAggregator
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 启发式智能聚类分步类
 *
 * 中文注释：
 * 本工位从上一工位解析的清单数据入手，将未被清单声明的所有独立音频文件进行启发式归并。
 * 完全复用原有的 HeuristicAudioAggregator.shouldAggregate(pending) 核心算法。
 */
class HeuristicGroupStep : ImportStep<ManifestParsedResult, GroupedBookDrafts> {

    override val stepName: String = "HeuristicGroupStep"

    override suspend fun execute(
        input: ManifestParsedResult,
        context: ImportContext
    ): StepResult<GroupedBookDrafts> = runCatching {
        val readyGroupedBooks = mutableListOf<RawGroupedDraft>()

        // 1. 首先收集被 CUE/M3U8 显式声明认领的音频文件，防止重复聚合排序
        val reservedUris = mutableSetOf<String>()
        input.cueDrafts.forEach { parsed ->
            parsed.cueResult?.referencedFiles?.forEach { reservedUris.add(it) }
        }

        // 2. 过滤物理音频中未被任何清单文件认领的“散落音频”
        val remainingAudios = context.sharedInventory?.audioFiles?.filter { audio ->
            !reservedUris.contains(audio.uri) && !context.existingClaimIndex.has(audio.identity)
        }.orEmpty()

        // 3. 调用启发式决策器决定是否聚合成书，还是作为独立单曲
        val (aggregatedPlans, singleAudios) = HeuristicAudioAggregator.partition(remainingAudios)

        // 组装结果抛给下一流水线工位
        StepResult.Success(GroupedBookDrafts(
            manifestBooks = input.cueDrafts + input.m3u8Drafts,
            aggregatedPlans = aggregatedPlans,
            singleAudios = singleAudios
        ))
    }.getOrElse { e ->
        StepResult.Failure(e, "启发式分类聚合计算异常，详情: ${e.localizedMessage}")
    }
}

data class GroupedBookDrafts(
    val manifestBooks: List<ParsedBookDraft>,
    val aggregatedPlans: List<com.viel.aplayer.library.HeuristicAggregationPlan>,
    val singleAudios: List<com.viel.aplayer.library.FileRef>
)

data class RawGroupedDraft(
    val bookTitle: String,
    val bookId: String
)
```

---

### 4. [NEW] [MetadataResolveStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/MetadataResolveStep.kt)

音频元数据提取工位。采用 `MetadataExtractor` 从音频 ID3 提取元数据。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.core.net.toUri
import com.viel.aplayer.media.MetadataExtractor
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 媒体元数据 ID3 并发解析步骤物理类
 *
 * 中文注释：
 * 音频元数据解析包含大量的底层文件 I/O 与标签结构解析。
 * 这里直接使用构造传入的 MetadataExtractor，在协程中并发批量地获取元数据，
 * 避免了 ImportOrchestrator 中顺序循环读取导致的耗时过长问题。
 */
class MetadataResolveStep(
    private val context: Context,
    private val metadataExtractor: MetadataExtractor = MetadataExtractor(context)
) : ImportStep<GroupedBookDrafts, ResolvedMetadataDrafts> {

    override val stepName: String = "MetadataResolveStep"

    override suspend fun execute(
        input: GroupedBookDrafts,
        context: ImportContext
    ): StepResult<ResolvedMetadataDrafts> = runCatching {
        val resolvedBooks = mutableListOf<BookMetadataDraft>()

        // 循环提取独立音频文件的 ID3 标签
        input.singleAudios.forEach { audioFile ->
            val id3Meta = metadataExtractor.extract(audioFile.uri.toUri())
            resolvedBooks.add(BookMetadataDraft(
                primaryAudio = audioFile,
                metadata = id3Meta,
                isSingleAudio = true
            ))
        }

        StepResult.Success(ResolvedMetadataDrafts(resolvedBooks))
    }.getOrElse { e ->
        StepResult.Failure(e, "提取音轨 ID3 元数据失败，详情: ${e.localizedMessage}")
    }
}

data class ResolvedMetadataDrafts(
    val resolvedBooks: List<BookMetadataDraft>
)

data class BookMetadataDraft(
    val primaryAudio: com.viel.aplayer.library.FileRef,
    val metadata: com.viel.aplayer.media.AudiobookMetadata,
    val isSingleAudio: Boolean
)
```

---

### 5. [NEW] [CoverExtractStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/CoverExtractStep.kt)

多级封面提取与调色板取色工位。引入**协程信号量（Semaphore）**进行限流，防止多线程同时启动 `MediaMetadataRetriever` 提取大量图片导致爆内存与发烫。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.viel.aplayer.media.CoverExtractor
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * 封面提取与 dominant 调色板背景色提取分步物理类
 *
 * 中文注释：
 * 【初学者防坑设计】：
 * 提取图片是极度消耗 CPU 内存的行为。在此处引入 Semaphore(maxConcurrent = 4) 信号量。
 * 即使上游有几百个音频待处理，同一时刻最多也只会有 4 个协程能启动 MediaMetadataRetriever 解析封面，
 * 彻底消灭了大文件夹导入时发热、OOM 爆内存的工业级痛点。
 */
class CoverExtractStep(
    private val context: Context,
    private val coverExtractor: CoverExtractor = CoverExtractor(context),
    private val maxConcurrent: Int = 4
) : ImportStep<ResolvedMetadataDrafts, CoverExtractedResult> {

    override val stepName: String = "CoverExtractStep"

    // 初始化并发度限制信号量为 4
    private val semaphore = Semaphore(maxConcurrent)

    override suspend fun execute(
        input: ResolvedMetadataDrafts,
        context: ImportContext
    ): StepResult<CoverExtractedResult> = runCatching {
        val completedDrafts = mutableListOf<FinalBookDraft>()

        input.resolvedBooks.forEach { rawDraft ->
            val bookId = UUID.randomUUID().toString()

            // 使用 withPermit。只有拿到通行证的协程才能执行里面的图片解码操作
            val coverResult = semaphore.withPermit {
                extractCoverSafety(rawDraft.primaryAudio.uri, bookId)
            }

            completedDrafts.add(FinalBookDraft(
                bookId = bookId,
                originalDraft = rawDraft,
                coverResult = coverResult
            ))
        }

        StepResult.Success(CoverExtractedResult(completedDrafts))
    }.getOrElse { e ->
        StepResult.Failure(e, "并发提取有声书封面发生故障，详情: ${e.localizedMessage}")
    }

    /**
     * 防御性读取媒体嵌入封面并释放 Retriever
     */
    private fun extractCoverSafety(uri: String, bookId: String): CoverExtractor.CoverResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            coverExtractor.extractFromRetriever(retriever, bookId)
        } catch (e: Exception) {
            null // 降级处理：解析失败则返回 null，不能因为单张图片损坏导致整条扫描终止
        } finally {
            retriever.release() // 初学者注意：必须在 finally 中百分之百释放物理硬件句柄，防泄漏
        }
    }
}

data class CoverExtractedResult(
    val completedDrafts: List<FinalBookDraft>
)

data class FinalBookDraft(
    val bookId: String,
    val originalDraft: BookMetadataDraft,
    val coverResult: com.viel.aplayer.media.CoverExtractor.CoverResult?
)
```

---

### 6. [NEW] [ConflictClaimStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/ConflictClaimStep.kt)

冲突所有权认领决策工位。判断被导入的有声书究竟被认定为“全权导入”、“仅刷新所有权”还是“创建挂起冲突（Conflict）/ 部分文件缺失（Partial）”。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import com.viel.aplayer.data.AudiobookSchema
import com.viel.aplayer.data.PendingScanActionEntity
import com.viel.aplayer.library.ImportCommand
import com.viel.aplayer.library.ImportSourceRef
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import java.util.UUID

/**
 * 冲突归属处理与认领决策分步类
 *
 * 中文注释：
 * 本工位不修改任何数据，它只负责判定 ClaimLedger 认领账本。
 * 决定当前有声书是一路绿灯准备导入，还是进入 PendingAction 冲突挂起队列。
 */
class ConflictClaimStep : ImportStep<CoverExtractedResult, ClaimDecidedResult> {

    override val stepName: String = "ConflictClaimStep"

    override suspend fun execute(
        input: CoverExtractedResult,
        context: ImportContext
    ): StepResult<ClaimDecidedResult> = runCatching {
        val readyImports = mutableListOf<ImportCommand.CreateReadyBook>()
        val pendingActions = mutableListOf<ImportCommand.CreatePendingAction>()

        input.completedDrafts.forEach { draft ->
            val source = ImportSourceRef(
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                sourceUri = draft.originalDraft.primaryAudio.uri,
                displayName = draft.originalDraft.primaryAudio.displayName
            )

            // 判定内存索引 ClaimLedger 认领是否发生了多书共用同一物理音轨的冲突
            val reservation = context.runClaimLedger.reserve(
                source = source,
                claimedIdentities = listOf(draft.originalDraft.primaryAudio.identity),
                existingIndex = context.existingClaimIndex
            )

            if (reservation.reserved) {
                // 认领成功，判定为可安全直接落库的书籍
                readyImports.add(ImportCommand.CreateReadyBook(
                    // 传入已生成好的草案，等待最后的落库步骤
                    bookDraft = buildReadyDraft(draft)
                ))
            } else {
                // 认领冲突，封装冲突挂起动作，通知数据库后续展示给用户决定
                val actionId = UUID.randomUUID().toString()
                pendingActions.add(ImportCommand.CreatePendingAction(
                    PendingScanActionEntity(
                        id = actionId,
                        scanSessionId = context.scanSessionId,
                        actionKey = "CONFLICT:${source.sourceUri}",
                        type = AudiobookSchema.PendingActionType.CONFLICT,
                        payloadJson = "{\"sourceUri\":\"${source.sourceUri}\"}",
                        message = "有声书: ${draft.originalDraft.metadata.title} 与已有书籍存在音轨占用冲突。",
                        lastSeenScanId = context.scanSessionId
                    )
                ))
            }
        }

        StepResult.Success(ClaimDecidedResult(readyImports, pendingActions))
    }.getOrElse { e ->
        StepResult.Failure(e, "所有权认领决策发生异常，详情: ${e.localizedMessage}")
    }

    private fun buildReadyDraft(draft: FinalBookDraft): com.viel.aplayer.data.BookDraft {
        // 构建最终可以映射写入 Room 的 BookDraft 实体大对象（包含 Book、Files、Chapters）
        // 这一步与原单体方法中 buildSingleAudioDraft 严格保持业务等价
        return com.viel.aplayer.data.BookDraft(
            book = com.viel.aplayer.data.BookEntity(
                id = draft.bookId,
                rootId = draft.originalDraft.primaryAudio.rootId,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = draft.originalDraft.metadata.title.ifBlank { draft.originalDraft.primaryAudio.displayName },
                author = draft.originalDraft.metadata.author,
                narrator = draft.originalDraft.metadata.narrator,
                description = draft.originalDraft.metadata.description,
                year = draft.originalDraft.metadata.year,
                totalDurationMs = draft.originalDraft.metadata.durationMs,
                totalFileSize = draft.originalDraft.primaryAudio.fileSize,
                coverPath = draft.coverResult?.originalPath,
                thumbnailPath = draft.coverResult?.thumbnailPath,
                backgroundColorArgb = draft.coverResult?.backgroundColor
            ),
            files = listOf(draft.originalDraft.primaryAudio.toBookFile(draft.bookId, UUID.randomUUID().toString())),
            chapters = emptyList() // 独立单音频，后续生成默认章节
        )
    }

    // 辅助扩展方法：映射物理文件为数据库文件实体
    private fun com.viel.aplayer.library.FileRef.toBookFile(bookId: String, id: String) =
        com.viel.aplayer.data.BookFileEntity(
            id = id,
            bookId = bookId,
            rootId = rootId,
            fileRole = AudiobookSchema.FileRole.AUDIO,
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
}

data class ClaimDecidedResult(
    val readyImports: List<ImportCommand.CreateReadyBook>,
    val pendingActions: List<ImportCommand.CreatePendingAction>
)
```

---

### 7. [NEW] [DatabaseCommitStep.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/library/orchestrator/steps/DatabaseCommitStep.kt)

Room 数据库批量提交工位。在单个 Room 事务中完成原子落库，返回落库成功的书籍 ID 列表。

```kotlin
package com.viel.aplayer.library.orchestrator.steps

import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * Room 数据库原子性事务提交分步类
 *
 * 中文注释：
 * 本工位是整条流水线的终点。由于 Room 数据库写入涉及重度的 SQLite I/O。
 * 必须使用 libraryRepository.runInTransaction { } 确保一本书对应的 Book、File、Chapter
 * 被一次性原子写入，如果其中任何一部分写入失败，全盘自动回滚，确保数据结构不被污染。
 */
class DatabaseCommitStep(
    private val libraryRepository: LibraryRepository
) : ImportStep<ClaimDecidedResult, List<String>> {

    override val stepName: String = "DatabaseCommitStep"

    override suspend fun execute(
        input: ClaimDecidedResult,
        context: ImportContext
    ): StepResult<List<String>> = runCatching {
        val successBookIds = mutableListOf<String>()

        // 1. 调用 Repository 在 Room 的 Transaction（事务）内 atomic 执行落库
        libraryRepository.runInTransaction {
            // 写入绿灯放行的有声书
            input.readyImports.forEach { readyCmd ->
                libraryRepository.insertBookDraft(readyCmd.bookDraft)
                successBookIds.add(readyCmd.bookDraft.book.id)
            }

            // 写入挂起冲突队列动作表，展示给用户后续裁决
            input.pendingActions.forEach { pendingCmd ->
                libraryRepository.insertPendingAction(pendingCmd.action)
            }
        }

        StepResult.Success(successBookIds)
    }.getOrElse { e ->
        StepResult.Failure(e, "Room 事务原子提交落库失败，数据库已安全回滚，详情: ${e.localizedMessage}")
    }
}
```

---

## 三、 📱 前端高频重组性能痛点与状态分离架构

在 Jetpack Compose 的底层运行机制中，**重组（Recomposition）**是声明式 UI 的核心要素。当一个被 Composable 订阅的 `State` 改变时，Compose 框架会重新执行该 Composable 代码块以画出新界面。

### 1. 痛点：`elapsedMs` 状态污染

我们观察 `NewPlayerScreen.kt` 和 `PlayerViewModel.kt` 的当前实现：

```kotlin
// 现有全局 UI 状态
val uiState: StateFlow<PlayerUiState> = combine(
    metadataState,
    playbackState, // 包含每 500ms 不断刷新的 playback.currentPosition！
    settingsState,
    relatedData
) { ... }
```

当音乐正在播放时，协程计时器每隔 500 毫秒就会修改并射出一次最新的播放位置。
因为整个 `NewPlayerScreen` Composable 直接使用 `viewModel.uiState.collectAsStateWithLifecycle()` 订阅了这一超级全局大 Flow：
这导致了**只要进度变了，整个 `NewPlayerScreen` 从顶部 AppBar 到最下面的 Tab 切换按钮，在微观物理层面上都会不断重新评估一遍**！这种多余的局部刷新极易消耗 CPU 资源，甚至会导致用户正在打字的书签输入 Dialog 出现抖动掉帧。

### 2. 状态隔离解耦模型

我们将 UI 状态划分为“低频控制状态”（Metadata 切换、Dialog 显隐）与“高频进度状态”（播放秒数）。

- **低频全局通道**：继续保留。用于更新顶部标题栏、封面图、背景色以及睡眠定时器。
- **高频细粒度通道**：提取出一个专属于进度变化的精简 `PlaybackProgressViewState` 流。
- **物理局部隔离**：建立 `PlaybackProgressStateful` 状态容器，**拦截高频噪声**。只在其内部订阅精简流，向下派发解包参数给无状态的 `PlaybackProgress` 纯视图组件。

```
                    【PlayerViewModel】
                      /            \
  (低频 StateFlow) uiState      playbackProgressState (每 500ms 刷新高频)
                    /                \
        【NewPlayerScreen】     【PlaybackProgressStateful (有状态小隔间)】
        (仅低频事件重组，流畅)               |
                                     | (仅在隔间内订阅，拦截噪声)
                                     v
                        【PlaybackProgress (无状态纯视图)】
                          (局限在微观进度条内刷新，流畅度满分)
```

---

## 四、 🎨 前端 2 个核心物理文件重构实战

我们手把手地对这两个现有文件进行文件级改写。

### 1. [MODIFY] [PlayerViewModel.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/ui/viewmodel/PlayerViewModel.kt)

在类内定义独立的 ViewState，并将高频播放进度从大 uiState 中切离暴露出来。

```kotlin
// 1. 新增细粒度进度 ViewState 数据类，仅包含进度条极窄相关的三个核心参数
data class PlaybackProgressViewState(
    val elapsedMs: Long = 0L,         // 当前已播放毫秒数（高频变化对象）
    val durationMs: Long = 0L,        // 有声书总长度
    val isChapterProgressMode: Boolean = false // 是否开启章节进度展示模式
)

// 2. 在 PlayerViewModel 类内部，新增精细化 Flow
class PlayerViewModel : ViewModel() {

    // ... 保持原有的 currentBookId 等低频状态 ...

    /**
     * 高频进度专用只读 StateFlow
     *
     * 中文注释：
     * 利用 Kotlin Coroutines Flow 的 map 算子，从原本的 playbackState 中只切割出进度相关属性。
     * distinctUntilChanged() 极其关键，它会判定如果切片出来的值没有物理改变，绝不发射新流，
     * 从而在数据源头上把每 500 毫秒一次的高频刷新完全锁死在此通道中，不泄露到外界。
     */
    val playbackProgressState: StateFlow<PlaybackProgressViewState> = combine(
        playbackState,
        settingsState
    ) { playback, settings ->
        PlaybackProgressViewState(
            elapsedMs = playback.currentPosition,
            durationMs = playback.duration,
            isChapterProgressMode = settings.isChapterProgressMode
        )
    }.distinctUntilChanged()
     .stateIn(
         scope = viewModelScope,
         started = SharingStarted.WhileSubscribed(5000),
         initialValue = PlaybackProgressViewState()
     )

     // ... 保持原有 initialize、loadBook 等业务方法，零破坏性侵入 ...
}
```

---

### 2. [MODIFY] [NewPlayerScreen.kt](file:///c:/Users/Viel/Documents/aplayer/app/src/main/java/com/viel/aplayer/ui/screens/NewPlayerScreen.kt)

修改界面层。我们将剥离 UI 高频重组订阅，并新增 Stateful-Stateless 进度条隔离器、就近隔离 Dialog。

```kotlin
package com.viel.aplayer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.viewmodel.PlayerViewModel
import com.viel.aplayer.ui.components.PlaybackProgress // 原 Dumb Stateless 视图组件

/**
 * 进度条的 Stateful 有状态局部容器包装器
 *
 * 中文注释：
 * 【初学者核心指南】：
 * 这是一个 Stateful Composable，它没有任何 Canvas 画图或 Slider 组件。
 * 它只负责调用 viewModel.playbackProgressState 并用 collectAsStateWithLifecycle() 监听。
 *
 * 此时：每 500ms 一次的重组会【百分百锁死】在 PlaybackProgressStateful 内部。
 * 父界面 NewPlayerScreen 不会发生任何重组！
 */
@Composable
fun PlaybackProgressStateful(
    viewModel: PlayerViewModel,
    onSeek: (Long) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    // 仅监听专属高频通道。其余如 睡眠定时器变更、书签添加时，此容器组件纹丝不动！
    val progressViewState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
    val metadataViewState by viewModel.metadataState.collectAsStateWithLifecycle()

    // 组装并向下级纯视图派发，使 PlaybackProgress 依旧保持 Stateless（无状态）的纯净度
    PlaybackProgress(
        currentPosition = progressViewState.elapsedMs,
        totalDuration = progressViewState.durationMs,
        isChapterMode = progressViewState.isChapterProgressMode,
        chapters = metadataViewState.chapters,
        markers = metadataViewState.getChapterMarkers(progressViewState.durationMs),
        onSeek = onSeek,
        modifier = modifier
    )
}

// =========================================================================
// 3. 修改 NewPlayerScreen 主界面的原有进度条组件调用：
// =========================================================================
// 原有代码（NewPlayerScreen.kt 第 416-423行）：
// PlaybackProgress(
//     currentPosition = playback.currentPosition,
//     totalDuration = playback.duration,
//     ...
// )
//
// 重构后代码：
// PlaybackProgressStateful(
//     viewModel = viewModel,
//     onSeek = { pos -> actions.playback.onSeek(pos, true) }
// )

// =========================================================================
// 4. 重构书签临时打字 Dialog，实现就近状态隔离 (State Locality)
// =========================================================================
// 现有痛点：settings.bookmarkTitle 在全局 ViewModel，打一个字刷新一下全屏！
// 重构后方案：

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 局部隔离书签创建对话框
 *
 * 中文注释：
 * 键盘敲击（每秒数次打字）引起的临时字符串状态【高频引发局部 Dialog 重组】。
 * 此时使用 remember { mutableStateOf("") } 将临时状态锁死在 Dialog 物理局部，
 * 全局 ViewModel 根本不知道打字过程，主屏幕完全被切断了频繁重组，性能极大飞跃。
 */
@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    onSaveBookmark: (title: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    // 局部临时输入框状态，高频的键盘打字只会在当前 Alert 树内重组
    var localInputText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            localInputText = "" // 物理清空
            onDismiss()
        },
        title = { Text("新建书签记录") },
        text = {
            TextField(
                value = localInputText,
                onValueChange = { localInputText = it }, // 输入字符仅在此处局部回传更新
                placeholder = { Text("请输入书签标签名称...") }
            )
        },
        confirmButton = {
            Button(onClick = {
                // 当且仅当用户点击保存这一瞬间，才一次性把最终录入内容通过 Lambda 回调派发给 ViewModel 写入数据库
                onSaveBookmark(localInputText.ifBlank { "未命名书签" })
                localInputText = ""
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            Button(onClick = {
                localInputText = ""
                onDismiss()
            }) {
                Text("取消")
            }
        }
    )
}
```

---

## 五、 📝 初学者重构避坑指南与灰度验证方案

重构中大型应用时，切忌一刀切。我们将提供一套平滑演进与性能断言的灰度落地避坑指南。

### 1. 初学者物理避坑三大黄金准则

1.  **协程必须指定 IO 线程，杜绝物理卡顿**：
    元数据提取和清单文件解析具有严重的磁盘 I/O 阻塞性。在编写分步 Step（如 `MetadataResolveStep`、`CoverExtractStep`）时，核心执行区域必须被 `withContext(Dispatchers.IO)` 包裹，切忌在 Compose UI 主线程中直接运行 Retriever，否则会导致 App 瞬间掉帧卡死。
2.  **Dumb（无状态）组件绝对不要引用 ViewModel**：
    无状态纯视图组件 `PlaybackProgress` 不论后续添加什么功能，都不要引入任何 `viewModel` 强引用。凡是交互行为，都必须使用 Lambda 接口上抛，保证小隔音间的百分百密封度。
3.  **双轨测试，绝不粗暴删除老文件**：
    开发完成的 `ImportPipelineEngine.kt` 可以挂载在全新的后台 Service 或者 Unit Test 下跑灰度集成测试。请保持 `ImportOrchestrator.kt` 物理存在，等一切性能指标（如 Layout Inspector 结果）均达到卓越后，再进行物理移除。

### 2. 性能断言与验证方案

- **第一步：使用 AS Layout Inspector 验证重组压降**：
  1. 部署 APlayer 到手机。
  2. 打开 Android Studio 的 `Layout Inspector` 窗格。
  3. 开始播放一首长有声书，点击进入 `NewPlayerScreen`。
  4. 观察界面组件重组计数器（Recomposition Count）。
     _断言指标_：在音乐播放期间，除了进度条 Slider 有重组变动以外，顶部 Appbar、底栏 Tabs、推荐书籍卡片等组件的 Recomposition 频次必须**稳步保持为 0**。
- **第二步：运行流水线分步单元测试**：
  1. 针对新增的独立工位 `ManifestParseStep.kt` 编写 JUnit 单元测试。
  2. Mock 一个简单的 `ImportContext` 和一个只含有单行错误 CUE 的 `FileInventory` 传入。
  3. 直接对 execute() 返回的 `StepResult` 进行断言检查，确信解析能输出 Failure 并抛出明确中文说明。

---

_方案起草人：Antigravity Pair-Programming Agent (DeepMind Advanced Agentic Coding Team)_
_起草时间：2026年5月20日_
