# Audiobookshelf Catalog Mirror 在线后端方案 B 设计

日期：2026-06-01

## 结论

方案 B 是当前代码架构下最稳的接入方式：把 Audiobookshelf Server 作为新的可选在线来源，把远端 catalog 同步成本地 Room 镜像，然后继续复用现有首页、详情页、播放计划、播放进度和 VFS 播放链路。

设计注释：这个方案不把 ABS REST API 直接暴露给 `LibraryFacade`、ViewModel 或播放器。REST 的不稳定性、鉴权、错误重试和远端字段差异集中留在 ABS 反腐层内，现有 UI 继续消费本地 `Flow` 和 Room 数据。

## 设计依据

当前代码已经存在清晰的本地领域边界：

- `app/src/main/java/com/viel/aplayer/data/LibraryFacade.kt`：只聚合多个细粒度 gateway，不适合作为远端 REST 聚合大类继续膨胀。
- `app/src/main/java/com/viel/aplayer/data/gateway/BookQueryGateway.kt`：上层查询依赖本地书籍流、详情、搜索、章节、书签和播放计划。
- `app/src/main/java/com/viel/aplayer/data/gateway/ProgressGateway.kt`：进度模型以本地全书毫秒位置为核心。
- `app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/LibrarySourceProvider.kt`：SAF 和 WebDAV 已经通过 provider 抽象隔离存储来源。
- `app/src/main/java/com/viel/aplayer/media/BookPlaybackPlan.kt`：播放器需要的是稳定的 `BookFileEntity` 列表和起播位置。
- `app/src/main/java/com/viel/aplayer/media/PlaybackPlanBuilder.kt`：播放计划最终转换为 Media3 `MediaItem`。
- `app/src/main/java/com/viel/aplayer/media/VfsPlaybackUri.kt`：播放 URI 以 `BookFileEntity.id` 为入口。
- `app/src/main/java/com/viel/aplayer/media/VfsPlaybackDataSource.kt`：播放热路径只关心通过 VFS 打开指定文件和偏移。
- `app/src/main/java/com/viel/aplayer/library/SourceInventoryScanner.kt`：当前扫描器面向文件树，不适合直接承载 ABS catalog。
- `app/src/main/java/com/viel/aplayer/library/orchestrator/ImportPipeline.kt`：当前导入流水线负责本地媒体解析，ABS 已经在服务端完成这些整理工作。
- `app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt`：已有 OkHttp、Range、远端读取经验，可作为 ABS streaming provider 的实现参考。
- `app/src/main/java/com/viel/aplayer/data/db/AudiobookSchema.kt`：来源类型、书籍类型、章节来源需要新增 ABS 相关常量。

ABS 接口能力参考 `docs/cc/Audiobookshelfdocs` 与官方 API 文档。它们只用来判断远端能力，不作为本项目架构依据。

## 目标

- 新增 ABS 作为可选在线后端。
- 支持登录或 API token 连接 ABS server。
- 支持选择一个或多个 ABS book library。
- 把 ABS book item、audio track、chapter、cover、user progress 同步到本地 Room。
- 播放时继续使用 `BookPlaybackPlan -> VfsPlaybackUri -> VfsPlaybackDataSource`。
- 使用 ABS `contentUrl` 做带鉴权的 Range streaming。
- 播放过程中把本地进度同步回 ABS。

## 非目标

- 首期不支持 podcast。
- 首期不做 ABS 元数据回写。
- 首期不删除、移动或上传 ABS 远端条目。
- 首期不上传封面。
- 首期不接 Socket.io 实时推送。
- 不重构现有 `LibraryFacade` 对外形态。
- 不把 ABS 伪装成普通文件树交给 `ImportPipeline` 扫描。

设计注释：这些非目标用于收窄第一版风险。ABS 的 REST 能力很多，但当前项目最需要的是“可读、可播、进度可同步”，不是复制 ABS 管理后台。

## 核心原则

1. REST 只进入 ABS 反腐层。
2. UI 继续读本地 Room 和 `Flow`。
3. 播放继续走现有 VFS 和 Media3 链路。
4. ABS catalog 不进入 `SourceInventoryScanner` 和 `ImportPipeline`。
5. 远程 ID 映射集中管理，避免字符串拼接散落在同步、播放和 UI 中。
6. 本地进度仍是播放时的即时真相，ABS 进度作为远端同步对象。
7. ABS 凭据只在网络层和 provider 内部使用，不下沉到 UI 状态或播放 URI。

## 推荐包边界

建议新增一组小而明确的组件，避免形成一个 `AbsManager` 式上帝类。

```text
app/src/main/java/com/viel/aplayer/abs/
  auth/
    AbsCredentialStore.kt
    AbsCredential.kt
  net/
    AbsApiClient.kt
    AbsApiError.kt
    AbsBinaryResponse.kt
    dto/
      AbsAuthDto.kt
      AbsLibraryDto.kt
      AbsLibraryItemDto.kt
      AbsLibraryItemsResponseDto.kt
      AbsPlayRequestDto.kt
      AbsPlaybackSessionDto.kt
      AbsSessionSyncDto.kt
      AbsUserProgressDto.kt
  mapping/
    AbsRemoteIdMapper.kt
    AbsCatalogMapper.kt
    AbsProgressMapper.kt
  sync/
    AbsCatalogSynchronizer.kt
    AbsSyncStateDao.kt
    AbsSyncStateEntity.kt
    AbsSyncWorkScheduler.kt
  vfs/
    AbsSourceProvider.kt
    AbsSourceProviderFactory.kt
  playback/
    AbsPlaybackSessionSyncer.kt
    AbsPlaybackSessionStore.kt
```

设计注释：这里用 `abs` 作为顶层包，是为了让 ABS 相关网络、映射、同步和播放会话同步保持内聚。它仍然通过现有 gateway、Room entity、VFS provider 等边界接入主系统，而不是反向污染主系统。

### `AbsCredentialStore`

职责：

- 保存 ABS server baseUrl、token、userId、username、serverKey、默认 library 选择。
- 负责 token 更新或删除。
- 隐藏底层存储方式，后续可替换为 Android Keystore 加密。

不负责：

- 不发起 REST 请求。
- 不解析 catalog。
- 不做同步调度。

设计注释：凭据生命周期与 WebDAV 类似，但 ABS 使用 Bearer token 和用户 API token，不应复用 WebDAV 的 Basic Auth 模型。

### `AbsApiClient`

职责：

