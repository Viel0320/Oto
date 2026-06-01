# Audiobookshelf 接入：详细实施计划（org.json 版）

> 本文是 [`audiobookshelf_integration_plan.md`](./audiobookshelf_integration_plan.md)（可行性 + 初步方案）的**落地版拓展**，把混合方案细化到「可照着写代码」的粒度。
>
> **已锁定决策：不引入 `kotlinx.serialization`，ABS 客户端用 Android 内置 `org.json` 解析**（与全仓一致、零新依赖）。
>
> 本轮重点：**M0 脚手架 + M1 连接&浏览**（详细到文件/函数），M2 播放 / M3 进度同步给出可执行提纲。日期：2026-06-01。

---

## 0. 原则与边界

1. **纯增量、零回归**：不改 SAF / WebDAV 任何行为。ABS 只是新增一种 `sourceType`。
2. **复用现有链路**：VFS 流式播放、`CoverExtractor`、`PlaybackReachabilityManager`、Room 实体与 DAO 写入路径全部复用。
3. **org.json 解析模式**：定义轻量 `data class` + `companion fun from(json: JSONObject)`，用 `opt*` 取值并给默认值——天然忽略未知字段，对 ABS 版本演进健壮。
4. **分阶段可验收**：每阶段独立可测；M1 结束时书架能看到 ABS 书但可暂不可播。

---

## 1. 编译器强制的「改动清单」（加枚举值即触发）

只要给 `LibrarySourceKind` 加上 `AUDIOBOOKSHELF`，Kotlin 的穷尽性检查会在以下 **4 个 `when` 处直接报编译错误**，等于给我们一张不会遗漏的清单：

| # | 文件 | 位置 | 需补的分支 |
|---|---|---|---|
| 1 | `library/vfs/sourceProvider/LibrarySourceProvider.kt` | `LibrarySourceProviderFactory.providerFor` | `AUDIOBOOKSHELF -> absProvider` |
| 2 | `library/availability/AvailabilityChecker.kt` | `checkRoot` | `AUDIOBOOKSHELF -> checkAbsRoot(root)`（**必须 ping，不能走 VFS**，否则每次同步被标 `ERROR`） |
| 3 | `library/availability/AvailabilityChecker.kt` | `checkBookFiles`（批量） | `AUDIOBOOKSHELF -> ` 走逐文件 `checkBookFile` 循环（ABS 实际不会进批量路径，见下） |
| 4 | `data/service/LibraryRootService.kt` | `deleteLibraryRootDataOnly` | `AUDIOBOOKSHELF -> absCredentialStore.delete(root.credentialId)` |

另有 2 处**字符串判断**（不报错但需确认逻辑正确）：

- `data/store/LibraryRootStore.kt` `refreshPermissionStatuses`：非 SAF 失败→`ERROR`。只要 `checkAbsRoot` 在可达时返回 `AVAILABLE` 即可。
- `library/availability/PlaybackReachabilityManager.kt`：`isLocal = sourceType == SAF`、`allLocalFiles`。ABS 自动归为 remote 走宽限重试——**无需改动**。且因 ABS 走 `findNextAvailableRemoteAware`（逐文件），**不会进 `checkBookFiles` 批量路径**，故 #3 只是为满足编译穷尽性。

> 这条性质本身就是低风险的保证：忘了某处分支 = 编译不过。

---

## 2. org.json 解析模式（替代 kotlinx.serialization）

集中一个 `AbsJson.kt` 放工具扩展，model 各自带 `from(JSONObject)`：

```kotlin
// library/remote/abs/AbsJson.kt
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal inline fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
```

```kotlin
// library/remote/abs/AbsModels.kt  （只声明接入需要的字段，其余忽略）
data class AbsTrack(val index: Int, val startOffsetSec: Double, val durationSec: Double,
                    val title: String, val contentUrl: String, val mimeType: String?,
                    val ino: String?, val sizeBytes: Long, val filename: String) {
    companion object {
        fun from(o: JSONObject): AbsTrack {
            val meta = o.optJSONObject("metadata") ?: JSONObject()
            return AbsTrack(
                index = o.optInt("index", 1),
                startOffsetSec = o.optDouble("startOffset", 0.0),
                durationSec = o.optDouble("duration", 0.0),
                title = o.optString("title"),
                contentUrl = o.optString("contentUrl"),
                mimeType = o.optStringOrNull("mimeType"),
                ino = o.optStringOrNull("ino") ?: meta.optStringOrNull("ino"),
                sizeBytes = meta.optLong("size", 0L),
                filename = meta.optString("filename"),
            )
        }
    }
}
// 同理：AbsChapter(id,startSec,endSec,title)、AbsMediaProgress(currentTimeSec,durationSec,isFinished,lastUpdateMs)、
//      AbsLibrary(id,name,mediaType)、AbsLibraryItem(id, metadata*, coverPath, tracks[], chapters[], userProgress?)、AbsLoginResult(token)
```

