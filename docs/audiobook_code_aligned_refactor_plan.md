<!-- 注释：新增独立重构方案文件，只汇总 docs 目录方案与当前 Kotlin 实现的对齐结论，不修改任何既有文档或代码。 -->
# 有声书代码对齐重构方案

<!-- 注释：根据最新约束更新结论，本方案不再考虑旧代码迁移，只保留 UI 层作为新架构的接入方。 -->
## 1. 结论

本次重构不做旧代码迁移，不保留旧 repository、scanner、importer、DAO 的兼容路径。原代码只保留 UI 层和必要的 UI state / action 入口，数据层、扫描层、导入层、播放计划层直接切换到新的有声书架构。

新的主线是：

```text
扫描只负责生成稳定文件快照和来源候选
导入编排按 cue -> m3u8 -> audio 顺序决策
BookFile 成为文件占用事实来源
BookSource 退回为来源说明和导入记录
BookImporter 只做事务化落库
播放侧只消费 AUDIO 类型 BookFile
重扫只生成结果和待处理项，不自动覆盖用户数据
UI 层只依赖新的 LibraryFacade / PlayerFacade，不直接感知旧实现
```

<!-- 注释：明确旧实现的处理原则，避免后续再为旧表、旧 DAO 或旧扫描流程设计兼容分支。 -->
## 2. 切换原则

本方案按“新架构直接接管”执行：

- 不迁移 `AudiobookEntity` / `AudiobookDao` / 旧 `audiobooks` 表。
- 不兼容旧 `LibraryRepository.syncLibrary()` 的内部扫描和协调实现。
- 不沿用旧 `LibraryScanner` 的 priority claim 裁决。
- 不沿用旧 `BookImporter` 的多 DAO 非事务写入方式。
- 不沿用 `PendingScanAction` 自动 `SKIPPED` 的行为。
- 不保留 `M4B_EMBEDDED` 作为持久化或业务 source type。
- UI 层保留现有屏幕、组件、ViewModel 的交互形态，但它们要改接新的 façade。

旧代码在实施阶段可以先保留在文件系统中作为参考；真正删除任何文件前必须单独征得用户同意。

<!-- 注释：说明本方案参考的 docs 文件范围，避免把 UI 设计文档误纳入有声书数据流重构。 -->
## 3. docs 目录现状

`docs/audiobook_data_model.md` 定义了目标持久化模型，核心是“逻辑书 + 占用文件 + 章节 + 进度/书签锚点”。它要求 `BookFile` 同时覆盖 `SOURCE_MANIFEST` 和 `AUDIO`，并作为文件占用判断的唯一事实来源。

`docs/audiobook_import_single_file_sequence_plan.md` 定义了导入编排：先顺序消费 cue，再顺序消费 m3u8，最后顺序消费普通音频。启发式 `GENERATED_M3U8` 只应发生在普通音频阶段内部，而不是一个单独的全局候选池。

`docs/audiobook_rescan_flow.md` 定义了三类重扫：冷启动轻量重扫、用户主动全局重扫、新授权目录重扫。它强调冷启动只处理新文件，已入库文件可用性放到详情页按需检查。

`docs/material design.md` 是视觉系统说明，和本次有声书数据与扫描重构没有直接关系，本方案不纳入它的内容。

<!-- 注释：把当前代码重新划分为“保留 UI”和“替换实现”，不再把旧实现当成需要迁移的基线。 -->
## 4. 当前代码取舍

保留范围：

- `ui/screens`、`ui/components`、`ui/navigation`、`ui/state`、`ui/action`、`ui/theme` 尽量保留。
- `LibraryViewModel`、`PlayerViewModel`、`SettingsViewModel` 可以保留类名和 UI state 输出，但内部依赖要替换。
- `PlaybackManager` 和 `PlaybackService` 可以保留播放控制入口，但播放计划来源要替换为新 `PlaybackPlanProvider`。
- `PositionMapper` 的算法可以保留，因为它已经符合多文件全局位置映射。