- 封装 OkHttp 请求。
- 统一拼接 baseUrl。
- 统一注入 `Authorization: Bearer <token>`。
- 统一处理超时、HTTP 错误码、JSON 解析错误和 server version。
- 提供按业务语义命名的方法，例如 `getLibraries()`、`getLibraryItems()`、`getItemDetail()`、`openPlaybackSession()`、`syncSession()`、`closeSession()`。
- 固化浏览器验证过的 HTTP 方法：`authorize()` 必须使用 `POST /api/authorize`，不能使用 `GET /api/authorize`。
- 统一解析 ABS 返回的相对 API 路径，例如 `media.tracks[].contentUrl = /api/items/<itemId>/file/<ino>`。

不负责：

- 不写 Room。
- 不生成本地 ID。
- 不直接操作播放器。

设计注释：项目当前已有 OkHttp，但正式实现时建议引入结构化 JSON 解析库，例如 kotlinx.serialization 或 Moshi。不要用字符串手写解析 ABS 响应。

浏览器验证后的接口契约：

```kotlin
interface AbsApiClient {
    suspend fun login(username: String, password: String): AbsLoginResult
    suspend fun authorize(token: String): AbsAuthorizeResult
    suspend fun getLibraries(): List<AbsLibraryDto>
    suspend fun getLibraryItemsMinified(libraryId: String): AbsLibraryItemsResponseDto
    suspend fun getItemDetail(itemId: String): AbsLibraryItemDto
    suspend fun batchGetItems(itemIds: List<String>): List<AbsLibraryItemDto>
    suspend fun getProgressOrNull(itemId: String): AbsMediaProgressDto?
    suspend fun downloadCover(itemId: String): AbsBinaryResponse
    suspend fun openPlaybackSession(itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto
    suspend fun syncSession(sessionId: String, request: AbsSessionSyncDto): Unit
    suspend fun closeSession(sessionId: String, request: AbsSessionSyncDto): Unit
}
```

实现要求：

- `login()` 调 `POST /login`，只从响应中取 `user.token` 和必要用户信息；token 不写日志、不写异常文案。
- `authorize()` 调 `POST /api/authorize` 并附带 Bearer token。Chrome DevTools 已验证 `GET /api/authorize` 返回 404，所以实现和测试都不要保留 GET 兜底。
- `getLibraryItemsMinified()` 固定使用 `GET /api/libraries/<libraryId>/items?limit=0&minified=1&collapseseries=0`。
- `batchGetItems()` 使用 `POST /api/items/batch/get`，body 使用 `libraryItemIds` 数组；demo 2.35.1 的响应体是 `{ "libraryItems": [...] }` 包装对象，不是裸数组。单本详情 `GET /api/items/<itemId>?expanded=1&include=progress,authors` 只作为补偿和调试入口。
- `AbsLibraryItemDto` 的音频字段必须优先兼容 `media.tracks[]`。demo 2.35.1 的详情接口在 `media.tracks[].contentUrl` 暴露实际播放 URL；`media.audioFiles[]` 保留文件元数据但不一定带 `contentUrl`，旧文档或 session 返回里的 `audioTracks[]` 只能作为兼容输入。
- `getProgressOrNull()` 对 `GET /api/me/progress/<itemId>` 的 404 返回 `null`，不要把无进度当作同步失败。
- `downloadCover()` 接受 `image/*`，浏览器验证中同一 demo 可出现 `image/jpeg` 或 `image/webp`；缓存文件扩展名和解码逻辑必须以响应头或实际字节为准。
- `syncSession()` 和 `closeSession()` 只要求 2xx 成功。demo 2.35.1 返回短文本 `OK`，不是 Playback Session JSON。

设计注释：这些方法是反腐层的“协议事实表”。把 HTTP method、query、body 字段名、404/null 语义和短文本响应都收在 `AbsApiClient`，可以避免 synchronizer、provider、playback syncer 各自重新理解 ABS REST 细节。

### `AbsCatalogSynchronizer`

职责：

- 拉取 ABS library、item list、item detail、user progress。
- 把远端 DTO 交给 mapper 转成本地 entity。
- 在 Room 事务里写入 `BookEntity`、`BookFileEntity`、`ChapterEntity`、`BookProgressEntity`。
- 维护同步状态、失败原因和最后同步时间。

不负责：

- 不关心 UI 展示。
- 不打开媒体流。
- 不直接调用播放器。

设计注释：同步器是 REST 与本地 Room 的主要边界。它可以复杂，但复杂度应由子组件拆开：API client 负责请求，mapper 负责转换，sync state 负责增量状态。

### `AbsSourceProvider`

职责：

- 作为 ABS 远端媒体流的 VFS provider。
- 根据 `BookFileEntity.sourcePath` 中保存的 ABS `contentUrl` 发起带 token 的 GET/Range 请求。
- 支持播放器按 offset 打开远端音频。
- 把 401、403、404、5xx、timeout 映射为现有可用性检查可理解的失败状态。

不负责：

- 不枚举 ABS 目录。
- 不解析元数据。
- 不写入书籍 entity。

设计注释：`AbsSourceProvider` 可以借鉴 `WebDavSourceProvider` 的 Range 处理，但它的身份是“播放流 provider”，不是“文件树 provider”。

### `AbsPlaybackSessionSyncer`

职责：

- 播放 ABS 书籍时调用 `/api/items/<ID>/play` 创建远端 session。
- 播放中按节奏调用 `/api/session/<ID>/sync`。
- 暂停、切书、退出或释放播放器时调用 `/api/session/<ID>/close`。
- 网络失败时保留本地脏同步记录，等待下次补偿。

不负责：

- 不决定本地播放进度。
- 不写 UI 状态。
- 不处理 catalog 同步。

设计注释：现有 `ProgressSyncTracker` 已有约 10 秒保存本地进度的节奏。ABS 同步器可以复用这个节奏信号，但不应该把远端 session 逻辑塞进 `ProgressService`。

## 数据模型变更草案

### Schema 常量

建议在 `AudiobookSchema.kt` 中新增：

```kotlin
object LibrarySourceType {
    const val ABS = "ABS"
}

object SourceType {
    const val ABS_REMOTE = "ABS_REMOTE"
}

object ChapterSource {
    const val ABS = "ABS"
}
```

设计注释：`ABS` 表示书库根来源，`ABS_REMOTE` 表示书籍文件来自 ABS 远端 track。两层语义分开，可以避免后续本地导入类型和远端媒体类型混在一起。

### 新增同步状态表

建议新增 `AbsSyncStateEntity`：

```kotlin
@Entity(tableName = "abs_sync_state")
data class AbsSyncStateEntity(
    @PrimaryKey val rootId: String,
    val serverKey: String,
    val libraryId: String,
    val lastFullSyncAt: Long?,
    val lastIncrementalSyncAt: Long?,
    val serverVersion: String?,
    val lastError: String?,
    val fullListFingerprint: String?
)
```

