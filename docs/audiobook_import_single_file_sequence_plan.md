<!-- 注释：新增独立方案文件，保留 docs/audiobook_handling.md 原文不变，只描述新的单文件序列导入流程。 -->

# 有声书单文件序列导入方案

本文档是对 `docs/audiobook_handling.md` 中导入流程的简化方案。

目标是在不改持久化数据模型的前提下，把导入控制流改成单文件序列：

```text
1. 顺序处理 cue
2. 顺序处理 m3u8
3. 顺序处理普通音频文件
```

普通音频阶段仍然允许启发式聚合，但队列入口始终是单个音频文件。启发式逻辑只作为音频处理器内部的缓冲和 flush 规则存在，不再单独形成一个全局 `heuristicAudioPool`。

## 1. 保留范围

本方案保留 `audiobook_data_model.md` 中的数据模型，不要求改 Room 表结构。

继续保留：

- `Book`
- `BookFile`
- `Chapter`
- `BookProgress`
- `Bookmark`
- `ScanSession`
- `PendingScanAction`
- `Book.sourceType = SINGLE_AUDIO / CUE / M3U8 / GENERATED_M3U8`
- `BookFile.fileRole = SOURCE_MANIFEST / AUDIO`
- `BookFile.status = READY / MISSING`
- `Book.generatedManifestJson`
- `Book.heuristicRuleVersion`

可以简化的是扫描期和导入期 DTO，例如 `SourceCandidate`、`ClaimSource`、`ClaimResolver` 这类临时概念。它们不是持久化数据模型，可以改成更轻的运行期上下文。

## 2. 核心改法

新流程改成一个确定性的序列消费器：

```text
FileInventory
  -> cueFiles.sorted()
  -> m3u8Files.sorted()
  -> audioFiles.sorted()

ImportRunContext
  -> existingClaimIndex
  -> runClaimLedger
  -> reservedAudioIdentities
  -> heuristicBuffers
  -> failures / pendingActions / readyImports
```

`ImportRunContext` 只在本轮导入期间存在。它负责记录哪些文件已经被有效来源声明、哪些文件命中已入库书、哪些音频需要跳过单文件兜底。

一句话规则：

> `cue` 先占用，`m3u8` 后占用，普通音频最后兜底；有效 manifest 声明过的音频，无论最后是直接入库还是进入待处理，都不再作为普通单文件重复导入。

## 3. 运行期上下文

建议把原来的全局 claim 裁决收束成一个轻量 ledger。

```kotlin
data class ImportRunContext(
    val existingClaimIndex: ExistingClaimIndex,
    val runClaimLedger: RunClaimLedger,
    val reservedAudioIdentities: MutableSet<FileIdentity>,
    val heuristicBuffers: HeuristicAudioBuffers,
    val readyImports: MutableList<ImportCommand>,
    val pendingActions: MutableList<PendingScanActionDraft>,
    val failures: MutableList<ImportFailure>
)
```

职责说明：

- `existingClaimIndex`: 从已有 `BookFile` 派生，表示数据库里已经被书占用的文件。
- `runClaimLedger`: 本轮导入中由 `cue` / `m3u8` / `GENERATED_M3U8` / `SINGLE_AUDIO` 产生的占用记录。
- `reservedAudioIdentities`: 所有已经被有效来源声明过的音频文件身份；普通音频队列遇到这些文件时直接跳过。
- `heuristicBuffers`: 普通音频阶段的目录内缓冲器，只接收当前单个音频文件。
- `readyImports`: 可以直接执行的导入命令。
- `pendingActions`: 需要用户确认的冲突、更新、不完整新书。
- `failures`: manifest 解析失败、路径无法解析、章节结构错误等来源级失败。

`runClaimLedger` 不需要做复杂的二次裁决，只需要支持“先到先占用”：

```kotlin
class RunClaimLedger {
    fun reserve(source: ImportSourceRef, files: List<FileIdentity>): ReservationResult
}
```

