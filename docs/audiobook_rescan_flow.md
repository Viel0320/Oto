<!-- 注释：新增独立重扫流程方案文件，基于 audiobook_data_model.md 和 audiobook_import_single_file_sequence_plan.md，不修改原有两份文档。 -->
# 有声书重扫流程方案

本文档描述有声书库的三类重扫流程：

- 冷启动轻量级重扫
- 用户主动触发的全局重扫
- 添加新授权目录的新目录重扫

重扫流程继续沿用现有数据模型，不引入新的持久化表。`Book` / `BookFile` / `Chapter` / `BookProgress` / `Bookmark` / `ScanSession` / `PendingScanAction` 的职责保持不变。

<!-- 注释：说明本文档和现有两份方案的关系，避免重扫流程重新定义数据模型或导入顺序。 -->
## 1. 基础原则

重扫负责处理新文件、形成新书候选、刷新来源结构和生成待处理项，不直接处理播放进度、书签删除和已入库 `BookFile` 的播放可用性检查。

共同规则：

- 文件身份判断以 `BookFile` 为准。
- 授权目录边界以 `LibraryRoot` 为准。
- 可创建新书的扫描继续复用单文件序列导入顺序：`cue -> m3u8 -> audio`。
- 扫描中只生成运行期结果，不在 `ScanSession.RUNNING` 状态下写入新书或结构性更新。
- 扫描完整完成后，在同一事务中写入新书、刷新结构性结果、创建或刷新 `PendingScanAction`，再标记 `ScanSession.COMPLETED`。
- 扫描失败、应用被杀或授权中断时，本轮 `RUNNING` 结果全部丢弃，不恢复成有效结果。
- 已入库 `BookFile` 的可用性检查由 `DetailScreen` 打开详情页时按需触发，不放进冷启动轻量级重扫。
- 重扫不删除真实音频文件、cue 文件、m3u8 文件。
- 重扫不自动删除 `Book`、`BookFile`、`BookProgress`、`Bookmark`。

<!-- 注释：把三种业务触发类型直接映射到第一版 ScanSession.trigger，包含新增授权目录场景。 -->
## 2. 重扫类型

运行期使用 `RescanType` 区分三类重扫：

```kotlin
enum class RescanType {
    COLD_START_LIGHT,
    USER_GLOBAL,
    NEW_LIBRARY_ROOT
}
```

`ScanSession.trigger` 第一版直接区分三类来源：

- `COLD_START_LIGHT` 写入 `ScanSession.trigger = COLD_START`。
- `USER_GLOBAL` 写入 `ScanSession.trigger = USER`。
- `NEW_LIBRARY_ROOT` 由用户添加授权目录触发，写入 `ScanSession.trigger = ADD_LIBRARY_ROOT`。

<!-- 注释：定义扫描范围对象，让冷启动、全局和新目录三种流程共享同一套执行框架。 -->
## 3. 扫描范围

```kotlin
data class ScanScope(
    val rootIds: Set<LibraryRootId>,
    val includeNewFileProcessing: Boolean,
    val includeStructureRefresh: Boolean,
    val pendingActionMode: PendingActionMode
)

enum class PendingActionMode {
    NONE,
    NEW_FILES_ONLY,
    FULL_REFRESH
}
```

字段含义：

- `rootIds`: 本轮扫描覆盖的授权目录。
- `includeNewFileProcessing`: 是否处理本轮发现的未见过文件。
- `includeStructureRefresh`: 是否重新解析 manifest、章节和聚合结构。
- `pendingActionMode`: 待处理项处理范围。
  - `NONE`: 不生成、不刷新待处理项。
  - `NEW_FILES_ONLY`: 只为本轮新文件产生的冲突或不完整候选生成/刷新待处理项。
  - `FULL_REFRESH`: 按完整扫描结果生成或刷新待处理项。

三类重扫对应范围：

| 重扫类型         | rootIds                    | 新文件处理 | 结构刷新 | 待处理项模式     |
| ---------------- | -------------------------- | ---------- | -------- | ---------------- |
| 冷启动轻量级重扫 | 已授权且状态可用的全部目录 | 是         | 否       | `NEW_FILES_ONLY` |
| 用户主动全局重扫 | 已授权且状态可用的全部目录 | 是         | 是       | `FULL_REFRESH`   |
| 新目录重扫       | 新增授权目录               | 是         | 是       | `FULL_REFRESH`   |

<!-- 注释：冷启动重扫只处理未见过的新文件，已入库文件可用性改由详情页按需检查，避免启动时重评估旧文件。 -->
## 4. 冷启动轻量级重扫

冷启动轻量级重扫用于应用启动后处理未见过的新文件。它不重新处理已入库文件，不检查已入库 `BookFile` 是否还能访问，不重新组织已有书章节，但会为新文件造成的冲突或不完整候选生成 `PendingScanAction`。