字段注释：

- `rootId`：对应本地 `LibraryRootEntity.id`，用于把一个 ABS library 和本地书库根绑定。
- `serverKey`：本地生成的 server 稳定标识，用于避免 baseUrl 改写影响所有本地 ID。
- `libraryId`：ABS library id。
- `lastFullSyncAt`：最后一次全量同步完成时间。
- `lastIncrementalSyncAt`：最后一次增量同步完成时间。
- `serverVersion`：记录 ABS server 版本，便于兼容字段差异。
- `lastError`：保留最后一次同步错误，供设置页展示。
- `fullListFingerprint`：完整轻量清单的内容指纹，后续可用于判断远端集合是否变化；第一版可为空。

设计注释：同步状态单独成表，不塞进 `LibraryRootEntity`，是为了避免来源根表承担太多远端协议细节。

### 远程 ID 映射

集中放在 `AbsRemoteIdMapper`：

```text
serverKey = sha256(normalizedBaseUrl + userId).take(16)
rootId = abs:<serverKey>:library:<libraryId>
bookId = abs:<serverKey>:item:<libraryItemId>
bookFileId = abs:<serverKey>:item:<libraryItemId>:track:<trackIndex>
sessionLocalId = abs:<serverKey>:session:<sessionId>
```

设计注释：所有 ABS 本地 ID 必须由同一个 mapper 生成。不要在 synchronizer、provider、ViewModel 中分别拼接 ID，否则迁移和排查会很痛。

### `LibraryRootEntity` 映射

- `id`：`abs:<serverKey>:library:<libraryId>`。
- `sourceType`：`ABS`。
- `sourceUri`：规范化后的 server baseUrl。
- `basePath`：ABS libraryId。
- `displayName`：ABS library name。
- `credentialId`：指向 ABS 凭据。

设计注释：`sourceUri` 不保存 token，token 只存在凭据存储中。这样日志、数据库导出和 UI 展示都不会意外泄露凭据。

### `BookEntity` 映射

- `id`：`abs:<serverKey>:item:<libraryItemId>`。
- `rootId`：对应 ABS library root。
- `sourceType`：`ABS_REMOTE`。
- `title`：ABS item media metadata title，缺失时用 item name。
- `author`：优先 authors/persons，缺失时用 metadata author。
- `narrator`：metadata narrator。
- `description`：metadata description。
- `year`：metadata publishedYear。
- `totalDurationMs`：ABS duration 秒转毫秒。
- `totalFileSize`：优先按 `media.tracks[]`/`media.audioFiles[]` 可用的 size 字段求和，缺失时为 0；demo 2.35.1 的 track 只暴露 duration/bitRate/mimeType/contentUrl，不应假定一定有 size。
- `coverPath`：本地封面缓存路径。

设计注释：APlayer 内部仍然把 ABS item 当作一本书展示，而不是在 UI 层引入 ABS item DTO。这样首页、详情页和搜索逻辑不需要感知 REST。

### `BookFileEntity` 映射

- 一条 ABS 可播放 track 映射为一个 `BookFileEntity`。当前 demo 详情接口以 `media.tracks[]` 为主，`media.audioFiles[]` 只作为补充文件元数据，`audioTracks[]` 仅作为旧字段或 session 返回兼容。
- `id`：`abs:<serverKey>:item:<libraryItemId>:track:<trackIndex>`。
- `bookId`：对应本地 `BookEntity.id`。
- `sourcePath`：ABS `media.tracks[].contentUrl` 原样保存，例如 `/api/items/<itemId>/file/<ino>`。
- `sourceIdentity`：`libraryItemId + trackIndex + contentUrl` 的稳定组合。
- `durationMs`：track duration 秒转毫秒。
- `fileSize`：track size，缺失时为 0。
- `index`：写入当前 `BookFileEntity.index` 字段，使用 track index 排序；不要在实现里新增文档旧写法里的 `sortOrder` 字段。

设计注释：`sourcePath` 使用 `contentUrl`，但播放时不要直接把它暴露成普通网络 URL；仍由 `AbsSourceProvider` 按 ABS server baseUrl 解析成真实请求地址，并附加鉴权和 Range header。Chrome DevTools MCP 验证表明 demo 的 `contentUrl` 是 `/api/...`，当用户配置的 baseUrl 带 `/audiobookshelf` 子路径时，解析结果必须是 `<baseUrl>/api/...`，不能错误落到域名根路径 `/api/...`。

### `ChapterEntity` 映射

- ABS chapter `start` 和 `end` 从秒转为毫秒。
- `bookFileId` 通过 track 的 offset 和 duration 计算。
- `title` 使用 ABS chapter title。
- `source` 使用 `ABS`。

设计注释：现有播放进度以全书位置为核心，章节需要映射到具体文件。ABS chapter 是全书时间线语义，必须在 mapper 中完成“全书时间 -> track 时间”的归属计算。

### `BookProgressEntity` 映射

- ABS `currentTime` 秒转 `globalPositionMs`。
- ABS `progress` 可映射为百分比缓存，不作为播放器主位置。
- ABS `isFinished` 映射为本地已读状态。
- `updatedAt` 使用 ABS 进度更新时间，缺失时使用同步时间。

设计注释：本地播放期间，本地进度优先。启动同步时如果 ABS 远端更新时间更新，再按冲突策略决定是否覆盖本地位置。

## ABS API 调用流

### 接口筛选

首期方案实际需要的接口分为三组。

鉴权与选库：

- `GET /ping` 或 `GET /status`：轻量连通性探测，用于区分 baseUrl 不可达和账号鉴权失败。
- `POST /login`：用户名密码登录，返回 `user.token` 和 server 信息。
- `POST /api/authorize`：使用已保存 token 恢复登录态。
- `GET /api/libraries`：列出用户可访问 library，并读取 `mediaType`。

Catalog mirror：

- `GET /api/libraries/<libraryId>/items?limit=0&minified=1&collapseseries=0`：拉取完整轻量清单。
- `GET /api/items/<itemId>?expanded=1&include=progress,authors`：拉取单本详情，可作为单项补偿和调试入口。
- `POST /api/items/batch/get`：按批拉取 item 详情，首期详情同步主入口。
- `GET /api/items/<itemId>/cover`：下载封面。

播放与进度：

- `POST /api/items/<itemId>/play`：为 book 创建播放会话。
- `HEAD/GET <track.contentUrl>`：实际音频流入口，客户端播放时需要 Range。
- `POST /api/session/<sessionId>/sync`：同步打开会话的当前位置。
- `POST /api/session/<sessionId>/close`：关闭打开会话，可附带最后一次同步数据。