`ReservationResult` 至少表达：

- 哪些文件本轮首次被该来源声明。
- 哪些文件已经被本轮更早来源声明。
- 哪些文件命中已有 `BookFile`。
- 这个来源是否可以直接导入。
- 这个来源是否需要进入 `PendingScanAction`。

## 4. 总流程

```kotlin
suspend fun importLibrary(root: LibraryRoot) {
    val inventory = fileTreeWalker.snapshot(root)
    val context = ImportRunContext(
        existingClaimIndex = bookDao.loadExistingClaimIndex(),
        runClaimLedger = RunClaimLedger(),
        reservedAudioIdentities = mutableSetOf(),
        heuristicBuffers = HeuristicAudioBuffers(),
        readyImports = mutableListOf(),
        pendingActions = mutableListOf(),
        failures = mutableListOf()
    )

    inventory.cueFiles.sortedByStableFileKey()
        .forEach { cueFile -> processCueFile(cueFile, context) }

    inventory.m3u8Files.sortedByStableFileKey()
        .forEach { m3u8File -> processM3u8File(m3u8File, context) }

    inventory.audioFiles.sortedByStableFileKey()
        .forEach { audioFile -> processAudioFile(audioFile, context) }

    context.heuristicBuffers.flushAll(context)
    applyImportRun(context)
}
```

排序必须稳定，建议优先使用授权目录内的标准化相对路径，其次使用 SAF `documentId` 或 URI。这样同一批文件每次扫描顺序一致。

## 5. cue 阶段

`cue` 阶段只顺序消费 `.cue` 文件。
解析成功后在同目录找同名txt文件，找到后将内容当作metadata.description使用

- 读取阶段只从 txt 输入流读前 2 \* 1024 bytes。
- 如果刚好达到 2KB，会打 log：Manifest txt description reached 2048B read limit。
- 解码后如果超过 2000 字符，会截断并打 log：Manifest txt description truncated to
  2000 chars。
- UTF-8 如果刚好在 2KB 边界截断半个字符，会尝试丢掉末尾不完整字符，避免因为边界截断
  导致乱码。
- 保留原来的编码优先级：UTF-8 / Big5 / Shift-JIS。

处理规则：

1. 解析当前 cue。
2. 以 cue 所在目录为 base 解析 `FILE` 引用。
3. 如果 cue 语法失败、引用路径无法映射到确定文件身份、章节结构不成立，记录 failure，不声明任何音频。
4. 如果 cue 成立，为 cue 文件生成 `SOURCE_MANIFEST` draft，为引用音频生成 `AUDIO` draft。
5. 把所有引用音频加入 `reservedAudioIdentities`。
6. 用 `runClaimLedger.reserve()` 判断本轮冲突和已有书冲突。
7. 无冲突且全部文件可用时，生成 `ImportCommand.CreateBook.Ready`。
8. 引用文件可定位但不可用时，生成 `PARTIAL_NEW_BOOK` 待处理。
9. 命中已有书或本轮更早来源时，生成 `CONFLICT` 待处理。

单文件 cue 不特殊化。它仍然是：

```text
book.cue -> book.flac
sourceType = CUE
BookFile(SOURCE_MANIFEST) = book.cue
BookFile(AUDIO) = book.flac
Chapter.source = CUE
```

只要 cue 成立并声明了 `book.flac`，`book.flac` 就不会再进入普通音频兜底。

## 6. m3u8 阶段

`m3u8` 阶段在所有 cue 处理完之后顺序执行。
解析成功后在同目录找同名txt文件，找到后将内容当作metadata.description使用

处理规则：

