# Audiobookshelf Server 可选在线后端可行性分析

日期：2026-06-01

## 结论

可行，但不建议把 Audiobookshelf 的 REST API 直接实现成一套替代 `LibraryFacade` 的远程 gateway。更稳妥的方式是增加一个独立的 `ABS` 远程来源类型，把 ABS 的服务端目录同步成本地 Room 镜像，再复用现有的 UI 查询、播放计划、进度模型和 VFS 播放链路。

当前项目的架构已经给了接入点：`LibraryFacade` 只聚合 `BookQueryGateway`、`ProgressGateway`、`LibraryRootGateway` 等细粒度接口；播放层通过 `BookPlaybackPlan`、`BookFileEntity`、`VfsPlaybackUri`、`VfsPlaybackDataSource` 和 `LibrarySourceProvider` 解耦了媒体来源。真正不契合的是导入路径：现有 `SourceInventoryScanner` 和 `ImportPipeline` 是文件系统扫描优先，而 ABS 已经提供整理后的书库、条目、音轨、章节、封面和用户进度，继续走本地扫描会重复 ABS 服务端已经完成的工作。

## 依据

代码依据：

- `app/src/main/java/com/viel/aplayer/data/LibraryFacade.kt`：当前 facade 只是委托聚合，不承担具体存储实现。
- `app/src/main/java/com/viel/aplayer/data/gateway/BookQueryGateway.kt`：上层需要的是本地书籍流、详情、章节、书签、播放计划。
- `app/src/main/java/com/viel/aplayer/data/gateway/ProgressGateway.kt`：播放进度以本地毫秒级位置为核心。
- `app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/LibrarySourceProvider.kt`：SAF/WebDAV 已经通过 provider 抽象隔离来源。
- `app/src/main/java/com/viel/aplayer/media/VfsPlaybackDataSource.kt`：播放热路径只关心 `BookFileEntity.id -> VFS open(offset)`。
- `app/src/main/java/com/viel/aplayer/library/orchestrator/ImportPipeline.kt`：现有导入流水线假设先拿到文件清单，再解析元数据。

接口依据：

- 官方 API 入口：`https://api.audiobookshelf.org/`
- 本地镜像：`docs/cc/Audiobookshelfdocs`
- ABS 使用 Bearer token；`POST /login` 返回 `user.token`，也可直接使用用户 API token。
- `GET /api/libraries` 返回用户可访问的书库。
- `GET /api/libraries/<ID>/items` 返回书库条目列表。
- `GET /api/items/<ID>?expanded=1&include=progress,authors` 返回条目详情、音轨、章节、封面路径和用户进度。
- `GET /api/items/<ID>/cover` 返回封面图片。
- `POST /api/items/<ID>/play` 创建播放会话，返回会话、音轨、当前位置和播放相关信息。
- `POST /api/session/<ID>/sync` 和 `POST /api/session/<ID>/close` 用于同步/关闭打开的播放会话。
- Socket.io 可推送 `item_updated`、`user_item_progress_updated`、`stream_progress` 等事件，但不是首期必需能力。

## 核心不匹配点

1. 数据入口不同。APlayer 现在从 SAF/WebDAV 文件树扫描，再本地解析 ID3/CUE/M3U8；ABS 提供的是服务端整理后的 catalog。把 ABS 伪装成普通目录会让客户端重复扫描和解析，收益低且慢。

2. 查询模型不同。APlayer 的首页、搜索、详情依赖 Room 的 `Flow<List<BookWithProgress>>`；ABS 是分页 REST 查询。直接远程实现每个 `BookQueryGateway` 方法会把网络状态、分页、缓存、错误重试扩散到 UI 层。

3. 播放模型不同。APlayer 播放计划依赖 `BookFileEntity` 的本地稳定 ID、`rootId`、`sourcePath`、`durationMs` 和 `fileSize`；ABS 的音轨是 `audioTracks[].contentUrl`，同时有播放会话语义。两者可以映射，但不适合直接替换。

4. 进度语义不同。APlayer 使用全书毫秒位置，并在本地事务里映射到具体音轨；ABS 使用秒级 `currentTime`、`progress`、播放会话 ID 和 `timeListened`。必须有专门同步器做单位换算和冲突处理。

5. 来源生命周期不同。SAF/WebDAV 根目录是“存储授权”；ABS 后端是“账号 + server + library”。需要新建账号/凭据边界，不能只把它塞进现有 WebDAV 凭据模型。

## 方案选项

### 方案 A：把 ABS 伪装成 VFS 文件树

做法：新增 `AbsSourceProvider`，让 ABS library/item/track 以目录和文件形式暴露给 `SourceInventoryScanner`，再复用 `ImportPipeline`。

优点：

- 对现有导入流水线改动较少。
- 播放层可以较快复用 VFS offset 读取。

问题：