设计注释：`/play` 使用 `libraryItemId` 创建会话，但实际媒体读取使用详情接口里的 `media.tracks[].contentUrl`。不要把 item id 当作音频文件 URL。

### Demo 验证结果

验证日期：2026-06-01

验证环境：`https://audiobooks.dev/audiobookshelf`，demo 账号，server version `2.35.1`。

验证原则：

- 没有实际播放音频。
- 只对音频 `contentUrl` 做 `HEAD` 请求确认头信息，没有下载音频正文。
- `/play`、`/sync`、`/close` 只用 0 秒会话验证接口可用性，并立即关闭会话。
- 没有调用删除、上传、元数据回写或播放进度修改类接口。

浏览器复核：

- in-app Browser 已能打开 `https://audiobooks.dev/audiobookshelf/login`，并用 demo 账号进入已登录页面。
- ABS 前端实际观察到 `POST /api/authorize`、`GET /api/libraries`、`GET /api/libraries/<id>?include=filterdata`、`GET /api/libraries/<id>/personalized`、`GET /api/items/<id>?expanded=1&...`、`GET /api/items/<id>/cover` 等 XHR 或资源请求。
- 浏览器可直接打开 `/ping` 并读取 `{"success":true}`。
- 当前 in-app Browser 会话未开放 CDP network response 读取能力，裸打开 `/api/libraries`、`/status` 会被浏览器客户端拦截。因此响应体结构仍以 HTTP/API 验证结果为准，浏览器复核用于确认 ABS 前端真实调用路径和可见渲染。
- 书籍详情页可见 `First Fifty Digits of Pi`，并显示 `Chapters 56`、`Audio Tracks 1`、`Library Files 1`。
- Podcast library 页面可见 `Library: Podcasts`，bookshelf 页触发 `/api/libraries/d11c4630-d872-43b5-8564-d9a02a59ff5f/items?...&minified=1...`，并显示 `Librivox Community Podcast` 与 `23` 条目计数。

Chrome DevTools MCP 复核：

- Chrome DevTools MCP 可打开 demo、登录 demo 账号，并在 Network 面板观察到真实 XHR：`POST /login`、`GET /status`、`GET /api/libraries`、`GET /api/libraries/<id>?include=filterdata`、`GET /api/libraries/<id>/personalized`、`GET /api/tasks?include=queue`。
- 使用临时 token 直接调用受保护接口时，`POST /api/authorize` 返回 200，`GET /api/authorize` 返回 404；实现必须使用 `POST`。
- `GET /api/libraries/<id>/items?limit=0&minified=1&collapseseries=0` 在 `Audiobooks` 返回 `total=16`，在 `Podcasts` 返回 `total=2`。
- `GET /api/items/1645b4c9-0561-48f5-9d30-8e57e82bc704?expanded=1&include=progress,authors` 返回 `audioFiles=1`、`tracks=1`、`chapters=56`、`libraryFiles=1`，首个 track 的 `contentUrl` 为 `/api/items/<itemId>/file/856465`。
- `POST /api/items/batch/get` 返回 `{ "libraryItems": [...] }`，不是裸数组；批量详情 DTO 需要保留这一层响应包装。
- `HEAD <track.contentUrl>` 返回 `Content-Type: audio/mpeg`、`Content-Length: 77458087`、`Accept-Ranges: bytes`。
- `GET <track.contentUrl>` 携带 `Range: bytes=0-15` 返回 `206`、`Content-Range: bytes 0-15/77458087`、`Content-Length: 16`，Range seek 链路成立；本次只读取 16 字节用于验证响应头和状态码，没有实际播放完整音频。
- 封面 HEAD 在 Chrome 上返回 `image/webp`，早前 HTTP GET 返回过 `image/jpeg`；实现不能硬编码封面 MIME，按 `image/*` 和响应头处理。
- `/play`、`/sync`、`/close` 用 0 秒会话验证成功，`sync` 和 `close` 响应体为短文本 `OK`。

验证结论：

| 接口 | 结果 | 关键发现 |
| --- | --- | --- |
| `GET /ping` | 200 | demo 可用于登录前轻量连通性探测。 |
| `GET /status` | 200 | demo 可用于登录前轻量服务状态探测。 |
| `POST /login` | 200 | 返回用户与 token；demo 实际用户名会变成类似 `demo45230` 的临时用户。 |
| `POST /api/authorize` | 200 | Bearer token 可恢复登录态；返回 server settings，版本为 `2.35.1`。 |
| `GET /api/libraries` | 200 | 返回 `mediaType`；demo 有 `Audiobooks: book`、`E-Books: book`、`Mixed: book`、`Podcasts: podcast`。 |
| `GET /api/libraries/<id>/items?limit=0&minified=1&collapseseries=0` | 200 | `Audiobooks` 返回 `total=16`、`mediaType=book`；`Podcasts` 返回 `total=2`、`mediaType=podcast`。 |
| `GET /api/items/<id>?expanded=1&include=progress,authors` | 200 | book 详情包含 `media.audioFiles`、`media.tracks`、`media.chapters`、`libraryFiles`、`size`；当前 demo 的播放 URL 在 `media.tracks[].contentUrl`；无进度时 `userMediaProgress` 可为空。 |
| `POST /api/items/batch/get` | 200 | 返回 `{ libraryItems: [...] }` 包装对象，可作为分批详情主接口。 |
| `GET /api/items/<id>/cover` | 200 | demo 可返回 `image/jpeg` 或 `image/webp`；实现应按 `image/*` 处理，不要硬编码 MIME。 |
| `HEAD <track.contentUrl>` | 200 | 返回 `Content-Type: audio/mpeg`、`Accept-Ranges: bytes`，支持 Range 播放。 |
| `GET <track.contentUrl>` with `Range: bytes=0-15` | 206 | 返回 `Content-Range: bytes 0-15/77458087`，验证非零 seek 可按同一 provider 策略实现。 |
| `POST /api/items/<id>/play` | 200 | 返回 Playback Session Expanded，包含 `libraryItemId`、`mediaType=book`、`audioTracks`、`libraryItem`。 |
| `POST /api/session/<id>/sync` | 200 | demo 2.35.1 返回体不是文档中的完整 session JSON，实测为短文本响应；实现应只依赖 2xx 成功。 |
| `POST /api/session/<id>/close` | 200 | demo 2.35.1 返回体不是完整 session JSON，实测为短文本响应；实现应只依赖 2xx 成功。 |
| `GET /api/me/progress/<itemId>` | 404 | 对没有进度的 book 返回 404；同步时应把 404 当作“无远端进度”，不是错误。 |