1. 解析当前 m3u8。
2. 拒绝网络 URL，第一版只支持本地相对路径或授权目录内文件。
3. 以 m3u8 所在目录为 base 解析条目。
4. 如果 m3u8 语法失败、条目路径无法映射到确定文件身份、顺序结构不成立，记录 failure，不声明任何音频。
5. 如果 m3u8 成立，为 m3u8 文件生成 `SOURCE_MANIFEST` draft，为条目音频生成有序 `AUDIO` draft。
6. 把所有条目音频加入 `reservedAudioIdentities`。
7. 用 `runClaimLedger.reserve()` 判断是否和 cue、早先 m3u8 或已有书冲突。
8. 没有冲突且全部文件可用时，生成 `ImportCommand.CreateBook.Ready`。
9. 有冲突、结构变化或部分文件不可用时，生成对应 `PendingScanAction`。

如果某个 m3u8 引用了已经被 cue 声明的音频，cue 保持优先。m3u8 不覆盖 cue，只进入待处理。

## 7. 普通音频阶段

普通音频阶段仍然按单个文件顺序消费。

处理规则：

1. 如果当前音频已经在 `reservedAudioIdentities` 中，跳过。
2. 如果当前音频命中 `existingClaimIndex`，只更新可见性或 last seen，不创建新书。
3. 读取当前音频的元数据、时长、封面和内嵌章节。
4. 如果有可用内嵌章节，直接生成 `SINGLE_AUDIO`。
5. 如果没有内嵌章节，把当前文件交给 `HeuristicAudioBuffers.accept(audioFile, metadata)`。
6. `accept()` 可以暂存当前文件，也可以 flush 出一个 `GENERATED_M3U8` 或若干 `SINGLE_AUDIO`。
7. 所有音频遍历结束后，调用 `flushAll()` 处理剩余缓冲。

这里的关键是：启发式聚合不是一个单独队列。

```text
audio queue item
  -> 当前文件是否已被 manifest 声明
  -> 当前文件是否已有内嵌章节
  -> 当前文件进入目录内 heuristic buffer
  -> buffer 满足规则时输出 GENERATED_M3U8
  -> buffer 不满足规则时逐个输出 SINGLE_AUDIO
```

## 8. 启发式聚合

启发式聚合只在普通音频阶段内部发生。

建议组件：

```text
HeuristicAudioBuffers
  -> Map<HeuristicBufferKey, HeuristicAudioBuffer>
```

`HeuristicBufferKey` 只使用直接父目录：

```kotlin
data class HeuristicBufferKey(
    val rootId: Long,
    val parentDocumentId: String
)
```

这样可以保证启发式聚合不会跨父子目录、兄弟目录或不同授权目录。

每个 buffer 只接收当前单个音频文件：

```kotlin
sealed interface HeuristicAcceptResult {
    data object Hold : HeuristicAcceptResult
    data class EmitGeneratedM3u8(val files: List<AudioFileRef>) : HeuristicAcceptResult
    data class EmitSingles(val files: List<AudioFileRef>) : HeuristicAcceptResult
}
```

保守规则：

- 只聚合同一个直接父目录下的音频。
- 只聚合没有内嵌章节的音频。
- 只聚合没有被 cue/m3u8 声明的音频。
- 3 个及以上文件需要满足强信号之一：ID3 Album 一致且 track number 连续，或文件名自然序号连续且公共前缀可信。
- 2 个文件只在 ID3 Album 一致且 track number 为 1/2 时聚合。
- 遇到目录切换、track number 断裂、文件名序列断裂、Album 明显不同，当前 buffer 必须 flush。

flush 规则：

```text
buffer 可形成高置信度组
  -> 输出 GENERATED_M3U8
  -> 为组内文件写入 runClaimLedger
  -> sourceType = GENERATED_M3U8
  -> Book.generatedManifestJson 保存虚拟清单

buffer 不满足聚合规则
  -> 按原顺序逐个输出 SINGLE_AUDIO
```

这样既保留自动整理散装音频的能力，又不会让主流程退回“先收集一整个 heuristic pool 再批处理”的复杂形态。

## 9. 落库映射

### 9.1 CUE

```text
Book.sourceType = CUE
BookFile(SOURCE_MANIFEST) = cue 文件
BookFile(AUDIO) = cue 引用音频
Chapter.source = CUE
```