替换范围：

- `data` 层实体、DAO、Repository 按新模型重建。
- `library` 层 scanner、reconciler、snapshot、manifest claim DTO 按新架构重建。
- `BookImporter` 按事务命令模型重建。
- `LibraryRepository` 不再作为核心业务聚合器，替换为新的 façade 或 use case 聚合入口。
- 旧 `AudiobookEntity`、`AudiobookDao`、`ClaimSource`、`LibraryReconciler`、priority claim 逻辑不参与新架构。

<!-- 注释：把原来的“差异修补”改成“直接替换点”，符合只保留 UI 层的新约束。 -->
## 5. 直接替换点

### 4.1 文件占用事实被拆散

文档要求 `BookFile` 是唯一文件占用来源，并通过 `fileRole = SOURCE_MANIFEST / AUDIO` 区分 manifest 和音频。当前代码中，音频在 `BookFileEntity`，manifest 在 `BookSourceEntity.sourceUri`。

这会导致两个问题：

- 冲突判断需要同时查 `books.sourceUri`、`book_files.uri` 和 `book_sources.sourceUri`。
- cue/m3u8 文件本身没有进入统一占用索引，`BookFile` 不能完整表达一本书占用了哪些文件。

替换方向：直接按目标模型重建 `BookFileEntity`，让 manifest 文件也写入 `book_files`。`BookSourceEntity` 只记录来源说明、导入记录和启发式信息，不参与文件占用事实。

### 4.2 扫描器仍是全局候选裁决

`LibraryScanner.scanDirectory()` 当前在每个目录里同时生成 cue、m3u8、单音频和 `HeuristicM3u8Suggester` 候选，再用 `priority` 做一次全局过滤。

这和文档里的单文件序列导入不完全一致。尤其是当前 `SINGLE_AUDIO` 的 priority 是 4，而 `GENERATED_M3U8` 是 5，单音频会先占用文件，启发式聚合通常无法生效。

替换方向：废弃旧 priority claim 裁决。新 `LibraryScanner` 只输出稳定文件清单，`ImportOrchestrator` 按 `cue -> m3u8 -> audio` 顺序消费。启发式聚合放进 audio 阶段内部，由目录内 buffer 决定输出 `GENERATED_M3U8` 还是多个 `SINGLE_AUDIO`。

### 4.3 `M4B_EMBEDDED` 是临时概念

文档倾向把 m4b 归入 `SINGLE_AUDIO`，通过内嵌章节判断生成章节。当前代码在 `ClaimSourceType` 中有 `M4B_EMBEDDED`，但 `BookImporter.importSingleAudio()` 最终仍写入 `BookEntity(sourceType = "SINGLE_AUDIO")`。

替换方向：新架构不引入 `M4B_EMBEDDED`。m4b 作为 `SINGLE_AUDIO` 处理，是否有章节由 `MetadataExtractor` 的章节结果决定。

### 4.4 导入不是统一事务边界

`BookImporter` 当前连续调用多个 DAO 方法。更新已有 manifest 书时，会先删除旧 `BookFile` 和旧 `Chapter`，再插入新结构；如果中间失败，会留下不完整状态。

替换方向：重建 `BookImporter`，所有导入、更新、待处理项写入和扫描完成标记都必须放进 `database.withTransaction {}`。更新已有书时，先在内存中完成新结构和锚点重映射，再一次性替换。

### 4.5 新书导入过早创建进度

`docs/audiobook_data_model.md` 说明新书导入时不主动创建 `BookProgress`。当前 `BookImporter.importSingleAudio()` 和 `importManifestBook()` 都会立即插入 0 位置进度。

替换方向：导入新书不创建进度。新的 `ProgressRepository` 或播放开始 use case 负责 upsert 第一条 `BookProgress`。这样 `BookWithProgress.isNotStarted` 可以通过缺少 progress 或 progress 为 0 表达。