重要差异：

- 新版 demo 的 `track.contentUrl` 形态是 `/api/items/<itemId>/file/<ino>`，不是旧文档示例中的 `/s/item/<itemId>/<filename>`。
- `sync` 和 `close` 在 demo 2.35.1 上返回 200，但响应体不是 Playback Session JSON；客户端不能强依赖返回体解析。
- `Library.mediaType` 和 `LibraryItem.mediaType` 都是可靠分型字段，值为 `book` 或 `podcast`。
- `mediaType=book` 不等于一定是有声书；demo 有 `E-Books` 和 `Mixed` 也是 `book` library。APlayer 首期同步时还需要以详情中的 `media.tracks` 或 `media.audioFiles` 是否存在来判断是否可播放。

设计注释：demo 验证说明当前方案可行，但实现 DTO 要宽松：允许字段缺失、允许 `userMediaProgress` 为空、允许 session sync/close 返回空体或短文本。

### 连接与选库

```text
输入 baseUrl/token 或 username/password
  -> GET /ping 或 GET /status
  -> POST /login 或 POST /api/authorize
  -> GET /api/libraries
  -> 用户选择 book library
  -> 创建 LibraryRootEntity 和 AbsSyncStateEntity
```

设计注释：`/ping` 或 `/status` 用于快速判断 baseUrl 是否可达；鉴权接口用于确认 token 是否有效；选库后才创建本地 root，避免保存半成品来源。

### Catalog 同步

```text
GET /api/libraries/<libraryId>/items?limit=0&minified=1&collapseseries=0
  -> 保存完整轻量 item 集合
  -> 根据完整集合计算新增、仍存在和疑似远端删除
POST /api/items/batch/get
  -> 按批拉取 expanded item 详情
  -> map BookEntity
  -> map BookFileEntity
  -> map ChapterEntity
  -> map BookProgressEntity
  -> 下载或排队下载 cover
```

设计注释：列表接口负责发现条目，详情接口负责完整映射。首期统一使用全量轻量清单，不引入分页状态机；后续再用 ABS 更新时间、服务端事件或定期扫描做增量。

### 封面同步

```text
GET /api/items/<itemId>/cover
  -> 检查 Content-Type 是否为 image/*
  -> 按响应 MIME 或图片探测结果选择缓存扩展名
  -> 写入本地 cover cache
  -> 更新 BookEntity.coverPath
```

设计注释：封面失败不应导致整本书同步失败。封面缓存可以独立重试，书籍 catalog 先可见。Chrome DevTools 验证中封面 HEAD 返回过 `image/webp`，早前 HTTP GET 返回过 `image/jpeg`，所以缓存层只能依赖 `image/*` 和实际字节，不要把 ABS 封面固定成 jpg。

### 播放与进度同步

```text
用户播放 ABS book
  -> 本地构造 BookPlaybackPlan
  -> AbsPlaybackSessionSyncer 调用 POST /api/items/<itemId>/play
  -> PlaybackPlanBuilder 生成 aplayer-vfs://book-file/<bookFileId>
  -> VfsPlaybackDataSource 通过 AbsSourceProvider 解析并打开 contentUrl
  -> 播放中周期性 POST /api/session/<sessionId>/sync
  -> 停止或切换时 POST /api/session/<sessionId>/close
```

设计注释：远端 session 是同步对象，不是播放器的驱动对象。播放器仍由本地播放计划驱动，这样 seek、章节、断点续播和通知栏进度可以沿用现有逻辑。浏览器验证说明详情接口中的 `media.tracks[].contentUrl` 是实际媒体入口，`/play` 返回里的 `audioTracks[]` 只用于远端会话上下文校验；播放器读流时仍以本地 `BookFileEntity.id` 为入口，由 provider 在最后一刻解析 URL 和加 Bearer。

## 同步策略

### 第一版策略

- 手动触发全量同步。
- 默认使用 `limit=0&minified=1` 一次性拉取完整轻量清单。
- 详情通过 `POST /api/items/batch/get` 分批拉取。
- 限制详情批次大小和请求并发。
- 支持取消同步，取消后不执行删除收敛。
- item 清单先形成远端 ID 集合，详情和封面后台补齐。
- 同步中单本失败不阻断整个 library。
- 只同步 `mediaType=book`。
- 第一版完全不走分页；如果首次同步较慢，用同步弹窗和进度提示承接。

设计注释：这里的“全量同步”指全量轻量清单，不是一次性拉取全量详情。清单用于判断新增、缺失和可能更新；详情、章节、track、progress 仍然分批处理，避免单次响应过大。

### 全量清单策略

ABS 的 library items 接口允许 `limit=0` 表示不分页，返回当前查询条件下的全部结果。第一版建议使用它拉取完整 minified 清单，降低分页期间远端变化导致的状态不一致。

第一版默认请求：

```text
GET /api/libraries/<libraryId>/items?limit=0&minified=1&collapseseries=0
```

参数建议：

- `limit = 0`：请求完整轻量清单。
- `minified = 1`：清单阶段只做远端 item 发现，不拉取完整详情。
- `collapseseries = 0`：同步镜像必须保留每本书的真实 item，不能把 series 折叠成一个展示项。
- `sort` 可不传：同步正确性不依赖远端排序。

全量清单成功后，客户端得到完整的 `remoteItemId` 集合：

```text
remoteIds = ABS 本次完整清单中的 item id 集合
localIds = abs_item_mirror 中同一 root 的 ACTIVE item id 集合

新增 = remoteIds - localIds
删除候选 = localIds - remoteIds
可能更新 = remoteIds ∩ localIds 中 remoteUpdatedAt 变化的 item
```

详情拉取策略：

```text
完整清单只负责发现 item id
  -> 新增和可能更新的 item id 入详情队列
  -> 分批调用 POST /api/items/batch/get
  -> DTO 映射成本地 entity
  -> Room 事务 upsert
```

批次建议：

- `batch size = 20~50`：表示每个 `POST /api/items/batch/get` 请求 body 里放入的 item id 数量，不是并发数量。
- 大库或弱网使用 `batch size = 20`。
- `concurrency = 1~2`：表示同时最多运行 1 到 2 个 batch 请求。
- 单个 batch 失败时拆小重试，仍失败则记录单项错误，不进入删除确认。

可行性阈值：

- `<= 3000` 本：默认全量 minified 清单同步。
- `3000~10000` 本：仍可全量 minified 清单，但详情批大小降到 `20`，并强制后台任务执行。
- `> 10000` 本：仍走同一套全量清单逻辑；同步前弹窗提示耗时和流量风险，用户确认后执行。