- 会重复解析 ABS 已经整理好的音频元数据和章节。
- ABS 的 item、progress、cover、play session 很难自然落进文件树语义。
- 大书库扫描会变慢，且 REST 分页/详情请求容易被目录遍历放大。

判断：不推荐作为主方案，只可借鉴其播放流读取部分。

### 方案 B：ABS Catalog 同步到本地 Room，再复用现有架构

做法：新增 `ABS` 作为可选在线来源。登录/配置后，从 ABS REST API 拉取 library/item/detail，映射成现有 `BookEntity`、`BookFileEntity`、`ChapterEntity`、`BookProgressEntity`。播放时仍走 `BookPlaybackPlan -> VfsPlaybackUri -> DataSource`，但 `BookFileEntity.sourcePath` 指向 ABS track 的 `contentUrl`，由 `AbsSourceProvider` 用 Bearer token 和 Range 请求打开流。

优点：

- UI、搜索、详情、播放队列、最近播放基本继续复用 Room/Flow。
- REST 被隔离在同步器和 provider 内，不污染 ViewModel 和现有 facade。
- 可以离线展示已同步的目录、封面、进度，网络失败时状态可控。
- 与现有 `SAF`、`WEBDAV` 来源模式一致，扩展点清晰。

问题：

- 需要新增同步状态、远程 ID 映射、账号凭据和冲突策略。
- 服务端变更需要主动刷新或后续 Socket 增量同步。
- 用户在 APlayer 编辑元数据/书签/readStatus 时，需要明确是否回写 ABS；首期应只读或有限回写。

判断：推荐。

### 方案 C：完全远程 gateway，不落本地镜像

做法：新增一套 `AbsBookQueryGateway`、`AbsProgressGateway`，每个 UI 查询直接访问 ABS REST API。

优点：

- 数据实时性强，不需要同步表。
- 初看起来“REST 对 REST”，模型纯粹。

问题：

- 当前 UI 大量依赖 `Flow` 和 Room 语义，远程分页、重试、错误态会向上扩散。
- 播放计划、封面、进度、最近播放仍要做本地缓存，否则体验不稳定。
- 会出现本地/远程 gateway 在同一 facade 下行为差异过大。

判断：不推荐，除非未来重做成多后端实时客户端。

## 推荐架构

推荐新增一个 ABS 反腐层，边界如下：

1. `AbsCredentialStore`：保存 server baseUrl、token、用户名、用户 ID、默认 library ID。实现上可参考 WebDAV 凭据存储，但不要复用 WebDAV 的 Basic Auth 模型。

2. `AbsApiClient`：极薄的 OkHttp REST 客户端，统一处理 baseUrl、Bearer token、错误码、超时、JSON 解析。项目已有 OkHttp；如果正式实现，建议引入结构化 JSON 解析库，避免手写字符串解析。

3. `AbsCatalogSynchronizer`：负责把 ABS 的 library/item/detail/progress 映射到 Room。它不参与播放，不做 UI 状态。

4. `AbsSourceProvider`：实现 `LibrarySourceProvider` 的播放相关能力。它可以不支持目录扫描，但必须支持 `openInputStream(offset)` 和 `readRange(offset, length)`，用 ABS `contentUrl` 发起带鉴权的 GET/Range 请求。

5. `AbsPlaybackSessionSyncer`：监听播放生命周期。开始播放远程书籍时调用 `/api/items/<ID>/play` 创建 session；播放中按当前节奏同步 `/api/session/<ID>/sync`；暂停/切书/退出时调用 `/close`。如果离线或后台失败，保留本地进度，后续通过 `/api/session/local` 或 `/api/session/local-all` 补偿。

6. `AbsRemoteIdMapper`：集中定义本地 ID 规则，避免 ID 拼接散落。例如：
   - `LibraryRootEntity.id = abs:<serverKey>:<libraryId>`
   - `BookEntity.id = abs:<serverKey>:<libraryItemId>`
   - `BookFileEntity.id = abs:<serverKey>:<libraryItemId>:track:<index>`
   - `BookFileEntity.sourcePath = <contentUrl>`

## 数据映射建议

`LibraryRootEntity`：

- `sourceType` 新增 `ABS`。
- `sourceUri` 保存 ABS server baseUrl。
- `basePath` 保存 libraryId 或空值，建议另建配置表保存多 library 选择关系。
- `credentialId` 指向 `AbsCredentialStore`。

`BookEntity`：

- `id` 使用稳定远程映射 ID。
- `rootId` 指向 ABS library root。
- `sourceType` 可新增 `ABS_REMOTE`，避免与本地 `SINGLE_AUDIO/CUE/M3U8` 混淆。
- `title/author/narrator/description/year/totalDurationMs/totalFileSize` 来自 item media metadata。
- `coverPath/thumbnailPath` 使用本地封面缓存路径；缓存由 `/api/items/<ID>/cover` 生成。

`BookFileEntity`：