### 4.6 书签缺少文件锚点

`BookmarkEntity` 已经有 `bookFileId`、`fileOffsetMs`、`fileFingerprint`，但 `LibraryRepository.addBookmark()` 只写 `globalPositionMs`。

替换方向：新 `BookmarkRepository` 创建书签时读取当前书的 `AUDIO` 文件列表，通过 `PositionMapper.globalToFilePosition()` 填入 `bookFileId` 和 `fileOffsetMs`。来源更新后再用锚点重算 `globalPositionMs`。

### 4.7 待处理项被自动跳过

`LibraryRepository.syncLibrary()` 当前把 `LibraryReconciler` 生成的 `pendingActions` 统一改成 `SKIPPED` 后写库。这样 UI 无法承接冲突、更新、不完整新书这些决策。

替换方向：待处理项默认保持 `PENDING`。只有用户明确处理后才变为 `RESOLVED`、`SKIPPED`，或按文档第一版直接删除对应项。

### 4.8 重扫边界过重

当前 `syncLibrary(trigger)` 无论 trigger 是什么，都会扫描所有 root、协调所有差异，并直接更新 `UNAVAILABLE` / `PARTIAL` / `READY`。

替换方向：引入 `RescanCoordinator` 和 `ScanScope`，拆开冷启动轻扫、用户全局重扫、新目录重扫。冷启动不检查旧文件可用性，不更新旧书状态；详情页打开时再做 `BookFile` 可用性检查。

<!-- 注释：给出目标模块边界，新架构直接成为核心实现，旧 repository 不再作为中转层。 -->
## 6. 目标结构

建议目标模块如下：

```text
data/
  BookEntity.kt
  BookFileEntity.kt
  BookSourceEntity.kt
  ChapterEntity.kt
  BookProgressEntity.kt
  BookmarkEntity.kt
  LibraryRootEntity.kt
  ScanSessionEntity.kt
  PendingScanActionEntity.kt
  BookDao.kt
  LibraryRootDao.kt
  ScanSessionDao.kt
  BookmarkDao.kt

library/
  LibraryScanner.kt          只遍历授权目录，输出 FileInventory
  ImportOrchestrator.kt      按 cue -> m3u8 -> audio 生成 ImportRunResult
  ImportRunContext.kt        保存本轮 ledger、reservedAudioIdentities、failures
  RunClaimLedger.kt          判断本轮占用和已有占用
  ExistingClaimIndex.kt      从 BookFile 派生已有文件占用
  BookImporter.kt            只负责事务化落库 ImportCommand
  RescanCoordinator.kt       统一三类重扫入口
  DetailAvailabilityChecker.kt 详情页按需检查已入库文件可用性

domain/
  LibraryFacade.kt           UI 层读取书库、搜索、筛选、重扫的唯一入口
  PlayerFacade.kt            UI 层播放、进度、书签的唯一入口
  PlaybackPlanProvider.kt    从新模型生成 BookPlaybackPlan
```

UI 层改接 façade：

```text
LibraryViewModel -> LibraryFacade
SettingsViewModel -> LibraryFacade
PlayerViewModel -> PlayerFacade
PlaybackManager -> PlaybackPlanProvider
```

<!-- 注释：第一阶段直接重建目标模型，不再设计旧表迁移或兼容字段。 -->
## 7. 阶段一：重建目标数据层

优先重建数据结构和 DAO，目标是让业务层只看到新模型。

建议变更：

- 直接按 `docs/audiobook_data_model.md` 重写 `BookEntity`。
- 直接按 `SOURCE_MANIFEST / AUDIO` 重写 `BookFileEntity`。
- `BookSourceEntity` 只保留来源说明字段，不作为占用来源。
- `ChapterEntity`、`BookProgressEntity`、`BookmarkEntity` 保留锚点模型。
- `ScanSessionEntity.trigger` 使用 `COLD_START / USER / ADD_LIBRARY_ROOT`。
- `PendingScanActionEntity.type` 使用 `CONFLICT / UPDATE_EXISTING / PARTIAL_NEW_BOOK`。
- `Book.status` 使用 `READY / PARTIAL / CONFLICT / UNAVAILABLE / DELETED`。
- `BookFile.status` 使用 `READY / MISSING`。

