# Audiobookshelf 在线后端接入：可行性分析与初步方案

> 文档目的：评估在 **保持当前架构不变** 的前提下，把 [Audiobookshelf](https://www.audiobookshelf.org/)（以下简称 **ABS**）服务器接入为 **可选的在线后端**，并在可行时给出一份可扩展的初步实施方案。
>
> 参考资料：本仓库 `docs/cc/Audiobookshelfdocs/`（ABS 官方 API 文档本地副本）。
>
> 状态：初步设计稿（待评审 / 待细化）。日期：2026-06-01。

---

## 0. 结论摘要（TL;DR）

**结论：可行，置信度高。** 接入 ABS 不需要改动核心架构，而是 **沿用现有为"远程源"预留的扩展点**，新增三个互相解耦的组件即可。

判断依据（均已逐文件核对源码）：

1. **源是可插拔的。** `LibrarySourceProvider` 接口 + `LibrarySourceKind` 枚举 + `LibrarySourceProviderFactory.providerFor(root)`（按 `root.sourceType` 分发）就是为"再加一种来源"设计的。代码注释里反复写着"以后新增 provider 时…"。
2. **WebDAV 已经端到端跑通。** 从 Settings 对话框 → `addWebDavLibraryRootAndScheduleSync` → `LibraryRootStore.addWebDavRoot` → 扫描 → **Range 流式播放**，整条远程链路是活的。ABS 是同一条路的自然延伸。
3. **播放完全与来源无关。** Media3 队列里的 URI 是内部 `aplayer-vfs://book-file/{id}`，`VfsPlaybackDataSource` 拿到 `BookFileEntity` 后通过 provider 按 offset 流式读取。**播放器、章节、书签、字幕、UI 几乎零改动。**
4. **`LibraryRootEntity` 字段天然适配服务器配置**（`sourceUri`=服务器地址、`basePath`=库 ID、`credentialId`=token 引用）。

**唯一的关键差异**：ABS 不是"哑文件系统"，而是 **语义化 REST API**——服务端已经完成了扫描、分组、元数据、章节的提取。因此 **不应** 把 ABS 强行塞进现有"按字节扫描 + 启发式分组"的导入流水线，而应采用 **混合方案**：

| 新增组件 | 职责 | 复杂度 | 复用 |
|---|---|---|---|
| `AbsSourceProvider` | 仅负责 **字节流式播放 / 封面下载**（实现 `LibrarySourceProvider`） | 低（≈ 复制 WebDAV provider） | VFS、播放链路 |
| `AbsLibraryImporter` | 调 REST API **直接写实体**，绕过字节解析与启发式分组 | 中 | Room 实体、`PositionMapper` |
| `AbsProgressSyncer` | 进度 **双向同步** + 离线 outbox | 中 | `ProgressGateway`、WorkManager |

> 整个改动是 **纯增量** 的：本地 SAF / WebDAV 书库继续工作，ABS 只是多了一种 root 类型，无数据迁移、无破坏性改动。

---

## 1. 当前架构关键发现（接入点在哪里）

| 子系统 | 关键文件 | 对接入的意义 |
|---|---|---|
| 源抽象 | `library/vfs/sourceProvider/LibrarySourceProvider.kt` | `interface LibrarySourceProvider` + `enum LibrarySourceKind { SAF, WEBDAV }` + `class LibrarySourceProviderFactory`。**新增 `AUDIOBOOKSHELF` 分支即可。** |
| 源类型常量 | `data/db/AudiobookSchema.kt` | `object LibrarySourceType { SAF, WEBDAV }`、`object AvailabilityStatus { ... }`。已为远程预留 `AUTH_FAILED / TIMEOUT / NETWORK_UNAVAILABLE` 等状态。 |
| 远程源样板 | `library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt` | OkHttp + `Range` + `applyAuth` + 错误→`AvailabilityStatus` 映射。**`AbsSourceProvider` 的播放/封面部分几乎是它的翻版。** |
| 凭据存储 | `…/webdav/WebDavCredentialStore.kt` | 按 `credentialId` 存账密（Base64，注释说后续上 Keystore）。**ABS 存 token 同样套路。** |
| Root 持久化 | `data/entity/LibraryRootEntity.kt`、`data/store/LibraryRootStore.kt` | `sourceType / sourceUri / basePath / credentialId / displayName + availability*`。`addWebDavRoot(...)` 是 `addAudiobookshelfRoot(...)` 的模板。 |
| 播放数据源 | `media/VfsPlaybackUri.kt`、`media/VfsPlaybackDataSource.kt`、`media/PlaybackPlanBuilder.kt` | URI=`aplayer-vfs://book-file/{fileId}`；`open(file, offset)` 走 provider。**源无关，无需改。** |
| VFS 派发 | `library/vfs/VfsFileInterface.kt`、`VirtualFileSystem.kt` | `directRangeNode()` **仅用 `BookFileEntity` 的 `sourcePath / sourceIdentity / etag / fileSize` 构造 `SourceNode`**，provider 负责把 `sourcePath` 还原成真实地址。→ ABS 只要在 `sourcePath` 里编码 track 的 `contentUrl` 即可流式播放。 |
| 扫描调度 | `library/sync/LibrarySyncWorker.kt` → `data/service/ScanService.kt` → `library/SourceInventoryScanner.kt` | `scheduleLibrarySync(trigger)` 收集所有 root 后统一走 `SourceInventoryScanner.scan(roots)`。**ABS 导入在此按 `sourceType` 分支。** |
| 进度 | `media/ProgressSyncTracker.kt` → `data/gateway/ProgressGateway.kt`（`ProgressService`） | 进度 **只写本地 Room**（`BookProgressEntity`），**没有远程同步钩子**。→ ABS 进度同步是净新增。 |
| 可达性 | `library/availability/PlaybackReachabilityManager.kt` | 已区分 local/remote：`isLocal = sourceType == SAF`，非本地走带网络宽限的重试。**ABS 自动归类为 remote。** |
| 封面 | `data/service/CoverService.kt`、`media/parser/CoverExtractor.kt` | 封面是 **本地文件**（`coverPath / thumbnailPath / backgroundColorArgb`），由字节提取 + palette 计算。→ ABS 封面**下载到同一缓存**即可复用。 |
| JSON | `data/store/SearchHistoryStore.kt` 等 | 全仓使用 Android 内置 `org.json`，**无 Gson/Moshi/kotlinx.serialization 依赖**。 |
| 网络明文开关 | `SettingsViewModel.toggleCleartextTrafficAllowed` | 已有"允许 HTTP 明文"设置，局域网 `http://` 的 ABS 可直接复用。 |

---

## 2. ABS API 摘要（接入相关端点）

鉴权：`POST /login` 返回 `user.token`（JWT），后续请求带 `Authorization: Bearer <token>`。

| 用途 | 方法 / 路径 | 关键字段 |
|---|---|---|
| 连接探活 | `GET /ping` → `{success:true}`；`GET /status` → `{isInit, language}` | 用于"测试连接" |
| 登录 | `POST /login` body `{username, password}` | → `user.token`、`user.mediaProgress[]`、`userDefaultLibraryId` |
| 持久化授权 | `GET /api/authorize`（带已存 token） | 重新拉取 user/server 信息 |
| 库列表 | `GET /api/libraries` | `libraries[].{id, name, mediaType}` |
| 库内条目 | `GET /api/libraries/{id}/items?limit&page&sort&minified&collapseseries` | `results[].{id, ino, media.metadata.{title,authorName,narratorName}, media.{coverPath,numTracks,duration}}`、`total/limit/page` |
| **条目详情** | `GET /api/items/{id}?expanded=1&include=progress,authors` | `media.metadata.{title,subtitle,authors[],narrators[],series[],publishedYear,publisher,description,asin,isbn}`、`media.coverPath`、`media.audioFiles[]`、`media.chapters[]`、`media.duration`、**`media.tracks[]`**、`userMediaProgress` |
| **音轨（播放）** | `media.tracks[]` 内 | `index`、`startOffset`(秒,全局)、`duration`(秒)、`title`、**`contentUrl`**=`/s/item/{id}/{filename}`、`mimeType`、`metadata.{filename,ext,size}` |
| 章节 | `media.chapters[]` 内 | `{id, start(秒), end(秒), title}`（**全局**时间轴） |
| 封面 | `GET /api/items/{id}/cover?width=400&format=webp&raw=0` | 返回图片字节（带 `Authorization: Bearer`） |
| **进度读** | `GET /api/me/progress/{libraryItemId}` | `{currentTime(秒), progress(0..1), isFinished, duration(秒), lastUpdate(ms)}` |
| **进度写** | `PATCH /api/me/progress/{libraryItemId}` body `{currentTime, duration, progress, isFinished}` | 200 OK |
| 进度批量写 | `PATCH /api/me/progress/batch/update`（数组） | 离线 outbox 批量回传可用 |
| 搜索 | `GET /api/libraries/{id}/search?q=` | 服务端搜索（可选，v1 可先用本地搜索） |
| （可选）会话播放 | `POST /api/items/{id}/play[/{episodeId}]` → `audioTracks[]`、`id`(sessionId)；`POST /api/session/{id}/sync` `{currentTime,timeListened,duration}`；`POST /api/session/{id}/close` | 提供"收听时长"统计；v1 用 `/api/me/progress` 已足够 |

**流式 URL 构造（关键）**：完整地址 = `{sourceUri}{contentUrl}`，例如 `https://abs.example.com/s/item/li_xxx/Track 01.mp3`。
鉴权方式：我们的 provider 用 OkHttp 自己发起请求，**优先在 header 带 `Authorization: Bearer`**（与 WebDAV provider 的 `applyAuth` 完全一致）；ABS `/s/` 静态路由同时支持 `?token=<jwt>` 查询参数作为兜底。`/s/` 与 `/api/items/{id}/cover` 均支持 HTTP **Range** 请求，满足 seek。

---

## 3. 关键差异与挑战

1. **文件系统 vs 语义 API（最重要）。** ABS 已在服务端完成扫描/分组/元数据/章节。若把它伪装成文件系统走 `SourceInventoryScanner` + `ImportPipeline`，会：① 为解析嵌入式标签而**通过网络下载字节**（极慢）；② 用本地启发式分组**覆盖** ABS 已有的正确分组；③ **丢弃** ABS 的结构化章节与元数据。→ 必须用专门的 REST 导入路径（见 §4.2）。
2. **进度模型不一致。** ABS 是"每条目一个 `currentTime`（秒）"；本地是 `globalPositionMs` + 文件锚点（`bookFileId / currentFileIndex / positionInFileMs`）。需双向换算并做冲突解决（建议按 `lastUpdate` vs `lastPlayedAt` 时间戳 **last-write-wins**）。
3. **封面来自 URL。** 现有模型存本地文件并计算背景主色。→ 导入时 `GET /cover` 下载字节，复用 `CoverExtractor` 的缩略图 + palette 流程落地为本地 `coverPath`。
4. **离线。** 流式播放强依赖网络。`PlaybackReachabilityManager` 已对远程源做宽限重试与 `MISSING` 降级。**v1 定位为"在线流式播放"**；离线下载（`/api/items/{id}/download` 或逐轨缓存）列为后续增强。
5. **章节 → 文件内偏移。** ABS 章节是全局秒级时间轴，本地 `ChapterEntity` 需要 `bookFileId` + `fileOffsetMs`。用 `tracks[].startOffset` 把全局章节起点定位到所属分轨，再算文件内偏移；该换算与现有 `PositionMapper.globalToFilePosition` 同构，可直接复用思路。
6. **鉴权与传输安全。** token 落 `AbsCredentialStore`；局域网 `http://` 复用已有"明文开关"；自签名 TLS 可参照 WebDAV 凭据里已有的 `allowInsecureTls` 先例。
7. **ID 稳定性。** 用 ABS 的 `libraryItemId` 派生稳定主键（如 `BookEntity.id = "abs_" + itemId`），`BookFileEntity.sourceIdentity = ino`，保证重扫/增量同步幂等。
8. **JSON。** 与全仓一致用 `org.json`（零新依赖）即可；若偏好类型安全，可选引入 `kotlinx.serialization`（见 §10）。

---

## 4. 推荐方案：混合架构

```
                        ┌─────────────────────────────────────────────┐
  Settings UI ──────────► AbsServerConnect (login → 选 library → 建 root)│
  "连接 Audiobookshelf"   └───────────────┬─────────────────────────────┘
                                          │ LibraryRootEntity(sourceType=AUDIOBOOKSHELF,
                                          │   sourceUri=server, basePath=libraryId, credentialId=token)
                                          ▼
   ScanService.scheduleLibrarySync ──按 sourceType 分支──┐
        │                                                │
        │ SAF/WEBDAV                                      │ AUDIOBOOKSHELF
        ▼                                                ▼
   SourceInventoryScanner + ImportPipeline        AbsLibraryImporter ──► AbsApiClient (OkHttp+org.json)
   (按字节扫描/启发式分组，原样不动)                   │  GET /libraries/{id}/items (分页)
                                                    │  GET /items/{id}?expanded=1
                                                    │  GET /items/{id}/cover
                                                    ▼
                                           写 Book/BookFile/Chapter + 落地封面
                                                    │
   ┌────────────────────────────────────────────────┘
   ▼ 播放（完全复用现有链路，零改动）
   PlayerVM → PlaybackManager → Media3(aplayer-vfs://book-file/{id})
        → VfsPlaybackDataSource → VfsFileInterface → AbsSourceProvider.openInputStream(file, offset)
                                                       (OkHttp GET {server}{contentUrl} + Bearer + Range)
        ▲
   进度 ProgressSyncTracker → ProgressGateway ──(ABS root)──► AbsProgressSyncer
        - 打开书：GET /api/me/progress/{itemId} → 设定起播位置（与本地 LWW 合并）
        - 保存进度：PATCH /api/me/progress/{itemId}（防抖 + WorkManager 离线 outbox 补偿）
```

### 4.1 `AbsSourceProvider`（流式 + 封面字节）

- 实现 `LibrarySourceProvider`，`kind = AUDIOBOOKSHELF`，`capabilities.supportsRangeRead = true`。
- **真实实现**：`openInputStream(file, offset)`、`readRange(file, offset, length)`、`exists(node)`——逻辑几乎复制 `WebDavSourceProvider`，把 `urlFor()` 换成 `{root.sourceUri}{sourcePath}`，把 `applyAuth` 换成 `Authorization: Bearer {token}`。
- **空实现/不支持**：`rootDirectory / resolve / listChildren`（ABS 不走 `SourceInventoryScanner`，这些是死路径，可返回空或 `UnsupportedOperation`）；`openFileDescriptor` 返回 `null`（同 WebDAV）。
- `file.sourcePath` 约定为 track 的相对 `contentUrl`（如 `/s/item/li_xxx/Track 01.mp3`）。

### 4.2 `AbsLibraryImporter`（REST → 实体，绕过字节扫描）

- 入口：在 `ScanService.scheduleLibrarySync` 内，先按 `root.sourceType` 把 roots 分成两组；ABS 组交给 `AbsLibraryImporter.import(root)`，其余组维持现状。
- 流程：
  1. `GET /api/libraries/{basePath}/items?limit=N&page=k` 分页拉全部条目（仅 `mediaType == book`，v1 跳过 podcast）。
  2. 对（新增/`updatedAt` 变化的）条目 `GET /api/items/{id}?expanded=1&include=progress`。
  3. 写 `BookEntity` / `BookFileEntity[]`（每个 `tracks[]` 一行）/ `ChapterEntity[]`（见 §5 映射表）。
  4. `GET /api/items/{id}/cover` 下载字节 → 复用 `CoverExtractor` 落地 `coverPath/thumbnailPath/backgroundColorArgb`。
- 幂等：以 `libraryItemId`/`ino` 派生稳定主键；增量同步对比 `updatedAt`。

### 4.3 `AbsProgressSyncer`（双向同步 + 离线 outbox）

- **拉**（打开书时）：`GET /api/me/progress/{itemId}` → `currentTime*1000` 作为起播 `globalPositionMs`，与本地 `BookProgressEntity` 按时间戳 LWW 合并。
- **推**（`ProgressSyncTracker` 落库后）：防抖后 `PATCH /api/me/progress/{itemId}`；离线时写入一张 outbox 表（参照已有 `PendingScanActionEntity` 模式），联网后由 WorkManager 用 `PATCH …/batch/update` 批量回传。
- 接入点：`ProgressService`（实现 `ProgressGateway`）在 `saveProgress` 后判断该 book 的 root 是否为 ABS，是则委托 `AbsProgressSyncer`。

### 4.4 凭据 / 连接 UI

- `AbsCredentialStore`（或把 `WebDavCredentialStore` 泛化为 `RemoteCredentialStore`）按 `credentialId` 存 JWT（+ 用户名用于显示/重登）。
- Settings 新增"连接 Audiobookshelf 服务器"对话框：输入 `URL / 用户名 / 密码` → `POST /login` → 列出可访问 library → 用户勾选要添加的库 → 每个库建一条 `LibraryRootEntity(sourceType=AUDIOBOOKSHELF)`。复用 `WebDavRootDialog` 的交互骨架。

---

## 5. 数据映射表（ABS → 本地实体）

**`LibraryRootEntity`**（1 个 ABS library = 1 条 root）

| 本地字段 | 来源 |
|---|---|
| `sourceType` | `"AUDIOBOOKSHELF"`（新增常量） |
| `sourceUri` | 服务器 origin，如 `https://abs.example.com` |
| `basePath` | ABS `libraryId` |
| `credentialId` | 指向 `AbsCredentialStore` 中的 token |
| `displayName` | ABS library `name` |

**`BookEntity`**（每个 ABS item 一本）

| 本地字段 | 来源 |
|---|---|
| `id` | `"abs_" + libraryItemId`（稳定） |
| `rootId` | 所属 ABS root |
| `sourceType` | 新增 `SourceType.ABS`（标识"远程多轨"，区别于 `SINGLE_AUDIO/M3U8`） |
| `title/author/narrator/description/year` | `media.metadata.{title, authorName, narratorName, description, publishedYear}` |
| `totalDurationMs` | `media.duration * 1000` |
| `coverPath/thumbnailPath/backgroundColorArgb` | `GET /cover` 下载后由 `CoverExtractor` 生成 |

**`BookFileEntity`**（每个 `media.tracks[]` 一行）

| 本地字段 | 来源 |
|---|---|
| `id` | `libraryItemId + "_" + ino`（稳定） |
| `bookId` | 对应 `BookEntity.id` |
| `rootId` | 所属 ABS root |
| `index` | `track.index` |
| `sourcePath` | `track.contentUrl`（供 `AbsSourceProvider` 还原流式 URL） |
| `sourceIdentity` | `ino`（稳定身份，增量检测用） |
| `durationMs` | `track.duration * 1000` |
| `fileSize` | `track.metadata.size` |
| `displayName` | `track.metadata.filename` |
| `fileRole` | `AUDIO` |

**`ChapterEntity`**（每个 `media.chapters[]` 一条）

| 本地字段 | 来源 |
|---|---|
| `id` | `libraryItemId + "_ch_" + chapter.id` |
| `bookId` | 对应 `BookEntity.id` |
| `bookFileId` | 由 `chapter.start` 落在哪个 track（`startOffset <= start < startOffset+duration`）决定 |
| `startPositionMs` | `chapter.start * 1000`（全局） |
| `durationMs` | `(chapter.end - chapter.start) * 1000` |
| `fileOffsetMs` | `(chapter.start - track.startOffset) * 1000` |
| `source` | `EMBEDDED`（或新增 `ChapterSource.ABS`） |

**`BookProgressEntity` ↔ ABS progress**

| 本地 | ABS |
|---|---|
| `globalPositionMs` | `currentTime * 1000` |
| `totalDurationMs`（来自 book） | `duration` |
| `readStatus = FINISHED` | `isFinished == true` |
| `lastPlayedAt` | `lastUpdate` |

---

## 6. 改动点清单

**新增（约 7 个文件）**
- `library/vfs/sourceProvider/audiobookshelf/AbsSourceProvider.kt`
- `library/vfs/sourceProvider/audiobookshelf/AbsCredentialStore.kt`
- `library/remote/abs/AbsApiClient.kt`（OkHttp + `org.json` 封装：login/ping/libraries/items/item/cover/progress）
- `library/remote/abs/AbsModels.kt`（轻量数据类）
- `library/orchestrator/abs/AbsLibraryImporter.kt`
- `media/abs/AbsProgressSyncer.kt`（+ 可选 `data/entity/PendingProgressSyncEntity.kt` 及其 DAO）
- `ui/settings/AbsServerDialog.kt`（连接对话框）

**修改（约 6 处，均为加分支/钩子，不动主逻辑）**
- `data/db/AudiobookSchema.kt`：加 `LibrarySourceType.AUDIOBOOKSHELF`、`SourceType.ABS`、（可选）`ChapterSource.ABS`。
- `library/vfs/sourceProvider/LibrarySourceProvider.kt`：`LibrarySourceKind` 加枚举值、`LibrarySourceProviderFactory.providerFor` 加分支。
- `data/service/ScanService.kt`：`scheduleLibrarySync` 内按 `sourceType` 分流到 `AbsLibraryImporter`。
- `data/store/LibraryRootStore.kt` + `LibraryRootService.kt`：加 `addAudiobookshelfRoot(...)`。
- `data/service/ProgressService.kt`：`saveProgress` 后对 ABS book 触发 `AbsProgressSyncer`。
- `ui/settings/SettingsScreen.kt` / `SettingsViewModel.kt`：加"连接 ABS"入口与回调。
- （可选）`AppDatabase.kt`：若引入 outbox 表，schema version +1 + migration。

---

## 7. 分阶段实施计划

| 阶段 | 目标 | 主要改动 | 验收标准 |
|---|---|---|---|
| **M0 脚手架** | 常量 + provider 分支 + API client 骨架 | `AudiobookSchema` 常量、`LibrarySourceKind`/factory 分支、`AbsApiClient`（login/ping） | 单测：登录拿到 token；ping 成功 |
| **M1 连接 + 浏览** | 在书架看到 ABS 书（暂不播放） | `AbsServerDialog`、`addAudiobookshelfRoot`、`AbsLibraryImporter`（拉条目 + 详情 + 封面）、`ScanService` 分支 | 添加服务器→选库→书架出现书、封面、元数据、章节数正确 |
| **M2 播放** | ABS 书可流式播放 | `AbsSourceProvider`（stream/range/exists） | 多轨连播、seek、切章、后台播放、通知栏均正常 |
| **M3 进度同步** | 双向进度 + 离线补偿 | `AbsProgressSyncer`、`ProgressService` 钩子、outbox + WorkManager | 手机 A 听到 50% → 服务端/手机 B 同步到 50%；离线听完联网后回传 |
| **M4 打磨** | 健壮性 | `AvailabilityChecker`/`checkRoot`→`/ping`、token 失效重登、多库、明文/自签名、错误态 UI | 断网/换 token/服务器宕机均有明确提示，不崩溃 |
| **M5（预研，可选）** | 离线下载 | 逐轨缓存到本地、`BookFileEntity` 切本地路径 | 下载后飞行模式可播 |

---

## 8. 风险与开放问题

- **`/s/` 静态路由的鉴权方式**：需对目标服务器版本确认 `Authorization: Bearer` header 是否被接受，否则回退 `?token=<jwt>` 查询参数。**（M2 前需验证）**
- **token 生命周期**：不同 ABS 版本对 JWT 过期/轮换策略不同（2.x 有变化）。需处理 401 → 静默重登。
- **ABS 版本兼容**：建议声明最低支持版本（如 2.2+），并在连接时读取 `serverSettings.version` 做能力判断；字段在大版本间可能有差异。
- **podcast vs book**：v1 仅支持 `mediaType == book`；podcast（含 `episodeId` 维度）作为后续。
- **大库性能 / 增量**：万级条目需分页 + 按 `updatedAt` 增量；避免每次全量拉详情。
- **章节为空的书**：部分 ABS 书 `chapters: []`，应回退为"每轨一章"或无章节。
- **自签名 TLS / 局域网 http**：复用已有明文开关 + `allowInsecureTls` 先例，但需安全提示。
- **进度冲突**：LWW 在多设备并发时可能丢更新；可接受作为 v1 策略，后续可引入服务端会话（`/session`）。

---

## 9. 不推荐的替代方案

**把 ABS 伪装成文件系统、复用 `SourceInventoryScanner` + `ImportPipeline`。**
看似改动最小（只写一个 provider），实则：

- 为解析嵌入式元数据/封面，需通过网络 **Range 下载音频字节**，扫描一个大库会产生海量慢请求；
- 本地 **启发式分组**（`HeuristicGroupStep`）会覆盖 ABS 已经正确的分组结果，可能把一本书拆错；
- **丢弃** ABS 的服务端章节与结构化元数据，反而用质量更差的本地推断；
- 与"在线后端应当轻量、快速"的预期背道而驰。

因此明确否决，改用 §4.2 的 REST 直导入。

---

## 10. 工作量与依赖

- **依赖**：OkHttp（已有）、`org.json`（Android 内置，已在用）即可零新增依赖完成。若追求类型安全，可选引入 `kotlinx.serialization`（需加 Kotlin 插件 + runtime 依赖）——非必需，建议 v1 先用 `org.json` 保持与现有代码一致。
- **粗略量级**：M0–M3（连接/浏览/播放/进度）属于"中等"工作量，核心难点在进度双向同步与封面/章节映射；provider 与连接 UI 因有 WebDAV 样板而较轻。
- **架构影响**：零核心改动，全部为可插拔的增量扩展——这本身也印证了当前 VFS/Provider 架构的可扩展性设计是成功的。

---

### 附：关键源码引用

- 源抽象与工厂：`app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/LibrarySourceProvider.kt`
- 远程源样板：`…/sourceProvider/webdav/WebDavSourceProvider.kt`、`WebDavCredentialStore.kt`
- Root 持久化：`data/store/LibraryRootStore.kt`（`addWebDavRoot`）、`data/entity/LibraryRootEntity.kt`
- 播放链路：`media/VfsPlaybackUri.kt`、`media/VfsPlaybackDataSource.kt`、`library/vfs/VfsFileInterface.kt`
- 扫描调度：`library/sync/LibrarySyncWorker.kt`、`data/service/ScanService.kt`、`library/SourceInventoryScanner.kt`
- 进度：`media/ProgressSyncTracker.kt`、`data/service/ProgressService.kt`、`data/entity/BookProgressEntity.kt`
- 可达性：`library/availability/PlaybackReachabilityManager.kt`
- ABS API 文档：`docs/cc/Audiobookshelfdocs/{_server,_libraries,_items,_sessions,_me}.md`
