# 封面 Bitmap 与缓存命中优化计划

## 背景

当前封面链路已经具备部分内存保护能力：导入侧会把内嵌封面或外部封面落盘，再生成缩略图；主色提取也会通过小尺寸 Bitmap 运行 Palette，避免直接解码原图。问题主要集中在展示层和远端封面同步层：多个 UI 入口各自构造 `ImageRequest`，缓存 key、解码尺寸、原图与缩略图选择策略分散，导致 Coil 内存缓存命中不稳定，也容易产生重复 Bitmap。

本计划只收口图片加载与缓存策略，不把封面、导入、播放、ABS 同步合并成大而全的管理器。新增组件保持小职责：日志只记录事实，请求工厂只生成请求，路径选择只表达场景规则。

## 当前事实

- 执行前，`PlayerCover`、`CoverBackground`、首页列表、最近播放卡片、迷你播放器都手写 `ImageRequest`；当前已收口到统一请求工厂，后续只需要继续补齐遗漏入口。
- 列表和迷你播放器多处使用 `180x180`，最近播放使用 `360x360`，模糊背景使用 `128x128`，主封面当前计划暂定为 `1200x1200`。
- 固定 `800x800` 不适合作为当前主封面标准。Android 官方建议按实际显示需求加载合适尺寸，避免把超过显示需求的 Bitmap 放入内存；本计划先按项目决策把主封面统一收口到 `1200x1200`，后续再通过实测决定是否升级为自适应 bucket。
- `ImageProcessor.DEFAULT_THUMBNAIL_MAX_SIZE` 执行前为 `300`，与 UI 中 `360x360` 的中等缩略图规格不一致；本轮实现已提升到 `360`。
- `AbsCoverCache` 执行前通过 `response.body.bytes()` 一次性把远端封面读入内存，再包装为 `ByteArrayInputStream` 交给图片处理链路；本轮实现已改为直接使用 `response.body.byteStream()` 交给本地处理链路，当前剩余问题主要是 ABS 封面更新后的失效戳刷新还没补齐。
- 桌面 widget 当前先作为独立事项保留 TODO，不纳入本轮主 UI 封面缓存收口。

## 官方依据

- Android Bitmap 优化建议：按目标显示尺寸加载缩放后的图片，避免不必要的大 Bitmap 常驻内存。
  - https://developer.android.com/develop/ui/compose/graphics/images/optimization
- 手写 Bitmap 解码时可通过 `BitmapFactory.Options.inSampleSize` 降低解码尺寸。
  - https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize

## 目标

- 降低封面 Bitmap 峰值内存。
- 提高同一场景、同一尺寸下的 Coil memory cache 命中率。
- 避免不同尺寸场景互相污染缓存，例如列表误用主封面大 Bitmap，或主封面误用低清缩略图。
- 让 ABS 批量封面同步不再把远端封面整包读入堆内存。
- 让日志事实集中在 `logger` 目录，后续问题能通过统一日志定位，而不是从 UI 文件散查。

## 非目标

- 不重做导入架构。
- 不把封面缓存、封面自愈、ABS 下载、UI 展示合并成上帝类。
- 不在本轮实现 widget 封面缓存优化。
- 不全量重建用户已有封面缩略图，避免启动或扫描时产生大量磁盘 I/O。

## 阶段 0：统一图片缓存日志

### 改动

新增 `app/src/main/java/com/viel/aplayer/logger/CoverImageCacheLogger.kt`，用于集中记录封面图片链路事件。

日志覆盖：

- 请求开始：场景、variant、路径 hash、更新时间戳、目标尺寸 bucket。
- 命中结果：memory cache hit、disk cache hit、network/file decode。
- 请求成功：实际解码尺寸、数据来源、耗时。
- 请求失败：异常类型、路径 hash、variant。
- ABS 封面处理：下载开始、下载完成、落盘大小、缩略图生成结果。

### 约束

- UI 层不直接散落 `Log.e("ListItem"... )`、`Log.e("MainCoverView"... )` 这类图片加载日志。
- Coil `EventListener` 或事件桥接类也放在 `logger` 目录下。
- 日志内不输出完整本地路径和完整远端 ID，避免日志泄漏用户目录结构或服务端标识；使用短 hash 或脱敏片段。