DAO 需要补齐：

- 按 `fileRole` 查询一本书的音频文件。
- 查询所有 `BookFile` 文件身份，构建 `ExistingClaimIndex`。
- 按 `actionKey` upsert 待处理项。
- 更新 `LibraryRoot.lastScannedAt` 和 `LibraryRoot.status`。
- 更新 `BookFile.status`，供详情页可用性检查使用。

数据库策略：

```text
不设计旧表迁移
开发期直接重建 Room schema
AppDatabase 只注册新架构实体和 DAO
旧实体和旧 DAO 不参与编译路径
```

<!-- 注释：第二阶段直接实现新扫描和导入编排，旧 ClaimSource / LibraryReconciler 不参与。 -->
## 8. 阶段二：扫描与导入编排

重建 `LibraryScanner` 和 `ImportOrchestrator`：

```text
LibraryScanner:
  LibraryRoot -> FileInventory

ImportOrchestrator:
  FileInventory + ExistingClaimIndex -> ImportRunResult
```

`FileInventory` 建议包含：

```kotlin
data class FileInventory(
    val rootId: String,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByDirectory: Map<String, List<FileRef>>
)
```

`FileRef` 至少保存：

```kotlin
data class FileRef(
    val uri: String,
    val rootId: String,
    val documentId: String,
    val relativePath: String,
    val parentDocumentId: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long
)
```

导入编排顺序固定：

```text
1. cueFiles.sortedBy(relativePath)
2. m3u8Files.sortedBy(relativePath)
3. audioFiles.sortedBy(relativePath)
4. heuristicBuffers.flushAll()
5. applyImportRun()
```

`RunClaimLedger` 只做本轮占用记录，规则是先到先占用：

```text
cue 优先于 m3u8
m3u8 优先于普通 audio
audio 阶段内，内嵌章节单文件优先于启发式 buffer
启发式生成的 GENERATED_M3U8 再写入 ledger
```

manifest 失败时不声明任何音频。manifest 成立但部分文件缺失时，已解析出身份的文件进入 reserved 集合，不让普通音频兜底重复导入。

<!-- 注释：第三阶段重写 BookImporter 的输入和事务边界，旧 BookImporter 不再作为实现基础。 -->
## 9. 阶段三：事务化落库

新 `BookImporter` 只接收 `ImportCommand`，不接收旧 `ClaimSource`。

建议命令类型：

```kotlin
sealed interface ImportCommand {
    data class CreateReadyBook(val draft: BookDraft) : ImportCommand
    data class UpdateExistingBook(val bookId: String, val draft: BookDraft) : ImportCommand
    data class CreatePendingAction(val draft: PendingScanActionDraft) : ImportCommand
    data class RecordFailure(val failure: ImportFailure) : ImportCommand
}
```

`BookDraft` 内部已经区分 manifest 和音频文件：

```kotlin
data class BookDraft(
    val book: BookEntityDraft,
    val files: List<BookFileDraft>,
    val source: BookSourceDraft?,
    val chapters: List<ChapterDraft>
)
```

落库规则：

- `CreateReadyBook` 插入 `Book`、全部 `BookFile`、`BookSource`、`Chapter`，不插入 `BookProgress`。
- `UpdateExistingBook` 保留 `bookId`、`addedAt`、用户编辑过的元数据、`BookProgress` 和 `Bookmark`。
- 更新已有书前，先用旧 `BookFile` 建立锚点映射，再生成新 `BookFile`，最后重算 progress/bookmark 的 `globalPositionMs`。
- 所有写入必须在 `database.withTransaction {}` 中完成。
- 封面生成失败不应让整本书导入失败，只记录 warning 并使用占位封面。