### 9.2 M3U8

```text
Book.sourceType = M3U8
BookFile(SOURCE_MANIFEST) = m3u8 文件
BookFile(AUDIO) = m3u8 条目音频，按播放顺序保存
Chapter.source = M3U8
```

### 9.3 GENERATED_M3U8

```text
Book.sourceType = GENERATED_M3U8
Book.generatedManifestJson = 应用内虚拟清单
Book.heuristicRuleVersion = 当前启发式规则版本
BookFile(AUDIO) = 启发式组内音频，按自然顺序保存
Chapter.source = GENERATED
```

`GENERATED_M3U8` 不写外部 `.m3u8` 文件，也没有 `BookFile(SOURCE_MANIFEST)`。

### 9.4 SINGLE_AUDIO

```text
Book.sourceType = SINGLE_AUDIO
BookFile(AUDIO) = 当前音频文件
Chapter.source = EMBEDDED / GENERATED
```

带内嵌章节的音频生成多个章节；没有章节的音频至少生成一个默认章节，默认生成章节标题为音频元数据标题，无元数据标题使用文件名。

## 10. 冲突策略

冲突判断也按序列化方式处理，不再需要最后统一二次裁决。
扫描导入冲突只进入 PendingScanAction，不改已有的Book.status，Book.status.CONFLICT为保留状态

### 10.1 本轮来源冲突

如果后处理的来源引用了已经被前面来源声明的音频：

```text
cue vs cue   -> 先处理的 cue 保持 active
cue vs m3u8  -> cue 保持 active
m3u8 vs m3u8 -> 先处理的 m3u8 保持 active
manifest vs single audio -> manifest 保持 active，single audio 直接跳过
```

后处理来源不覆盖前处理来源，只生成 `PendingScanAction(CONFLICT)`。

### 10.2 已入库书冲突

如果当前来源命中已有 `BookFile`：

- 完全相同且结构未变化：更新 last seen / 文件状态，不重复导入。
- 来源结构变化：生成 `UPDATE_EXISTING`，用户确认后更新旧书。
- 新来源想占用已有书文件：生成 `CONFLICT`，让用户选择保持旧书、更新旧书、另存为新书或跳过。

默认不自动覆盖已有书，避免破坏进度、书签和用户编辑字段。

### 10.3 manifest 失败

manifest 来源级失败时不声明任何文件。

这意味着：

- `book.cue` 语法错误时，它引用的 `book.flac` 仍可在后续 m3u8 或普通音频阶段被处理。
- `book.m3u8` 条目路径无法解析时，它列出的音频仍可在普通音频阶段被处理。
- 只有 manifest 成立并解析出确定文件身份后，才会把引用音频加入 `reservedAudioIdentities`。

### 10.4 manifest 部分不可用

manifest 成立，但某些引用音频不可播放或缺失时：

- 已解析出身份的音频仍加入 `reservedAudioIdentities`。
- 不可用的书标为 `BookFileDraft.status = MISSING`。
- 新书候选进入 `PARTIAL_NEW_BOOK`，用户确认导入不完整书后，才写入 Book.status = PARTIAL 和 BookFile.status = MISSING。
- 已有书候选进入 `CONFLICT` 。

这样可以避免缺失文件又被普通音频兜底误导入。

## 11. 应用时机

为了简单，每个来源最终只生成一种结果：

```text
ReadyImport
PendingAction
Failure
Noop
```

`applyImportRun(context)` 可以继续使用事务，但不需要理解全局候选图：

- `ReadyImport`: 交给 `BookImporter` 写入 `Book` / `BookFile` / `Chapter`。
- `PendingAction`: 写入 `PendingScanAction`。
- `Failure`: 写入扫描结果或日志，不落书库。
- `Noop`: 已存在、已跳过、已被更高优先级来源占用。

第一版：扫描完成后统一 apply，不做边扫边入库。
后续优化：如果扫描耗时明显，再支持 ReadyImport 独立事务增量写入。