目标：

- 只处理授权目录中本轮新发现、以前没有被 `BookFile` 占用过的文件。
- 对这些新文件尝试形成没有冲突、可以直接导入的新书。
- 对新文件引发的冲突或不完整候选生成待处理项。
- 保持启动扫描足够轻，不阻塞用户进入书库。
- 避免启动时刷新旧书状态或全量重算旧待处理队列。

扫描范围：

```kotlin
ScanScope(
    rootIds = activeLibraryRootIds,
    includeNewFileProcessing = true,
    includeStructureRefresh = false,
    pendingActionMode = PendingActionMode.NEW_FILES_ONLY
)
```

处理流程：

1. 创建 `ScanSession(trigger = COLD_START, status = RUNNING)`。
2. 对所有可用 `LibraryRoot` 建立文件快照。
3. 从已有 `BookFile` 构建 `existingClaimIndex`。
4. 过滤出未命中已有 `BookFile` 的新文件，已入库文件直接跳过。
5. 只在新文件集合内按 `cue -> m3u8 -> audio` 顺序识别新来源。
6. 完全由新文件组成、没有冲突、文件完整且结构确定的新候选生成 `ReadyImport`。
7. 新来源引用旧文件或想占用已有 `BookFile` 时，生成 `PendingScanAction(CONFLICT)`。
8. 新来源结构成立但部分文件不可用时，生成 `PendingScanAction(PARTIAL_NEW_BOOK)`。
9. 新文件使已入库书的来源结构出现变化时，生成 `PendingScanAction(UPDATE_EXISTING)`。
10. 解析失败的 manifest 只记录轻量 failure，不声明其引用音频。
11. 扫描完整完成后，写入 `ReadyImport`、新文件相关 `PendingScanAction` 和 `LibraryRoot.lastScannedAt`。
12. 标记 `ScanSession(status = COMPLETED)`。

冷启动轻量级重扫不做：

- 不检查已入库 `BookFile` 是否可播放或可打开。
- 不重新处理已入库文件。
- 不用旧文件参与新书候选。
- 不更新已有 `BookFile.status`。
- 不根据旧书文件缺失修改 `Book.status`。
- 不对已有书执行结构刷新或 `UPDATE_EXISTING` 判断。
- 不直接落库不完整新书；不完整新书只生成 `PendingScanAction(PARTIAL_NEW_BOOK)`。
- 不全量刷新旧的 `PendingScanAction`。

<!-- 注释：全局重扫是完整、显式、用户可预期的重扫，会复用单文件序列导入并刷新待处理项。 -->
## 5. 用户主动触发的全局重扫

用户主动全局重扫用于完整刷新整个媒体库。它可以发现新书、刷新 manifest 结构、生成冲突和不完整新书的待处理项。

目标：

- 发现所有授权目录中的新书。
- 检查已入库书的来源结构是否仍和扫描结果一致。
- 检查 cue/m3u8 或文件集合是否发生结构变化。
- 生成 `CONFLICT`、`UPDATE_EXISTING`、`PARTIAL_NEW_BOOK` 等 `PendingScanAction`。
- 避免自动覆盖已有书，保护进度、书签和用户编辑字段。

扫描范围：

```kotlin
ScanScope(
    rootIds = activeLibraryRootIds,
    includeNewFileProcessing = true,
    includeStructureRefresh = true,
    pendingActionMode = PendingActionMode.FULL_REFRESH
)
```

处理流程：

1. 创建 `ScanSession(trigger = USER, status = RUNNING)`。
2. 对所有可用 `LibraryRoot` 建立文件快照。
3. 从已有 `BookFile` 构建 `existingClaimIndex`。
4. 对每个授权目录执行单文件序列导入流程：`cue -> m3u8 -> audio`。
5. 对完全命中已有 `BookFile` 且结构未变化的书，只刷新 `lastSeenScanId` 和 `Book.lastScannedAt`。
6. 对新来源且没有冲突的候选，生成 `ReadyImport`。
7. 对命中已有书但结构变化的候选，生成 `PendingScanAction(UPDATE_EXISTING)`。
8. 对新来源想占用已有 `BookFile` 的候选，生成 `PendingScanAction(CONFLICT)`。
9. 对 manifest 成立但部分文件缺失的新候选，生成 `PendingScanAction(PARTIAL_NEW_BOOK)`。
10. 对解析失败的 manifest 记录 `Failure`，不声明其引用音频。
11. 扫描完整完成后，统一 `applyImportRun(context)`。
12. 标记 `ScanSession(status = COMPLETED)`。

写库规则：