### 验收

- 首页列表快速滚动、最近播放、详情页、播放器页、迷你播放器、ABS 批量同步都能看到统一格式日志。
- 日志可以判断一次图片展示来自 memory cache、disk cache 还是重新 decode。

## 阶段 1：封面请求规格收口

### 改动

新增小职责请求构造层：

- `CoverImageVariant`：定义封面展示规格。
- `CoverImageRequestFactory`：根据路径、更新时间戳、variant、目标像素生成 Coil `ImageRequest`。

建议 variant：

| Variant | 目标用途 | 解码规格 |
| --- | --- | --- |
| `ThumbnailSmall` | 首页列表、迷你播放器、搜索结果小图 | `180x180` |
| `ThumbnailMedium` | 最近播放卡片、相关推荐中等卡片 | `360x360` |
| `Backdrop` | 高斯模糊背景、氛围背景 | `128x128` |
| `Main1200` | 播放器主封面、详情主封面 | `1200x1200` |

### Main1200 规则

- 不再固定写死 `800x800`。
- 本轮主封面统一暂定为 `1200x1200`，作为清晰度、内存占用、缓存共用之间的折中值。
- `Main1200` 不启用 crossfade，避免封面切换或页面重组期间额外同时持有旧主图和新主图。
- 不按每一次布局结果生成任意尺寸请求，避免同一封面因为细微布局差异产生多个主封面缓存变体。
- 如果后续实测发现大屏或高密度设备上 `1200x1200` 不够清晰，再基于日志和截图证据升级为有限自适应 bucket。

### Cache Key 规则

统一格式：

```text
cover:<variantBucket>:<normalizedPathHash>:<lastUpdated>
```

示例：

```text
cover:small180:a1b2c3d4:1717000000000
cover:main1200:a1b2c3d4:1717000000000
cover:backdrop128:a1b2c3d4:1717000000000
```

### 验收

- `PlayerCover`、`CoverBackground`、首页列表、最近播放卡片、迷你播放器不再直接手写 `.memoryCacheKey()`、`.diskCacheKey()`、`.size()`。
- 同一 variant、同一路径、同一更新时间戳得到同一 key。
- 不同 variant 不共用 key，避免不同清晰度互相污染。

## 阶段 2：路径选择策略

### 改动

新增轻量路径选择函数，例如 `CoverImageSourceSelector`，只做场景到路径的选择，不创建请求，不访问数据库。

建议规则：

| 场景 | 路径优先级 |
| --- | --- |
| 首页列表 | `thumbnailPath ?: coverPath` |
| 搜索结果 | `thumbnailPath ?: coverPath` |
| 最近播放 | `thumbnailPath ?: coverPath` |
| 相关推荐 | `thumbnailPath ?: coverPath` |
| 迷你播放器 | `thumbnailPath ?: coverPath` |
| 详情主封面 | `coverPath ?: thumbnailPath` |
| 播放器主封面 | `coverPath ?: thumbnailPath` |
| 模糊背景 | `thumbnailPath ?: coverPath` |

`Backdrop` 同样不启用 crossfade。背景图只作为模糊采样源，切换过渡由页面背景色和遮罩承担，避免无意义地保留两张背景采样 Bitmap。

本轮已将首页列表、最近播放、相关书籍、迷你播放器、详情页背景、播放页背景、详情页主封面、播放页主封面接入 `CoverImageSourceSelector`。小图入口统一走缩略图优先，主封面入口统一走原图优先，背景入口统一走缩略图优先；相关书籍列表同时补传 `lastScannedAt`，避免封面重建后继续使用默认 `0` 时间戳命中旧缓存。

### 验收

- 小图场景不会优先拿原图。
- 主封面在有原图时保持清晰度，但仍通过 `Main1200` 限制解码尺寸。
- 背景模糊始终走低分辨率路径，避免为了 64dp blur 解码高清 Bitmap。

## 阶段 3：全局 Coil 配置

### 改动

在 Application 或启动容器层配置单一 `ImageLoader`。

配置内容：