> 这套模式零依赖、与 `SearchHistoryStore` 现有风格一致，且 `opt*` + 默认值天然对缺字段/新字段免疫。

---

## 3. M0 — 脚手架（无 UI，可单测）

### 3.1 常量 `data/db/AudiobookSchema.kt`
```kotlin
object LibrarySourceType { const val SAF=...; const val WEBDAV=...; const val AUDIOBOOKSHELF = "AUDIOBOOKSHELF" }
object SourceType { ...; const val ABS = "ABS" }        // BookEntity.sourceType 用
object ChapterSource { ...; const val ABS = "ABS" }      // 可选
```

### 3.2 `LibrarySourceKind` + 工厂（`LibrarySourceProvider.kt`）
- `enum` 增 `AUDIOBOOKSHELF(AudiobookSchema.LibrarySourceType.AUDIOBOOKSHELF)`。
- `LibrarySourceProviderFactory` 增 `private val absProvider = AbsSourceProvider(context.applicationContext)`，`providerFor` 加分支（#1）。

### 3.3 `AbsCredentialStore`（仿 `WebDavCredentialStore`）
- 路径：`library/vfs/sourceProvider/audiobookshelf/AbsCredentialStore.kt`。
- 按 `credentialId` 存 `token`（+ 可选 `serverUrl`/`username` 便于显示与重登）。结构、增删改与 WebDAV 版一致。

### 3.4 `AbsApiClient`（OkHttp + org.json）
- 路径：`library/remote/abs/AbsApiClient.kt`。复用 OkHttp（已有依赖）。
- 鉴权：`applyAuth { header("Authorization", "Bearer $token") }`（仿 WebDAV `applyAuth`）。
- 错误映射：仿 `WebDavException` 建 `AbsException(availabilityStatus, ...)`，把 401→`AUTH_FAILED`、超时→`TIMEOUT`、5xx→`SERVER_ERROR` 等映射到 `AudiobookSchema.AvailabilityStatus`，并在 `AvailabilityChecker.toAvailabilityResult` 一并识别（与现有 `WebDavException` 同位处理）。
- 线程：所有方法 `withContext(Dispatchers.IO)`。

| 方法 | 端点 |
|---|---|
| `suspend fun ping(baseUrl): Boolean` | `GET /ping` |
| `suspend fun login(baseUrl, user, pass): AbsLoginResult` | `POST /login` |
| `suspend fun getLibraries(root): List<AbsLibrary>` | `GET /api/libraries` |
| `suspend fun getItemsPage(root, limit, page): AbsItemsPage` | `GET /api/libraries/{libId}/items?minified=1&limit&page` |
| `suspend fun getItem(root, itemId): AbsLibraryItem` | `GET /api/items/{id}?expanded=1&include=progress` |
| `suspend fun getCoverBytes(root, itemId): ByteArray?` | `GET /api/items/{id}/cover` |
| `suspend fun getProgress(root, itemId): AbsMediaProgress?` | `GET /api/me/progress/{id}` |
| `suspend fun patchProgress(root, itemId, currentTimeSec, durationSec, isFinished): Boolean` | `PATCH /api/me/progress/{id}` |

### 3.5 `AbsSourceProvider`（M0 先占位，M2 填实现）
- 路径：`library/vfs/sourceProvider/audiobookshelf/AbsSourceProvider.kt`，`implements LibrarySourceProvider`。
- M0：`rootDirectory`/`resolve`/`listChildren` 先返回 `null`/`emptyList`（让 `SourceInventoryScanner` 天然跳过 ABS root）；`exists` 返回 `true`；流方法抛 `NotImplementedError`（M2 实现）。