- `ReadyImport` 写入 `Book` / `BookFile` / `Chapter`。
- `PendingAction` 写入或刷新 `PendingScanAction`。
- `Failure` 写入扫描日志或统计，不落书库。
- `Noop` 只刷新必要的 `lastSeenScanId` 和 `lastScannedAt`。
- 扫描导入冲突只进入 `PendingScanAction`，不直接改已有 `Book.status`。
- 用户确认 `PARTIAL_NEW_BOOK` 后，才写入 `Book.status = PARTIAL` 和 `BookFile.status = MISSING`。

<!-- 注释：新目录重扫只扫描新增授权目录，但仍使用完整导入能力，以便新目录第一次进入书库。 -->
## 6. 添加新授权目录的新目录重扫

新目录重扫发生在用户新增一个 `LibraryRoot` 后。它只扫描新增目录，不扫描旧目录。

目标：

- 把新增授权目录中的可识别书籍导入书库。
- 避免新目录中的文件和已有书产生重复归属。
- 允许新目录中的 cue/m3u8/audio 按完整导入规则生成新书或待处理项。

扫描范围：

```kotlin
ScanScope(
    rootIds = setOf(newRootId),
    includeNewFileProcessing = true,
    includeStructureRefresh = true,
    pendingActionMode = PendingActionMode.FULL_REFRESH
)
```

处理流程：

1. 写入新的 `LibraryRoot(status = ACTIVE)`。
2. 创建 `ScanSession(trigger = ADD_LIBRARY_ROOT, status = RUNNING)`。
3. 只对 `newRootId` 建立文件快照。
4. 从全库已有 `BookFile` 构建 `existingClaimIndex`，防止新目录文件误占已有书。
5. 对新增目录执行单文件序列导入流程：`cue -> m3u8 -> audio`。
6. 没有冲突的新候选生成 `ReadyImport`。
7. 和已有书命中文件身份的新候选生成 `PendingScanAction(CONFLICT)`。
8. 结构成立但部分文件不可用的新候选生成 `PendingScanAction(PARTIAL_NEW_BOOK)`。
9. 扫描完整完成后，统一写入新书、待处理项和 `LibraryRoot.lastScannedAt`。
10. 标记 `ScanSession(status = COMPLETED)`。

新目录重扫不做：

- 不重扫旧授权目录。
- 不把旧目录中缺失的文件标记为 `MISSING`。
- 不刷新旧目录已有书的结构。
- 不清空旧目录已有的 `PendingScanAction`。

<!-- 注释：统一说明完整重扫怎样处理已入库书的结构变化；BookFile 可用性统一交给 DetailScreen。 -->
## 7. 已入库书处理规则

用户主动全局重扫会按 `BookFile` 命中结果处理已入库书的来源结构变化。冷启动轻量级重扫不处理已入库书状态，新目录重扫只用全库 `BookFile` 做冲突判断。

| 情况                                | 处理                                      |
| ----------------------------------- | ----------------------------------------- |
| 完全命中已有 `BookFile`，结构未变化 | 刷新 `lastSeenScanId` / `lastScannedAt`   |
| 完整重扫中发现来源结构缺失或变化    | 生成 `PendingScanAction(UPDATE_EXISTING)` |
| manifest 内容或文件集合变化         | 生成 `PendingScanAction(UPDATE_EXISTING)` |
| 新来源想占用已有书文件              | 生成 `PendingScanAction(CONFLICT)`        |
| 旧书文件不可访问                    | 不在重扫中处理，由 `DetailScreen` 检查    |

`Book.status.CONFLICT` 第一版只作为保留状态。扫描导入期间发现的冲突进入 `PendingScanAction(CONFLICT)`，不直接把已有书改成 `CONFLICT`。

<!-- 注释：把已入库文件可用性检查放到 DetailScreen 按需触发，响应用户最新约束。 -->
## 8. DetailScreen 可用性检查

已入库 `BookFile` 是否仍可打开，由 `DetailScreen` 打开书籍详情时按需检查。

触发时机：

- 用户进入书籍详情页时完成

处理流程：

1. 读取当前 `Book` 和全部 `BookFile`。
2. 按 `BookFile.uri` 或 `documentId` 尝试轻量打开文件。
3. 能打开的文件更新为 `BookFile.status = READY`。
4. 不能打开的文件更新为 `BookFile.status = MISSING`。
5. 根据 `AUDIO` 文件状态重算 `Book.status`。
6. 如果全部必要文件可用，`Book.status = READY`。
7. 如果部分音频缺失，`Book.status = PARTIAL`。
8. 如果所有音频不可用，`Book.status = UNAVAILABLE`。

规则：

- `DetailScreen` 检查不创建 `ScanSession`。
- `DetailScreen` 检查不发现新书。
- `DetailScreen` 检查不生成 `PendingScanAction`。
- `DetailScreen` 检查不改 `Chapter`、`BookProgress`、`Bookmark`。
- 检查失败只影响当前书，不扩散到全库。