- 明确 memory cache 大小比例。
- 明确 disk cache 目录与大小。
- 绑定统一 logger 或 event listener。
- 默认允许 hardware bitmap，提高常规 UI 图片渲染效率。
- 对需要软件处理的路径显式 `allowHardware(false)`。

需要 `allowHardware(false)` 的场景：

- 高斯模糊背景需要软件层处理时。
- 后续 widget 需要 Canvas 合成时。
- 任何会把 Bitmap 交给软件 Canvas、Palette 之外的手工绘制链路时。

### 验收

- 全 App 使用同一个 Coil 缓存池。
- 日志可以看到跨页面缓存命中。
- 常规图片不因为全局禁用 hardware bitmap 损失渲染性能。

## 阶段 4：ABS 封面下载降峰值

### 改动

调整 `AbsCoverCache`：

- 不再使用 `response.body.bytes()`。
- 使用 `response.body.byteStream()` 直接交给本地落盘链路。
- 下载日志中的字节数优先取 `Content-Length`，处理完成后再用落盘文件长度校正。

建议让 `ImageProcessor.processExternalImage()` 支持返回原图文件大小，或在 ABS 层通过结果路径读取文件长度。

### 验收

- ABS 批量同步时不会同时持有完整封面 ByteArray 和解码 Bitmap。
- 下载失败、处理失败、缩略图生成失败仍能被统一日志定位。
- MIME 不硬编码为 jpeg，继续按实际字节交给解码链路。

## 阶段 5：缓存失效与命中规则

### 改动

保留 `lastUpdated` 作为强失效信号。

增强规则：

- 同一路径、同一更新时间、同一 variant bucket 必须命中同一 key。
- 同一路径、同一更新时间、不同 variant bucket 必须隔离。
- 自定义封面、封面自愈、ABS 重新下载成功后必须更新 `lastScannedAt` 或等价更新时间戳。

### 验收

- 首页列表与迷你播放器同为 `ThumbnailSmall` 时可共享内存缓存。
- 播放器页与详情页同为 `Main1200` 时可共享内存缓存。
- 封面重建后不会继续显示旧图。

## 阶段 6：缩略图提升到 360

### 改动

将 `ImageProcessor.DEFAULT_THUMBNAIL_MAX_SIZE` 从 `300` 提升到 `360`。

影响范围：

- 内嵌封面生成缩略图。
- 自定义封面生成缩略图。
- sidecar 封面生成缩略图。
- ABS 封面生成缩略图。

保留规则：

- 主色提取继续使用约 `100x100` 的下采样 Bitmap，不提升到 360。
- 旧的 300 缩略图不做启动期全量迁移。
- 后续封面重建、自定义封面保存、ABS 重新同步时自然产出 360 缩略图。

### 验收

- `ThumbnailMedium=360` 可优先从本地缩略图解码，不需要回源到原图。
- 最近播放卡片、相关推荐卡片在 360 规格下清晰度稳定。
- 主色提取耗时和内存不明显上升。

## 阶段 7：封面自愈触发降噪

### 改动

`BookQueryService.checkCovers()` 继续保留，但增加轻量存在性检查降噪。

建议策略：

- 以 `bookId + coverPath + thumbnailPath + lastScannedAt` 为 key 缓存短时间检查结果。
- 同一本书在短窗口内不重复 `File.exists()`。
- 已经进入 `pendingRegenerations` 的书不重复触发。
- ABS_REMOTE 继续跳过本地音频封面自愈。

### 验收

- 大列表滚动不会持续触发重复文件存在性检查。
- 缺失封面仍能后台恢复。
- ABS 远端书籍不会被 UI 展示反向触发远端音频封面解析。

## 阶段 8：Widget TODO

### 状态

暂不实现 widget 封面优化。

### TODO

后续单独做 widget 封面链路：

- 使用 widget 专用 `120x120` 或接近实际 RemoteViews/Glance 显示尺寸的预生成图。
- 不复用主 UI 的 `Main1200` 大 Bitmap。
- 如需要 Canvas 合成，显式 `allowHardware(false)`，并处理 immutable bitmap。
- 独立评估 widget 更新频率和跨进程传输大小，避免 `TransactionTooLargeException`。