设计注释：全量清单比分页状态机简单，因为新增和删除都基于一次完整集合差异。它仍不是服务端事务快照，但对于有声书库常见规模已经足够实用；真正重的详情、章节和 track 不放在同一个大响应里。

### 不使用分页的首期策略

第一版不实现分页同步。所有 ABS book library 都走同一套流程：

```text
完整 minified 清单
  -> 集合对比
  -> 新增/更新详情分批拉取
  -> 删除候选两阶段确认
```

不使用分页的原因：

- 少一套状态机，降低新增、删除、权限变化时的页间漂移问题。
- 删除判断基于完整 `remoteIds` 集合，更容易解释和测试。
- 代码路径统一，避免大库和小库行为不一致。
- 首次同步慢是可见的产品体验问题，可以用弹窗、进度和后台任务处理；误删是数据正确性问题，修复成本更高。

用户体验要求：

- 首次同步前显示弹窗，说明会读取完整远端书库，可能耗时较长。
- 弹窗展示当前阶段：拉取清单、对比本地、拉取详情、下载封面、写入数据库。
- 展示数量进度：`已处理 item / 总 item`、`当前详情批次 / 总批次`。
- 支持取消；取消后保留已成功写入的新增/更新，但不执行删除收敛。
- 同步可转入后台任务；设置页显示最近同步状态和错误。

设计注释：首期选择简单一致的全量清单策略，是用可接受的等待时间换更低的状态复杂度。后续只有在真实用户大库数据证明无法接受时，再引入分页或分区同步。

### 同步轮次与幂等入库

每次完整清单同步创建一个 `syncRunId`，同步器对每个成功见到的远端 item 记录本轮可见事实：

```text
remoteItemId
  -> localBookId
  -> lastSeenSyncRunId = syncRunId
  -> lastSeenAt = now
  -> state = ACTIVE
```

建议新增镜像状态表：

```kotlin
@Entity(tableName = "abs_item_mirror")
data class AbsItemMirrorEntity(
    @PrimaryKey val localBookId: String,
    val rootId: String,
    val serverKey: String,
    val remoteItemId: String,
    val lastSeenSyncRunId: String?,
    val lastSeenAt: Long?,
    val remoteUpdatedAt: Long?,
    val state: String
)
```

字段注释：

- `localBookId`：由 `AbsRemoteIdMapper` 生成的本地书籍 ID。
- `rootId`：对应 ABS library root，便于按 server/library 清理。
- `serverKey`：对应 ABS server 稳定标识。
- `remoteItemId`：ABS 原始 library item id。
- `lastSeenSyncRunId`：最后一次完整同步中看见该 item 的同步轮次。
- `lastSeenAt`：最后一次看见该 item 的本地时间。
- `remoteUpdatedAt`：ABS item 的远端更新时间，缺失时为空。
- `state`：远端镜像状态，建议使用 `ACTIVE`、`STALE`、`REMOTE_DELETED`。

设计注释：`BookEntity.status` 只承担 UI/播放可见性，`AbsItemMirrorEntity.state` 承担远端同步事实。不要把 ABS 同步轮次、远端更新时间和缺失确认逻辑都塞进 `books` 表。

入库规则：

- 远端新增：从完整清单集合差异中发现，生成稳定本地 ID，upsert `BookEntity`、`BookFileEntity`、`ChapterEntity`、`BookProgressEntity`，并把 mirror state 设为 `ACTIVE`。
- 远端更新：比较 `remoteUpdatedAt` 或详情内容签名，有变化才替换本地元数据、文件和章节。
- 重复 item：按 `remoteItemId/localBookId` 去重，重复 item 只会覆盖同一行。
- 单本详情失败：记录 item 错误，不阻断整轮同步；该 item 不进入删除确认。
- 完整清单失败：不执行远端删除收敛，只保留已成功 upsert 的新增或更新。

设计注释：同步器必须是幂等的。重复跑同一轮、重试同一批详情、或者详情请求部分失败，都不应该制造重复书籍或误删书籍。

### 远端新增处理

远端新增书籍在下一次完整清单同步或手动刷新时自然出现：

```text
ABS 新 item
  -> 完整清单发现 remoteItemId
  -> batch/get 拉取 expanded item
  -> AbsCatalogMapper 映射成本地 entity
  -> Room 事务 upsert
  -> 首页 Flow 自动刷新
```

新增书入库时建议：

- `BookEntity.status = READY`。
- `BookFileEntity.status = READY`。
- `AbsItemMirrorEntity.state = ACTIVE`。
- `BookEntity.addedAt` 首次入库时写本地时间；后续远端更新不要刷新 `addedAt`。
- `BookProgressEntity` 只在本地没有更新或远端更新更晚时覆盖。

设计注释：首页和搜索已经通过 Room `Flow` 消费 `books` 表。ABS 新增书只要写入同一套 entity，就会和 SAF/WebDAV 导入书一样出现，不需要单独给 UI 加远端列表。

### 远端删除处理

远端删除基于完整清单集合差异判断，但仍然不硬删。本地应使用两阶段确认：

```text
完整清单同步开始
  -> 创建 syncRunId
  -> 每见到一个 item，标记 lastSeenSyncRunId = syncRunId
完整清单成功返回并完成集合对比
  -> 找出同一 root 下本轮没 seen 的 ACTIVE item
  -> 标记为 STALE
下一轮完整清单仍缺失，或 GET /api/items/<itemId> 返回 404
  -> 标记为 REMOTE_DELETED
  -> BookEntity.status = DELETED
```

删除策略：

- `STALE`：不立刻从 UI 删除，可选择继续显示为暂不可确认，或在下一轮确认前保持原状态。
- `REMOTE_DELETED`：把 `BookEntity.status` 改为 `DELETED`，现有列表查询会自动隐藏。
- 不物理删除 `BookEntity`、`BookProgressEntity` 和 `BookmarkEntity`。
- 封面缓存可延迟清理，例如保留 7 到 30 天，或只在用户执行清理时删除。
- 如果之后远端同一 `remoteItemId` 又出现，则把 mirror state 设回 `ACTIVE`，并把 `BookEntity.status` 恢复为 `READY`。

设计注释：当前 `BookDao` 的列表查询已经过滤 `status = DELETED`。ABS 远端删除只要落到这个软删除语义，就能和现有首页、搜索、最近添加逻辑相容，同时保留进度和书签恢复空间。

不要做的事：

- 不要在分页过程中边翻页边删除本地未见 item。
- 不要在首期实现分页删除逻辑。
- 不要把远端删除映射为 `BookFileEntity.status = MISSING`。`MISSING` 是文件可达性语义，远端 catalog 删除是同步事实。
- 不要让 `MissingBookFileRecoveryChecker` 处理 ABS 删除。
- 不要在完整清单请求失败时执行删除收敛。