### 3.6 `AvailabilityChecker` 分支（#2/#3）
- `checkRoot`：`AUDIOBOOKSHELF -> checkAbsRoot(root)`，内部 `absApiClient.ping(root.sourceUri)` 成功→`AVAILABLE`，认证失败→`AUTH_FAILED`，网络失败→对应状态。
- `checkBookFiles`：`AUDIOBOOKSHELF ->` 对 `rootFiles` 逐个调 `checkBookFile`（不依赖 `listChildren`）。

**M0 验收**：加 `androidx test` 的 `MockWebServer`（OkHttp 自带 `mockwebserver`，仅 test 依赖），喂样例 JSON（直接取自 `docs/cc/Audiobookshelfdocs/_items.md` 的示例）断言 `AbsLibraryItem.from(...)` 各字段、章节秒→毫秒换算正确。`login`/`ping` 走 MockWebServer。

---

## 4. M1 — 连接 + 浏览（书架可见，暂不可播）

### 4.1 root 持久化 `data/store/LibraryRootStore.kt`
新增（仿 `addWebDavRoot`）：
```kotlin
suspend fun addAudiobookshelfRoot(
    serverUrl: String, libraryId: String, libraryName: String, token: String, username: String
): LibraryRootEntity = withContext(Dispatchers.IO) {
    val origin = normalizeOrigin(serverUrl)                 // 只留 scheme://authority
    val credential = absCredentialStore.save(token, serverUrl = origin, username = username)
    // 去重：同 origin + 同 libraryId 视为同一 root
    ... 命中则更新 credential/displayName；否则插入
    LibraryRootEntity(
        id = UUID.randomUUID().toString(),
        sourceType = AudiobookSchema.LibrarySourceType.AUDIOBOOKSHELF,
        sourceUri = origin, basePath = libraryId,
        credentialId = credential.id, displayName = libraryName, ...
    ).also { rootDao.insertRoot(it) }
}
```

### 4.2 网关 `LibraryRootService` / `LibraryRootGateway`
- 加 `addAudiobookshelfLibraryRoot(...)` 与 `addAudiobookshelfLibraryRootAndScheduleSync(...)`（仿 WebDAV 两个方法，后者 `addRoot` 后 `scanScheduler.syncLibrary(trigger)`）。
- `deleteLibraryRootDataOnly` 的 `when` 加 ABS 分支清 token（#4）。

### 4.3 扫描分流 `data/service/ScanService.kt`
在 `runSyncLibrary` 里、`ScanSessionRunner.rescan` 之外，并行处理 ABS root：
```kotlin
private suspend fun runSyncLibrary(trigger: String) = withContext(Dispatchers.IO) {
    rootStore.refreshPermissionStatuses()
    // 既有字节扫描：ABS root 因 AbsSourceProvider.rootDirectory 返回 null 被自动跳过
    val type = if (trigger == ScanTrigger.COLD_START) COLD_START_LIGHT else USER_GLOBAL
    ScanSessionRunner(appContext, vfsFileInterface, coverRecoveryHelper::checkAndTriggerCoverRegeneration).rescan(type)
    // 新增：ABS 语义化导入
    val absRoots = libraryRootDao.getAllRootsOnce()
        .filter { it.sourceType == AudiobookSchema.LibrarySourceType.AUDIOBOOKSHELF && it.status == ACTIVE }
    absRoots.forEach { root -> absLibraryImporter.import(root) }
}
```
> `ScanService` 需新注入 `AbsLibraryImporter`（在 `AppContainer` 装配）。

### 4.4 `AbsLibraryImporter`（REST → 实体）
- 路径：`library/orchestrator/abs/AbsLibraryImporter.kt`。
- 流程 `suspend fun import(root)`：
  1. 分页 `getItemsPage(root, limit=100, page=k)` 直到 `page*limit >= total`，仅取 `mediaType=="book"`。
  2. 对每个 item（新增或 `updatedAt` 变化）`getItem(root, id)` 取 `tracks/chapters/metadata`。
  3. 映射并 **upsert**（复用导入流水线写库所用的 `BookDao` upsert 方法，见 §6 准备项）：`BookEntity` + 每个 track 一条 `BookFileEntity` + 章节。
  4. 封面：`getCoverBytes(root,id)` → `coverExtractor.saveEmbeddedImage("abs_$itemId", bytes)` → `bookDao.updateCoverPaths(id, originalPath, thumbnailPath, backgroundColor, now)`。
  5. 增量：以稳定主键（`abs_$itemId` / `${itemId}_${ino}`）保证幂等；记录 `lastScannedAt`/对比 `updatedAt`。
  6. 删除协调（v1 可后置）：服务端已无的 item → 标记 `UNAVAILABLE` 或删除。