<!-- 注释：第四阶段用新 RescanCoordinator 接管扫描触发，旧 syncLibrary 只作为 UI 兼容名称或直接移除。 -->
## 10. 阶段四：重扫协调

新增 `RescanCoordinator`，UI 层通过 `LibraryFacade.rescan(type)` 调用。旧 `LibraryRepository.syncLibrary(trigger)` 不作为业务入口保留。

建议入口：

```kotlin
suspend fun rescan(type: RescanType, rootId: String? = null)
```

三类重扫：

| 类型 | 范围 | 做什么 | 不做什么 |
| --- | --- | --- | --- |
| `COLD_START_LIGHT` | 全部 active root | 处理未见过的新文件，生成新文件相关 pending | 不检查旧文件可用性，不全量刷新旧 pending |
| `USER_GLOBAL` | 全部 active root | 完整扫描，发现新书，刷新结构，生成 pending | 不自动覆盖已有书，不自动删除 |
| `NEW_LIBRARY_ROOT` | 新增 root | 扫描新目录，和全库已有 `BookFile` 做冲突判断 | 不重扫旧目录 |

`PendingScanAction` 写入规则：

- 冲突和结构更新保持 `PENDING`，不要在同步时自动改成 `SKIPPED`。
- 相同 `actionKey` 只刷新 payload、message、lastSeenScanId。
- 用户处理成功后再删除或更新状态。
- `Book.status.CONFLICT` 第一版不主动使用，冲突靠 pending 队列表达。

详情页可用性检查独立出来：

```text
DetailScreen 打开书
  -> DetailAvailabilityChecker.check(bookId)
  -> 尝试打开 AUDIO 类型 BookFile
  -> 更新 BookFile.status
  -> 重新计算 Book.status = READY / PARTIAL / UNAVAILABLE
```

<!-- 注释：第五阶段只保留 UI 交互形态，内部依赖全部换成新 façade 和 use case。 -->
## 11. 阶段五：播放与 UI 对齐

`BookPlaybackPlan` 只接收 `fileRole = AUDIO` 的 `BookFile`。即使 `BookFile` 表里新增了 `SOURCE_MANIFEST`，播放队列也不能包含 cue 或 m3u8 文件。

`PositionMapper` 继续只处理音频文件列表：

```text
globalPositionMs <-> AUDIO file index + positionInFileMs
```

`LibraryRepository.updateProgress()` 需要改成 upsert：

- 如果没有 `BookProgress`，创建第一条进度。
- 如果有 `BookProgress`，更新全局位置、当前文件、文件内偏移。
- 如果文件列表为空，只保存全局位置并标记锚点不可解析。

`LibraryRepository.addBookmark()` 需要补齐锚点：

- 通过 `PositionMapper.globalToFilePosition()` 找到当前文件。
- 写入 `bookFileId`、`fileOffsetMs`、`fileFingerprint`。
- 无法映射时写入 `anchorStatus = UNRESOLVED`。

UI 状态建议：

- `READY`: 正常展示和播放。
- `PARTIAL`: 详情页提示部分文件不可用，可播放可用部分。
- `UNAVAILABLE`: 详情页提示需要重新授权或恢复文件。
- `DELETED`: 普通列表隐藏，但文件占用仍保留。
- `PENDING` 扫描项：在设置页或扫描结果入口展示，不弹窗打断。

<!-- 注释：删除“遗留清理阶段”，改为直接切换实施顺序；实际文件删除仍需用户单独同意。 -->
## 12. 推荐实施顺序

第一批，新核心落地：

1. 新建统一常量或 enum-like object：source type、file role、status、scan trigger、pending action type。
2. 重写新实体和 DAO。
3. 重写 `AppDatabase`，只注册新架构实体和 DAO。
4. 新建 `LibraryFacade`、`PlayerFacade`、`PlaybackPlanProvider`。

第二批，新导入链路：