设计注释：完整清单减少了分页漂移，但仍不是数据库事务快照。网络错误、权限变化、server 正在扫描、用户远端编辑都可能让某次同步缺项。删除必须比新增更保守。

### 后续增量策略

- 使用 ABS item 更新时间或 library scan 时间判断是否需要刷新。
- 如果字段不稳定，则退回“完整轻量清单 + 变更 item 拉详情”。
- 后续可接 Socket.io 的 item 更新事件，但不作为首期依赖。
- 删除远端条目时，本地可先标记为不可用或从 ABS root 下软删除。

设计注释：增量同步应是 catalog mirror 的优化，不是首期正确性的前提。首期全量同步跑通后，再增加增量可以降低实现风险。

## 播放策略

`AbsSourceProvider` 需要实现以下能力：

- 用 `contentUrl` 构造 ABS 媒体请求；`/api/...` 这种路径必须拼到用户配置的 ABS baseUrl 后面。
- 给请求附加 Bearer token。
- 支持 `Range: bytes=<offset>-`。
- 从 `Content-Length` 和 `Content-Range` 推导可读长度。
- 401 或 403 返回授权失效状态。
- 404 返回远端文件缺失状态。
- 5xx 和 timeout 返回临时网络错误。
- 不把 token 写入日志、URI 或异常消息。

URL 解析规则：

```text
configuredBaseUrl = https://host.example/audiobookshelf
contentUrl        = /api/items/<itemId>/file/<ino>
requestUrl        = https://host.example/audiobookshelf/api/items/<itemId>/file/<ino>
```

实现建议：

- 规范化 baseUrl 时去掉结尾 `/`，但保留部署子路径，例如 `/audiobookshelf`。
- `contentUrl` 如果以 `/api/` 开头，则拼接到规范化 baseUrl 后。
- `contentUrl` 如果是完整 URL，则仅允许同一个 ABS server host/basePath 下的地址，避免把 Bearer token 发到外部域名。
- 播放前可用 HEAD 做可达性和 Range 能力检查，但真正播放只由 Media3 数据源发起 GET/Range。

设计注释：现有播放器只需要 `BookFileEntity.id -> open(offset)`。ABS 只要在 provider 层满足这个契约，就不需要侵入 Media3 层。浏览器验证中音频 HEAD 返回 `Accept-Ranges: bytes` 和明确 `Content-Length`，但实现仍要兼容代理不返回长度的情况。

## 进度同步策略

本地进度：

- 播放时继续由现有 `ProgressSyncTracker` 和 `ProgressService` 保存。
- 单位继续使用毫秒。
- 继续更新 `BookProgressEntity` 和 read status。

远端同步：

- ABS session 打开后记录 sessionId。
- 每约 10 秒把 `globalPositionMs / 1000.0` 转成 ABS `currentTime`。
- pause、stop、切书、退出时关闭 session。
- 网络失败时保存 pending sync。
- 下次联网后用 ABS local session 接口补偿，或者重新打开 session 后同步最新位置。
- `syncSession()` 和 `closeSession()` 对 2xx 即视为成功，不解析 Playback Session JSON。

冲突策略：

- 播放中本地优先。
- 非播放状态下，如果 ABS 远端更新时间晚于本地，则用远端覆盖本地。
- 如果本地更新时间晚于 ABS，则保持本地并等待下一次远端同步。
- 如果两边更新时间不可比较，则保守保留本地，避免用户当前进度后退。

设计注释：进度冲突一定要独立成策略类，例如 `AbsProgressConflictResolver`。不要把判断散落在同步器和播放器里。Chrome DevTools 验证中 `sync` 和 `close` 的响应体是短文本 `OK`，如果实现强行按 JSON DTO 解析，会把成功请求误判为失败。

## Podcast 处理

首期明确排除 podcast：

- ABS book 的播放对象就是 library item。
- ABS podcast 的 library item 是 show/container，真正播放对象是 episode。
- Podcast episode 的进度、封面、发布时间、订阅状态与 book 不同。
- 如果强行把 podcast 当 book，会污染本地 `BookEntity` 语义。

后续可选方案：

- episode-as-book：每个 episode 映射为一本本地书，show 只作为分组信息。
- show-as-series：show 映射为 series 或 collection，episode 映射为条目。

设计注释：先做 book 可以让 `BookPlaybackPlan` 和 `BookFileEntity` 的语义保持稳定。Podcast 支持应作为第二条产品线设计。

## 设置页最小入口

建议新增“在线后端”或“ABS Server”设置入口：

- 添加 server。
- 测试连接。
- 登录或输入 API token。
- 选择 book library。
- 手动同步。
- 首次同步等待弹窗。
- 查看最近同步状态和错误。
- 删除 server 或断开 library。

设计注释：设置页只做账号和同步操作入口。同步弹窗展示阶段和数量进度，不展示请求队列、DTO 或远端协议细节。

## 分阶段实施

### 阶段 1：基础模型与 API client

- 新增 ABS schema 常量。
- 新增 `LibrarySourceKind.ABS` 并接入 `LibrarySourceProviderFactory`、`AvailabilityChecker`、`LibraryRootService` 等按 source kind 分支的位置；当前 `LibrarySourceKind.from()` 对未知值会回退到 SAF，不能先写入 ABS root 再补 provider。
- 新增 ABS 凭据模型和存储接口。
- 新增 `AbsApiClient` 和 DTO。
- 使用 MockWebServer 覆盖登录、`POST /api/authorize`、获取 libraries、全量 minified 清单、`batch/get` 的 `{ libraryItems: [...] }` 包装响应、获取 item detail、错误码。

验收标准：

- 能连接测试 ABS server。
- 能列出 book libraries。
- `GET /api/authorize` 不作为有效接口使用；测试中应确认只有 `POST /api/authorize` 被调用。
- 不写入任何书籍数据。

### 阶段 2：Catalog mirror

- 新增 `AbsRemoteIdMapper`。
- 新增 `AbsCatalogMapper`。
- 新增 `AbsCatalogSynchronizer`。
- 写入 `LibraryRootEntity`、`BookEntity`、`BookFileEntity`、`ChapterEntity`、`BookProgressEntity`。
- 封面先可延后到阶段 3。

验收标准：

- 同步完成后首页能看到 ABS book。
- 详情页能看到标题、作者、章节、时长。
- 不需要播放器改动。

### 阶段 3：封面和播放