## 未完成部分补充：观测与收口缺口

### 所属阶段

本节归入阶段 0、阶段 3、阶段 5、阶段 7 的未完成部分，不新增独立管理器。指标采集仍由 `CoverImageCacheLogger`、Coil 事件桥接、现有请求工厂和封面自愈链路协作完成，避免把日志、请求构造、路径选择、ABS 同步继续耦合成一个大类。

### 当前缺口

- 本轮主 UI 封面缓存收口只剩 widget 独立事项未做；阶段 0、阶段 3、阶段 5、阶段 7 的主链代码收尾已经完成。
- 后续若继续扩展观测能力，重点不再是“补齐缺口”，而是是否要把 `scene` 聚合统计、命中率统计或更细颗粒度指标继续沉淀成调试工具。

### 当前阶段状态

| 阶段 | 状态 | 当前代码依据 | 仍需收尾 |
| --- | --- | --- | --- |
| 阶段 0：统一图片缓存日志 | 已完成 | `logger/CoverImageCacheLogger.kt` 已统一封面日志口径，`logger/CoverImageCoilEventListener.kt` 已补齐全局 `EventListener` 桥接 | 无新增收尾项 |
| 阶段 1：封面请求规格收口 | 已完成 | `ui/common/CoverImageRequestFactory.kt` 已统一 `variant`、`size`、`memoryCacheKey`、`diskCacheKey`、`crossfade` | 无新增收尾项 |
| 阶段 2：路径选择策略 | 已完成 | `ui/common/CoverImageSourceSelector.kt` 已被首页、最近播放、相关书籍、详情主封面、播放主封面、背景、迷你播放器接入 | 无新增收尾项 |
| 阶段 3：全局 Coil 配置 | 已完成 | `APlayerApplication.newImageLoader()` 已提供单一 `ImageLoader`、memory cache、disk cache，并绑定全局封面 `EventListener` | 无新增收尾项 |
| 阶段 4：ABS 封面下载降峰值 | 已完成 | `abs/sync/AbsCoverCache.kt` 已改为 `response.body.byteStream()` 直通 `processExternalImage()` | 无新增收尾项 |
| 阶段 5：缓存失效与命中规则 | 已完成 | UI 请求 key 已包含 `variant + hash + lastUpdated`，本地自愈、自定义封面保存和 ABS 封面路径变化都会刷新 `lastScannedAt` | 无新增收尾项 |
| 阶段 6：缩略图提升到 360 | 已完成 | `media/parser/ImageProcessor.kt` 中 `DEFAULT_THUMBNAIL_MAX_SIZE` 已提升到 `360` | 无新增收尾项 |
| 阶段 7：封面自愈触发降噪 | 已完成 | `CoverRecoveryHelper` 已具备 `pendingRegenerations`、`alreadyAttempted`、`ABS_REMOTE` 跳过逻辑，并补上短窗口存在性检查缓存 | 无新增收尾项 |
| 阶段 8：Widget TODO | 未开始 | `widget/PlayerWidget.kt` 仍显式保留 TODO | 继续独立排期 |

### 剩余收尾的文件级落点

| 未完成项 | 建议改动文件 | 需要补的内容 | 完成判定 |
| --- | --- | --- | --- |
| Widget 封面链路 | `app/src/main/java/com/viel/aplayer/widget/PlayerWidget.kt` | 单独设计 widget 专用尺寸、软件位图策略和跨进程传输大小控制 | 不复用主 UI 的 `Main1200`，并能独立验证 RemoteViews/Glance 更新成本 |

### 收尾实现细则

1. 阶段 0 + 阶段 3 一起收尾：
   - 不再给每个页面单独追加日志字段。
   - 统一由 `CoverImageRequestFactory` 记录 request 起点，由全局 Coil 事件桥接补齐结束态和命中来源。
   - 这样可以保证“同一个 key 在不同页面里命中了谁”的统计口径一致。

2. 阶段 5 收尾以“路径变化才刷新戳”为准：
   - 不能每轮 ABS 同步都无条件刷新 `lastScannedAt`，否则会把未变化封面的缓存全部打穿。
   - 只在 `coverPath` 或 `thumbnailPath` 发生变化时刷新失效戳，才能保持缓存稳定和新图可见两边都成立。