## 12. 必测场景

```text
folder/
  book.cue
  book.flac
```

结果：导入一本 `CUE` 单文件章节书，`book.flac` 不再进入普通音频阶段。

```text
folder/
  album.cue
  001.mp3
  002.mp3
  album.m3u8
```

结果：cue 先占用 `001.mp3` / `002.mp3`；m3u8 进入冲突待处理，不覆盖 cue。

```text
folder/
  album.m3u8
  001.mp3
  002.mp3
```

结果：m3u8 导入一本 `M3U8` 聚合书；两个 mp3 不再作为单文件导入。

```text
folder/
  001.mp3
  002.mp3
  003.mp3
```

结果：普通音频队列逐个处理；启发式 buffer 满足强信号后 flush 成一本 `GENERATED_M3U8`。

```text
folder/
  chaptered.m4b
  001.mp3
  002.mp3
```

结果：`chaptered.m4b` 因内嵌章节直接导入 `SINGLE_AUDIO`，不参与启发式聚合；`001.mp3` / `002.mp3` 只有满足 2 文件强规则才聚合，否则各自导入单文件。

```text
folder/
  broken.cue
  book.flac
```

结果：`broken.cue` 记录 failure，不声明 `book.flac`；`book.flac` 后续按普通音频处理。

## 13. 结论

这版方案把复杂度从“全局候选和统一裁决”降到“固定顺序 + 运行期占用表”。

数据模型仍然支持单文件书、cue、m3u8 和启发式生成的虚拟 m3u8；变化只发生在导入编排层：

```text
cue 队列先声明
m3u8 队列后声明
audio 队列最后兜底
heuristic 只在 audio 队列内部缓冲
Book / BookFile / Chapter 不变
```

## 14.补充

### 14.1. 封面

优先级：

1. m4b 或音频文件内嵌封面。
2. manifest 同目录下的 `cover.jpg` / `folder.jpg` / `artwork.png`。
3. 第一条可用音频文件的内嵌封面。
4. 占位封面。

### 14.2. 标题生成策略

显式 manifest 导入和启发式生成的标题策略不同：

- `CUE`: `Book.title` 优先使用 cue 内容中的全局 `TITLE`；如果缺失或为空，再 fallback 到 cue 文件名。
- `M3U8`: `Book.title` 优先使用 m3u8 文件名。`#EXTINF` 和音频内嵌标题只参与章节名候选，不参与书名推导。
- `GENERATED_M3U8`: 没有真实 manifest 文件，才使用启发式书名生成策略。
- `SINGLE_AUDIO`: 音频元数据内的album名 > 音频元数据内的标题 如果缺失或为空，再 fallback 到音频文件名。

启发式 m3u8 默认聚合会遇到标题处理问题。标题必须拆成两层：

```text
Book title    = 这组文件作为一本书时的书名
Chapter title = 每个文件或 track 在书里的章节名
```

#### 14.2.1 书名来源

对启发式生成的 `GENERATED_M3U8`，书名优先级：

1. 所有文件 ID3 Album 一致时，使用 Album。
2. 使用同组音频所在的直接父文件夹名。
3. 使用高置信度公共前缀。
4. 使用第一个文件名清理后的标题。

#### 14.2.2 章节名来源

章节名优先级：

1. ID3 Title。sort by index
2. 文件名去掉书名、公共前缀和序号后的剩余部分，自然排序。
3. 文件名清理后的标题，自然排序。
4. `第 N 章` 或 `Chapter N`，自然排序。

特殊规则：

- 如果所有文件的 ID3 Title 完全相同，不要把它作为每个章节名。这通常是整本书书名。
- 如果文件名只有编号，例如 `001.mp3`，直接生成 `第 1 章`。
- 文件名清理要保守。宁可标题普通，也不要过度清理导致语义丢失。

编码规则 utf-8 bom > utf-8 > big5 > windows1256 > shift-jis