- 新增封面下载与缓存。
- 新增 `AbsSourceProvider`。
- 把 ABS provider 接入现有 VFS provider factory。
- 验证 Range streaming 和 seek。
- 验证带子路径 baseUrl 下的 `contentUrl` 解析，例如 `<baseUrl>/api/items/<itemId>/file/<ino>`。
- 让 ABS catalog 同步优先写入本地封面缓存路径，避免现有 `BookQueryService.checkCovers()` 在列表 Flow 收集时触发 `CoverRecoveryHelper`，进而对 ABS 远端音频做嵌入封面解析或 sidecar 枚举。

验收标准：

- ABS book 可以播放。
- seek 能触发 Range 请求。
- 封面缓存支持 `image/jpeg` 和 `image/webp`。
- token 不出现在 URI、日志或异常文案中。
- 首页和详情页展示 ABS 书籍时，不会因为封面缓存缺失而自动读取远端整段音频做封面自愈。

### 阶段 4：远端进度同步

- 新增 `AbsPlaybackSessionSyncer`。
- 播放 ABS book 时打开 session。
- 播放中定时 sync。
- pause、stop、切书时 close。
- 网络失败时保留 pending sync。
- sync/close 返回短文本、空体或 JSON 时都按 HTTP 2xx 判定成功。

验收标准：

- 本地播放进度仍正常保存。
- ABS web 端能看到同步后的播放进度。
- 断网播放不会丢本地进度。
- `sync` 和 `close` 返回 `OK` 时不会被 JSON 解析失败误判为同步失败。

### 阶段 5：增量同步与体验完善

- 增加增量刷新。
- 增加同步失败重试。
- 增加设置页同步状态。
- 增加 server version 兼容处理。
- 后续再评估 Socket.io 和 podcast。

验收标准：

- 大库同步耗时可控。
- 单本失败可见且可重试。
- 用户能安全移除 ABS server。

## 测试计划

- Mapper 单元测试：DTO 到 entity 的字段、单位、ID、章节归属。
- Progress 冲突测试：本地更新、远端更新、时间相等、时间缺失。
- API client 测试：`POST /api/authorize`、401、403、404、5xx、JSON 缺字段、`GET /api/me/progress/<itemId>` 404 映射为无进度。
- API client 测试：`POST /api/items/batch/get` 使用 `libraryItemIds`，`sync/close` 对短文本 `OK` 返回体只看 2xx。
- URL 解析测试：baseUrl 带子路径时，`/api/items/<itemId>/file/<ino>` 解析为 `<baseUrl>/api/items/<itemId>/file/<ino>`。
- 封面缓存测试：`image/jpeg`、`image/webp`、缺失 `Content-Length` 都能处理。
- Range streaming 测试：0 offset、非 0 offset、`Content-Range`、服务器不返回长度、`Accept-Ranges: bytes`。
- Room migration 测试：新增 schema 常量和 sync state 表。
- 手动验收：添加 server、选库、同步、播放、seek、断网、恢复、进度同步。

设计注释：最容易出错的是单位转换和 ID 稳定性。测试应优先覆盖这两类问题，而不是只验证接口能请求成功。

## 主要风险

1. JSON 库选择：当前项目已有 OkHttp，但没有明显的结构化 JSON 层。正式实现应先定解析库。
2. Token 安全：不能把 token 写入 URL、日志、Room 普通字段或 UI 状态。
3. 明文 HTTP：ABS 常部署在局域网 HTTP。默认建议 HTTPS；允许 HTTP 时要复用现有明文网络策略。
4. 大库同步：第一版统一全量 minified 清单；详情必须分批、限并发、可取消，并用首次同步弹窗承接等待成本。
5. ABS 版本差异：DTO 字段要允许缺失，server version 要记录。
6. 播放 Range 兼容：部分反向代理可能破坏 Range，需要明确错误提示。
7. 进度冲突：必须集中策略处理，避免同步覆盖用户刚播放的位置。
8. 源类型膨胀：不要让 `LibraryFacade`、`ProgressService` 或 `PlaybackManager` 变成 ABS 特判集合。
9. Source kind 回退风险：当前本地 `LibrarySourceKind.from()` 对未知 sourceType 会回退到 SAF。新增 ABS 时必须先补齐 enum 和 factory 分支，否则 ABS root 会被错误当成本地 SAF 根处理。
10. 封面自愈误触发风险：当前 `BookQueryService` 在列表 Flow 中会检查封面缓存并触发 `CoverRecoveryHelper`。ABS 书籍如果没有先写入本地封面缓存，可能在 UI 展示阶段反向读取远端音频尝试解析嵌入封面，违背 catalog mirror 的轻量同步目标。

## 修正说明

本次修正基于 2026-06-01 的本地代码核对和 Chrome DevTools MCP 对 `https://audiobooks.dev/audiobookshelf` demo 环境的在线验证。修正目标是让文档中的 API 形态、Room 字段和实现阶段边界与当前事实一致。

- 修正 batch detail 响应形态：`POST /api/items/batch/get` 的响应是 `{ libraryItems: [...] }` 包装对象，DTO 和测试不能按裸数组解析。
- 修正音轨字段来源：demo 2.35.1 的详情接口以 `media.tracks[].contentUrl` 作为实际播放入口，`media.audioFiles[]` 不一定带 `contentUrl`，`audioTracks[]` 只作为旧字段或 `/play` session 返回兼容。
- 修正 `BookFileEntity` 字段名：当前实体使用 `index` 表示播放顺序，文档不再使用不存在的 `sortOrder`。
- 补充 Range 实测：`HEAD <track.contentUrl>` 返回 `Accept-Ranges: bytes`，`GET <track.contentUrl>` 携带 `Range: bytes=0-15` 返回 206 和 `Content-Range`，因此 provider 方案可以支撑 seek。
- 补充 source kind 风险：当前未知 `LibrarySourceKind` 会回退 SAF，阶段 1 必须先补 ABS enum 和 factory 分支，避免 ABS root 走错 provider。
- 补充封面自愈风险：当前列表 Flow 会触发 `CoverRecoveryHelper` 检查缺失封面，ABS 同步应优先写入本地 cover cache，避免 UI 展示阶段误触发远端音频封面解析。

## 第一版建议切片

第一版只做：

- 一个 ABS server。
- 一个或多个 book library。
- 只读 catalog 同步。
- 本地封面缓存。
- ABS `media.tracks[].contentUrl` streaming。
- 本地进度保存。
- ABS 远端进度同步。

第一版不做：

- podcast。
- Socket.io。
- 元数据回写。
- 远端删除。
- 多用户切换。
- 离线下载。

设计注释：这个切片能验证方案 B 的核心价值，也能最大限度复用当前架构。等 book 链路稳定后，再扩展 podcast、实时同步和双向编辑。