<!-- 注释：明确 PendingScanAction 的处理边界，冷启动轻扫只处理新文件相关待处理项。 -->
## 9. 待处理项刷新规则

重扫可以生成或刷新 `PendingScanAction`，但不同重扫类型的范围不同。

刷新规则：

- 冷启动轻量级重扫只生成或刷新新文件相关的 `PendingScanAction`。
- 冷启动轻量级重扫不全量重算旧待处理队列。
- 用户主动全局重扫按全库扫描结果生成或刷新 `PendingScanAction`。
- 新目录重扫按新增目录扫描结果生成或刷新 `PendingScanAction`。
- 相同 `actionKey` 的未处理项只刷新内容、`lastSeenScanId` 和展示摘要，不新增重复项。
- 本轮仍然发现的待处理项保留。
- 本轮没有发现但用户尚未处理的旧待处理项，第一版可以保留，不在扫描中自动删除。
- 用户处理成功后，在处理事务中删除对应 `PendingScanAction`。

<!-- 注释：说明缺失文件和软删除的边界，避免重扫误删用户数据。 -->
## 10. 缺失和删除规则

已入库 `BookFile` 的可用性只由 `DetailScreen` 按需检查，不由重扫流程统一检查。重扫和 `DetailScreen` 都不执行彻底删除。

规则：

- `DetailScreen` 发现已入库文件不可访问时，更新 `BookFile.status = MISSING`。
- `DetailScreen` 发现聚合书部分文件缺失时，更新 `Book.status = PARTIAL`。
- `DetailScreen` 发现所有音频文件不可访问时，更新 `Book.status = UNAVAILABLE`。
- `DetailScreen` 发现文件恢复可访问时，更新 `BookFile.status = READY`，并按整书状态恢复 `Book.status`。
- 重扫只在用户确认导入不完整新书后，写入新书的 `Book.status = PARTIAL` 和 `BookFile.status = MISSING`。
- 用户删除书籍仍然只标记 `Book.status = DELETED`。
- `DELETED` 书籍默认不参与新书导入结果展示，但其 `BookFile` 仍保留占用，防止冷启动轻扫或全局重扫立刻重复导入。

<!-- 注释：给出一段统一伪代码，便于后续实现时把三类重扫接到同一个执行器。 -->
## 11. 统一执行框架

```kotlin
suspend fun rescan(type: RescanType, scope: ScanScope) {
    val session = scanSessionDao.createRunningSession(
        trigger = when (type) {
            RescanType.COLD_START_LIGHT -> ScanTrigger.COLD_START
            RescanType.USER_GLOBAL -> ScanTrigger.USER
            RescanType.NEW_LIBRARY_ROOT -> ScanTrigger.ADD_LIBRARY_ROOT
        }
    )

    val result = runCatching {
        when {
            type == RescanType.COLD_START_LIGHT -> runColdStartNewFileScan(session, scope)
            scope.includeNewFileProcessing -> runImportStyleRescan(session, scope)
            else -> RescanResult.empty(session.id)
        }
    }

    result.onSuccess { rescanResult ->
        database.transaction {
            applyRescanResult(rescanResult)
            scanSessionDao.markCompleted(session.id)
        }
    }

    result.onFailure {
        scanSessionDao.markAbandoned(session.id)
    }
}
```

执行器拆分：

- `runColdStartNewFileScan`: 冷启动轻量级重扫使用，只处理未见过的新文件，直接导入无冲突且完整的新书，并生成新文件相关待处理项。
- `runImportStyleRescan`: 用户全局重扫和新目录重扫使用，内部复用 `cue -> m3u8 -> audio` 导入编排。
- `applyRescanResult`: 唯一允许写入新书、结构结果、待处理项和扫描完成状态的入口。

<!-- 注释：用简短结论收束三类重扫的差异，便于后续实现或评审时快速对照。 -->
## 12. 结论

三类重扫的边界如下：

```text
冷启动轻量级重扫:
  只处理未见过的新文件
  只导入完全由新文件组成、无冲突且完整的新书
  生成或刷新新文件相关待处理项
  不检查已入库 BookFile 可用性
  不全量刷新旧待处理队列

用户主动全局重扫:
  扫描全部授权目录
  发现新书
  刷新结构和待处理项

新目录重扫:
  只扫描新增授权目录
  发现新书
  和全库已有 BookFile 做冲突判断
```

重扫的核心原则是：冷启动保持轻，用户主动操作才做完整刷新，新目录只处理新授权范围；所有可创建新书的重扫都复用单文件序列导入方案。

已入库 `BookFile` 的可用性检查由 `DetailScreen` 按需触发，不作为冷启动轻量级重扫的一部分。