- 每个 ABS `audioTracks[]` 映射为一个 `AUDIO` 文件。
- `sourcePath` 使用 `contentUrl`。
- `sourceIdentity` 使用 `libraryItemId + track.index + contentUrl` 的稳定组合。
- `durationMs` 由 `audioTracks[].duration * 1000`。
- `fileSize` 优先使用 track metadata size，没有则为 0。
- `etag` 可预留 HTTP 响应头或 ABS 更新时间，用于后续增量判断。

`ChapterEntity`：

- ABS `media.chapters[]` 的 `start/end` 是秒，映射为毫秒。
- `bookFileId` 通过 `audioTracks[].startOffset/duration` 计算章节落在哪个 track。
- `source = ABS` 或复用新增章节来源常量。

`BookProgressEntity`：

- ABS `userMediaProgress.currentTime` 是秒，转换为 `globalPositionMs`。
- `isFinished` 映射为 `ReadStatus.FINISHED`。
- 本地播放时继续用现有事务更新；远程同步器异步回写 ABS。

## 分阶段实施

第一阶段：账号与只读同步。

- 新增 ABS server 配置入口：baseUrl、token 或用户名/密码登录。
- 调用 `/ping` 或 `/status` 做连通性检测。
- 调用 `/api/libraries` 展示可选书库。
- 对选中书库执行全量同步：`/api/libraries/<ID>/items` 拉列表，再按需拉 `/api/items/<ID>?expanded=1&include=progress,authors`。
- 只支持 `mediaType=book`，暂不接 podcast。

第二阶段：播放与封面。

- 新增 `AbsSourceProvider`，让 `BookFileEntity.sourcePath = contentUrl` 能被 VFS 打开。
- 支持 Bearer token、Range、超时、401/403/404 映射到现有可用性状态。
- 封面通过 `/api/items/<ID>/cover` 缓存到本地，复用现有 `coverPath`。
- 播放仍由 `BookPlaybackPlan` 和 Media3 执行，减少播放层改动。

第三阶段：进度同步。

- 播放 ABS 书籍时创建 ABS open session。
- 每 10 秒左右把当前 `globalPositionMs` 转成秒，调用 `/api/session/<ID>/sync`。
- 暂停、切书、退出时调用 `/api/session/<ID>/close`。
- 网络失败时保留本地进度，下次同步使用本地 session 补偿。

第四阶段：增量同步和冲突策略。

- 增加手动刷新和后台刷新。
- 后续可接 Socket.io：监听 `item_added/item_updated/item_removed` 和 `user_item_progress_updated`。
- 明确远程为主：ABS 同步项首期不允许本地编辑元数据回写，避免冲突。

第五阶段：扩展能力。

- podcast 支持。
- collection/series/tag 映射。
- 书签双向同步。
- 多 server、多账号。

## 风险与处理

1. REST 与 Flow 的差异：用 Room 镜像消解。UI 继续订阅 Room，REST 只在同步层出现。

2. 远程播放授权：ABS 的 `contentUrl` 必须由 `AbsSourceProvider` 统一补 Bearer token，不要把 token 拼进 URL 或暴露给 UI。

3. 明文 HTTP：项目已有明文网络配置和应用层开关。ABS server 可能是局域网 HTTP，应复用现有明文授权逻辑，默认建议 HTTPS。

4. 大书库同步：先做分页、限并发、可取消。详情请求不要在首页滚动时临时打爆服务端。

5. 服务端版本差异：登录响应中有 `serverSettings.version`，同步器应记录 serverVersion，并对缺字段做兼容。

6. 进度冲突：首期以“最近更新时间较新者优先”为准。播放中本地进度优先，启动同步时若 ABS 远端更新更晚，则提示或采用远端。

7. 现有导入流水线复用边界：不要让 ABS catalog 进入 `ImportPipeline`。ABS 同步器直接写 Room，避免重复解析服务端已给出的元数据。

8. 凭据安全：不要存用户名密码明文。登录后保存 token；后续再考虑 Android Keystore 加密。

## 最小可行切片

最小可行版本应只包含：

- 添加一个 ABS server。
- 选择一个 ABS book library。
- 同步该 library 的书籍、音轨、章节、封面、当前进度到本地。
- 首页/详情能看到这些书。
- 点击播放能通过 ABS `contentUrl` 串流。
- 播放进度能本地保存，并在有网络时同步到 ABS。

不包含：

- 元数据回写。
- 删除远程条目。
- 上传封面。
- 管理 ABS library。
- podcast。
- Socket 实时同步。

## 推荐下一步

先做一个只读 ABS 后端切片，不改 `LibraryFacade` 对外形态，只新增来源类型、ABS API 客户端、catalog 同步器、ABS VFS provider 和播放会话同步器。这样 REST 与当前架构的不契合会被限制在反腐层内，而不是扩散到 UI、播放服务或现有扫描流水线。