3. 阶段 7 收尾只做轻量内存缓存：
   - 不需要数据库表，也不需要新的后台任务。
   - 缓存 TTL 建议控制在秒级短窗口，目标是压掉列表快速重组时的重复 `File.exists()`，不是建立新的持久层。

### 本文档收口后的实际剩余范围

- 已完成并可视为收口结束：阶段 0、阶段 1、阶段 2、阶段 3、阶段 4、阶段 5、阶段 6、阶段 7。
- 明确延后，不并入本轮：阶段 8 widget。

### 待补指标

| 指标 | 含义 | 建议来源 | 用途 |
| --- | --- | --- | --- |
| `decodeCostMs` | 单次封面请求从开始到成功或失败的耗时 | `CoverImageRequestFactory` 写入 request start time，Coil success/error 回调或 `EventListener` 结算 | 判断列表滚动、主封面切换、背景模糊是否仍存在重复 decode 峰值 |
| `bitmapByteCount` | 成功解码后实际 Bitmap 占用字节数 | success drawable 转 Bitmap 后读取 `allocationByteCount` 或可安全获得的等价尺寸估算 | 验证 `Main1200`、`ThumbnailMedium`、`Backdrop` 是否按预期限制内存 |
| `cacheHitRatio` | 按场景和 variant 聚合的缓存命中率 | Coil `dataSource`、memory/disk/network/file decode 结果聚合 | 验证同一 variant、同一路径、同一 `lastUpdated` 是否真正复用缓存 |
| `decodeSource` | 单次请求来自 memory cache、disk cache、file decode 或 network | Coil `ImageResult.dataSource` 和后续全局 `EventListener` | 定位跨页面是否共享同一个 ImageLoader 缓存池 |
| `sourceKeyHash` | 脱敏后的路径或远端 ID 指纹 | 继续复用 `CoverImageCacheLogger.hashSource()` | 在不暴露用户路径的前提下串联一次请求的开始、成功、失败和 ABS 下载事件 |

### 验收补充

- 首页列表、最近播放、详情主封面、播放主封面、背景模糊、迷你播放器都能输出 `scene + variant + sourceKeyHash + decodeCostMs + bitmapByteCount + decodeSource`。
- 日志能按 `scene + variant` 计算 `cacheHitRatio`，至少区分 memory hit、disk hit、重新 file decode。
- `Main1200` 的 `bitmapByteCount` 不被小图场景复用，小图场景也不误持有主封面大 Bitmap。
- ABS 批量封面同步日志继续记录下载和落盘事实；UI 展示日志只消费本地缓存路径，不把远端 item ID 原文写入日志。
- 指标聚合只做轻量内存计数或调试日志输出，不在 UI 重组路径同步访问磁盘，也不引入持久化统计表。

## 建议实施顺序

1. 阶段 0：先建立统一日志，否则后续无法判断优化是否真实命中。
2. 阶段 1 和阶段 2：收口请求构造与路径选择，是 UI 缓存命中提升的核心。
3. 阶段 6：把缩略图提升到 360，让中等卡片不再被迫从原图生成。
4. 阶段 4：修 ABS 远端封面下载峰值，避免批量同步时堆内存抖动。
5. 阶段 3 和阶段 5：统一 Coil 与失效规则，完成缓存池层面的稳定化。
6. 阶段 7：在缓存规则稳定后降低自愈检查噪音。
7. 阶段 8：widget 暂留 TODO，独立排期。

## 验证清单

- 首页列表快速滚动 30 秒，观察重复 decode 次数是否下降。
- 最近播放横向滑动，确认 360 缩略图命中。
- 进入详情页再进入播放器页，确认 `Main1200` 主封面可以复用。
- 切换封面或封面自愈后，确认 `lastUpdated` 变化能打破旧缓存。
- 开启模糊背景，确认背景只使用 `Backdrop=128`。
- ABS 批量同步多本书，观察堆内存峰值和 GC 次数。
- 运行 `.\gradlew.bat compileDebugKotlin` 验证编译。