- **实体映射**见初步方案 §5 的映射表。
- **章节映射**（全局秒 → 文件内偏移 + bookFileId）：
```kotlin
val sortedTracks = item.tracks.sortedBy { it.index }
item.chapters.mapIndexed { i, ch ->
    val track = sortedTracks.lastOrNull { it.startOffsetSec <= ch.startSec } ?: sortedTracks.first()
    val file = bookFiles.first { it.index == track.index }
    ChapterEntity(
        id = "${item.id}_ch_${ch.id}", bookId = bookId, bookFileId = file.id, index = i,
        title = ch.title.ifBlank { "Chapter ${i+1}" },
        startPositionMs = (ch.startSec * 1000).toLong(),
        durationMs = ((ch.endSec - ch.startSec) * 1000).toLong(),
        fileOffsetMs = ((ch.startSec - track.startOffsetSec) * 1000).toLong(),
        source = AudiobookSchema.ChapterSource.ABS,
    )
}
```
- `BookFileEntity.sourcePath = track.contentUrl`、`sourceIdentity = track.ino`、`durationMs = (track.durationSec*1000)`、`fileSize = track.sizeBytes`、`displayName = track.filename`、`index = track.index`。（M1 就写好，供 M2 直接流式播放。）

### 4.5 连接 UI
- `ui/settings/AbsServerDialog.kt`（仿 `WebDavRootDialog`，两步）：
  1. 输入 `服务器 URL / 用户名 / 密码` → 「测试并登录」→ `AbsApiClient.ping` + `login` → 拿 token；
  2. `getLibraries` 列出库 → 多选 → 「添加」。
- `SettingsViewModel`：`fun onAudiobookshelfSubmitted(serverUrl, username, password, selectedLibraries)` → 对每个选中库 `addAudiobookshelfLibraryRootAndScheduleSync(...)`。
- `SettingsScreen`：在 WebDAV 入口旁加「连接 Audiobookshelf 服务器」按钮 + 列表项图标区分（root 已有 `displayName`/`availabilityStatus` 可直接复用渲染）。
- 登录态：token 失败提示重登；明文 `http://` 复用既有 cleartext 开关。

### 4.6 `AppContainer` 装配
- 新增惰性单例：`absApiClient`、`absLibraryImporter`（注入 `absApiClient` + `database.bookDao()`/`chapterDao()` + `coverExtractor`），并把 `absLibraryImporter` 注入 `ScanService`。

**M1 验收**：设置里连服务器 → 选库 → 书架出现 ABS 书；封面、标题/作者/讲述人、章节数正确；详情页正常。重复同步幂等（不重复建书）。断网/错 token 有明确提示、不崩溃、不误删本地 SAF 书。

---

## 5. M2 / M3 提纲（本轮先不写码）

### M2 播放
- 实现 `AbsSourceProvider.openInputStream(file, offset)` / `readRange` / `exists`：`GET {root.sourceUri}{file.sourcePath}` + `Authorization: Bearer` + `Range: bytes=offset-`，逻辑≈复制 `WebDavSourceProvider` 对应方法；`openFileDescriptor` 返 `null`。
- 因 `BookFileEntity.sourcePath` 已在 M1 写入 `contentUrl`，播放链路（`VfsPlaybackDataSource`→`VfsFileInterface.open`）**零改动**即可流式播放、seek、切章、后台、通知。
- **开始前需实测**：`/s/item/...` 是否接受 `Authorization: Bearer` header；若否，改为 URL 追加 `?token=`。

### M3 进度同步
- `media/abs/AbsProgressSyncer.kt`：
  - **拉**：打开书时（`PlayerViewModel` 载入 / `PlaybackManager.applyPlaybackPlan` 前）`getProgress(itemId)` → `currentTimeSec*1000` 作为起播位置，与本地 `BookProgressEntity` 按时间戳 **LWW** 合并。
  - **推**：`ProgressService.saveProgress`/`updateProgress` 落库后，若该 book 的 root 为 ABS（`bookDao` 查 rootId→sourceType），防抖后 `patchProgress`。