1. 新建 `FileInventory`、`FileRef`、`ExistingClaimIndex`。
2. 新建 `ImportRunContext`、`RunClaimLedger`、`ImportOrchestrator`。
3. 新建事务化 `BookImporter`。
4. 新书导入不创建 `BookProgress`。

第三批，新扫描和重扫：

1. 重写 `LibraryScanner`，只产出文件清单。
2. 新建 `RescanCoordinator` 和 `ScanScope`。
3. 冷启动轻扫只处理新文件。
4. 用户全局重扫才刷新结构和 pending。
5. 新目录重扫只扫描新增 root。

第四批，UI 接入：

1. `LibraryViewModel` 改接 `LibraryFacade`。
2. `SettingsViewModel` 改接 `LibraryFacade.rescan()` 和 root 管理接口。
3. `PlayerViewModel` 改接 `PlayerFacade`。
4. `PlaybackManager` 改接 `PlaybackPlanProvider`。
5. 设置页或扫描结果入口展示 `PENDING` 待处理项，不自动 `SKIPPED`。

第五批，旧实现隔离：

1. 旧 data/library 实现不再被 UI 或新核心引用。
2. 需要删除旧文件时，先列出清单并等待用户同意。
3. 不删除 docs 旧方案，只由本文档声明新的执行方案。

<!-- 注释：给出必须覆盖的回归场景，确保重构不是只改结构而没有验证业务行为。 -->
## 13. 必测场景

导入场景：

- `book.cue + book.flac` 只生成一本 `CUE`，`book.flac` 不再作为单文件导入。
- `album.cue + album.m3u8 + 001.mp3 + 002.mp3` 中 cue 优先，m3u8 进入冲突待处理。
- `album.m3u8 + 001.mp3 + 002.mp3` 生成一本 `M3U8`，两个 mp3 不再单独导入。
- `001.mp3 + 002.mp3 + 003.mp3` 在强信号满足时生成 `GENERATED_M3U8`，否则各自为 `SINGLE_AUDIO`。
- `chaptered.m4b` 作为 `SINGLE_AUDIO` 导入，章节来自内嵌 metadata。
- `broken.cue + book.flac` 中 cue 失败不声明音频，`book.flac` 后续按普通音频处理。

重扫场景：

- 冷启动发现新文件时只导入新书，不把旧书改成 `UNAVAILABLE`。
- 用户全局重扫发现 manifest 结构变化时生成 `UPDATE_EXISTING`，不自动覆盖旧书。
- 新目录重扫只处理新增 root，不刷新旧 root。
- 用户删除书籍后标记 `DELETED`，文件占用仍保留，重扫不立刻重复导入。

播放场景：

- 多文件书从全局位置恢复到正确文件和文件内偏移。
- 书签创建后保存 `bookFileId` 和 `fileOffsetMs`。
- 更新 manifest 结构后，进度和书签能按文件锚点重映射。
- `BookFile(SOURCE_MANIFEST)` 不进入 Media3 播放队列。

<!-- 注释：最后总结本次重构的核心取舍，明确这是直接替换架构，不是旧实现修补。 -->
## 14. 核心取舍

本次重构按直接替换执行。旧 data/library/repository 实现不作为兼容对象，只保留 UI 层并把 UI 接到新 façade：

```text
Book = 逻辑书
BookFile = 文件占用事实
BookSource = 来源说明
Chapter = 展示和跳转结构
BookProgress / Bookmark = 稳定锚点
LibraryScanner = 文件快照
ImportOrchestrator = 导入决策
BookImporter = 事务落库
RescanCoordinator = 扫描触发边界
LibraryFacade / PlayerFacade = UI 接入层
```

按这个顺序推进，可以直接绕开旧实现里最容易出错的地方：启发式聚合不会生效、pending 被自动跳过、更新导入不事务化、进度和书签锚点没填满。后续实现时只需要保证 UI 层行为稳定，不需要保持旧数据或旧内部 API 兼容。