- 离线 outbox：`PendingProgressSyncEntity` + DAO + WorkManager（联网 flush，`PATCH /api/me/progress/batch/update` 批量）；需 `AppDatabase` version+1 + migration。
- `ProgressService` 新增可选注入 `absProgressSyncer`，在 `AppContainer` 装配。

---

## 6. 改动点矩阵

| 文件 | 类型 | 阶段 |
|---|---|---|
| `data/db/AudiobookSchema.kt` | 改：加常量 | M0 |
| `library/vfs/sourceProvider/LibrarySourceProvider.kt` | 改：枚举 + 工厂分支 | M0 |
| `library/vfs/sourceProvider/audiobookshelf/AbsSourceProvider.kt` | 新（占位→M2 实现） | M0/M2 |
| `library/vfs/sourceProvider/audiobookshelf/AbsCredentialStore.kt` | 新 | M0 |
| `library/remote/abs/AbsApiClient.kt` | 新 | M0 |
| `library/remote/abs/AbsModels.kt` / `AbsJson.kt` | 新 | M0 |
| `library/availability/AvailabilityChecker.kt` | 改：2 个 when 分支 | M0 |
| `data/store/LibraryRootStore.kt` | 改：`addAudiobookshelfRoot` | M1 |
| `data/service/LibraryRootService.kt` | 改：网关方法 + 删除分支 | M1 |
| `data/service/ScanService.kt` | 改：ABS 分流 + 注入 importer | M1 |
| `library/orchestrator/abs/AbsLibraryImporter.kt` | 新 | M1 |
| `ui/settings/AbsServerDialog.kt` | 新 | M1 |
| `ui/settings/SettingsViewModel.kt` / `SettingsScreen.kt` / `SettingsActivity.kt` | 改：入口 + 回调 | M1 |
| `AppContainer.kt` | 改：装配 client/importer | M0/M1 |
| `media/abs/AbsProgressSyncer.kt` (+ outbox 实体/DAO) | 新 | M3 |
| `data/service/ProgressService.kt` | 改：进度钩子 | M3 |
| `AppDatabase.kt` | 改：outbox 表 + migration | M3 |

---

## 7. 开始写码前需你拍板的开放点

1. **`/s/` 流式鉴权**：用你的 ABS 实例实测 Bearer header 是否被接受（影响 M2 实现细节）。
2. **最低 ABS 版本**：建议声明 ≥ 2.2，连接时读 `serverSettings.version` 做能力判断。
3. **v1 范围**：是否只支持 `book`（跳过 podcast）、只支持在线流式（离线下载列入 M5）。建议是。
4. **`BookEntity.sourceType`**：用新常量 `"ABS"`（推荐，语义清晰）还是复用 `GENERATED_M3U8`。
5. **进度机制**：v1 用 `/api/me/progress`（简单、够用）还是 session API（带收听时长统计）。建议先用前者。
6. **MockWebServer 测试依赖**：是否同意为 M0 单测加 `com.squareup.okhttp3:mockwebserver`（仅 `testImplementation`）。

---

## 8. 建议的提交顺序（每步可独立编译/审阅）

1. **PR-1（M0-a）**：常量 + `LibrarySourceKind`/工厂 + `AbsSourceProvider` 占位 + `AvailabilityChecker`/`LibraryRootService` 的 when 分支 → 编译通过、行为零变化。
2. **PR-2（M0-b）**：`AbsCredentialStore` + `AbsApiClient` + models + 单测。
3. **PR-3（M1-a）**：`LibraryRootStore`/网关的 ABS 建 root + `ScanService` 分流 + `AbsLibraryImporter`（不含 UI，可用临时入口/instrumented test 验证导入）。
4. **PR-4（M1-b）**：连接 UI 串起整条「连服务器→选库→书架可见」。
5. （后续）**PR-5 = M2 播放**，**PR-6 = M3 进度**。

---

### 关联文档
- 可行性 + 架构总览：[`audiobookshelf_integration_plan.md`](./audiobookshelf_integration_plan.md)
- ABS API 原始文档：`docs/cc/Audiobookshelfdocs/`
