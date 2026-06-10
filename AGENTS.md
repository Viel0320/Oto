# APlayer 项目架构文档

> Android 有声书播放器 - 支持本地存储（SAF）和远程服务器（Audiobookshelf）

## 项目概述

**技术栈**
- **语言**: Kotlin
- **最低 SDK**: 32 (Android 13)
- **目标 SDK**: 36
- **UI**: Jetpack Compose + Material 3
- **架构**: 分层架构 + 依赖注入
- **数据库**: Room
- **播放引擎**: Media3 ExoPlayer
- **图片加载**: Coil
- **网络**: OkHttp + Moshi
- **异步**: Kotlin Coroutines + Flow

**核心功能**
1. 本地有声书导入与扫描（支持 CUE、M3U8、单文件音频）
2. 远程 Audiobookshelf 服务器同步
3. 播放控制（自动倒带、书签、进度同步）
4. 元数据解析（内嵌章节、封面提取）
5. 搜索与分类管理
6. 多语言支持（i18n）

---

## 架构分层

```
┌─────────────────────────────────────────────────────────┐
│                     UI Layer (ui/)                      │
│  Compose Screens + ViewModels + Navigation             │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│              Application Layer (application/)           │
│  Use Cases + Commands + Read Models                     │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│           Data Layer (data/ + library/ + media/)        │
│  Gateways + Services + DAOs + Entities                  │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│          Infrastructure (abs/ + network/ + vfs/)        │
│  Remote APIs + Virtual File System + Cache              │
└─────────────────────────────────────────────────────────┘
```

---

## 核心模块详解

### 1. UI Layer (`ui/`)

**职责**: 用户界面展示和交互

#### 子模块
- **`home/`**: 主页书籍列表（支持状态筛选：READY/PARTIAL/UNAVAILABLE/DELETED）
- **`detail/`**: 书籍详情页（元数据、章节列表、操作按钮）
- **`player/`**: 播放器界面（全屏播放控制、章节导航、书签）
- **`miniplayer/`**: 迷你播放器（底部常驻）
- **`search/`**: 搜索界面（支持搜索历史）
- **`settings/`**: 设置页（库根管理、ABS 连接、语言切换）
- **`edit/`**: 书籍编辑（元数据修改、章节编辑）
- **`navigation/`**: 导航系统（Navigation 3 + Throttle）
- **`motion/`**: 共享元素动画（SharedElementKeys）
- **`common/`**: 通用 UI 组件（DisplayText、TimeUtils、主题）

**关键组件**
- `NavigationThrottle`: 防抖导航，避免快速点击
- `WindowClass`: 响应式布局适配（WindowWidthSizeClass）
- `SharedElementKeys`: 页面过渡动画

---

### 2. Application Layer (`application/`)

**职责**: 业务用例编排和命令处理

#### 子模块结构
```
application/
├── library/          # 图书馆业务逻辑
│   ├── home/        # 主页读模型和用例
│   ├── detail/      # 详情页命令和查询
│   ├── player/      # 播放器书签管理
│   ├── search/      # 搜索逻辑
│   ├── edit/        # 编辑命令
│   ├── settings/    # 设置模块
│   └── recovery/    # 已删除书籍恢复
├── playback/        # 播放控制
├── startup/         # 启动预热
└── usecase/         # 跨模块用例
```

**核心用例**
- `HomeLibraryUseCases`: 书籍列表管理、删除恢复
- `DeleteBookUseCase`: 软删除书籍（标记为 DELETED 状态）
- `BuildPlaybackPlanUseCase`: 构建播放计划
- `AbsSettingsConnectionUseCase`: ABS 服务器连接测试
- `SettingsLibraryMaintenanceUseCase`: 库维护（重新扫描、清理缓存）

**Read Models vs Commands**
- **Read Models**: 只读查询，返回 Flow<T>，用于 UI 订阅
- **Commands**: 写操作，suspend 函数，返回 Result<T>

---

### 3. Data Layer (`data/`)

**职责**: 业务用例编排和命令处理

#### 子模块结构
```
application/
├── library/          # 图书馆业务逻辑
│   ├── home/        # 主页读模型和用例
│   ├── detail/      # 详情页命令和查询
│   ├── player/      # 播放器书签管理
│   ├── search/      # 搜索逻辑
│   ├── edit/        # 编辑命令
│   ├── settings/    # 设置模块
│   └── recovery/    # 已删除书籍恢复
├── playback/        # 播放控制
├── startup/         # 启动预热
└── usecase/         # 跨模块用例
```

**核心用例**
- `HomeLibraryUseCases`: 书籍列表管理、删除恢复
- `DeleteBookUseCase`: 软删除书籍（标记为 DELETED 状态）
- `BuildPlaybackPlanUseCase`: 构建播放计划
- `AbsSettingsConnectionUseCase`: ABS 服务器连接测试
- `SettingsLibraryMaintenanceUseCase`: 库维护（重新扫描、清理缓存）

**Read Models vs Commands**
- **Read Models**: 只读查询，返回 Flow<T>，用于 UI 订阅
- **Commands**: 写操作，suspend 函数，返回 Result<T>

---

### 3. Data Layer (`data/`)

**职责**: 数据持久化和业务服务

#### 核心组件

**Database (`data/db/`)**
- `AppDatabase`: Room 数据库主入口
- `AudiobookSchema`: 统一常量注册表（状态码、类型标识）
  - `SourceType`: SINGLE_AUDIO, CUE, M3U8, GENERATED_M3U8, ABS_REMOTE
  - `BookStatus`: READY, PARTIAL, UNAVAILABLE, DELETED
  - `FileStatus`: READY, MISSING
  - `ReadStatus`: NOT_STARTED, IN_PROGRESS, FINISHED
  - `ChapterSource`: EMBEDDED, CUE, M3U8, GENERATED, MANUAL, ABS
  - `LibrarySourceType`: SAF, WEBDAV, ABS
  - `AnchorStatus`: OK, REMAPPED, UNRESOLVED
  - `ScanTrigger`: COLD_START, USER, ADD_LIBRARY_ROOT
  - `ScanStatus`: RUNNING, COMPLETED, ABANDONED
  - `LibraryRootStatus`: ACTIVE, REVOKED, ERROR
  - `AbsMirrorState`: ACTIVE, STALE, REMOTE_DELETED
  - `AvailabilityStatus`: AVAILABLE, UNKNOWN, REVOKED, AUTH_FAILED 等

**Entities (`data/entity/`)** - 11 个实体
- `BookEntity`: 书籍主实体
- `BookFileEntity`: 音频文件实体（角色：SOURCE_MANIFEST / AUDIO）
- `ChapterEntity`: 章节实体
- `BookProgressEntity`: 播放进度
- `BookmarkEntity`: 书签
- `LibraryRootEntity`: 库根配置
- `DirectoryCacheEntity` / `DirectoryChildCacheEntity`: 目录缓存
- `ScanSessionEntity`: 扫描会话
- `BookWithProgress` / `ChapterWithBookFile`: 关联查询实体

**DAOs (`data/dao/`)** - 7 个数据访问对象
- `BookDao`: 书籍 CRUD
- `BookmarkDao`: 书签管理
- `ChapterDao`: 章节查询
- `LibraryRootDao`: 库根管理
- `DirectoryCacheDao` / `DirectoryChildCacheDao`: 目录缓存
- `ScanSessionDao`: 扫描会话

**Gateways (`data/gateway/`)** - 16 个网关接口
- `BookCatalogGateway` / `BookQueryGateway` / `BookMetadataGateway` / `BookDeletionGateway`: 书籍访问
- `BookAvailabilityGateway`: 可用性检查
- `ChapterGateway`: 章节访问
- `ProgressGateway`: 进度访问
- `BookmarkGateway`: 书签访问
- `LibraryRootGateway`: 库根访问
- `CoverAssetGateway` / `CoverUriResolver`: 封面访问
- `MetadataRefreshGateway`: 元数据刷新
- `RemotePlaybackCleanupGateway`: 远程播放清理
- `ScanScheduler`: 扫描调度
- `SearchHistoryGateway`: 搜索历史
- `SubtitleGateway`: 字幕支持

**Services (`data/service/`)** - 12 个业务服务
- `BookQueryService`: 书籍查询聚合
- `BookAvailabilityService`: 可用性检查
- `LibraryRootService`: 库根管理
- `ScanService`: 扫描会话管理
- `ProgressService`: 进度持久化
- `PlaybackPlanService`: 播放计划构建
- `CoverAssetService` / `AndroidCoverUriResolver`: 封面资源管理
- `MetadataRefreshService`: 元数据刷新
- `SearchService`: 搜索服务（带防抖）
- `SubtitleService`: 字幕支持
- `RemotePlaybackCleanupService`: 远程播放清理

**Cache (`data/cache/`)** - 3 个缓存策略
- `CoverCacheInvalidationPolicy`: 封面缓存失效策略
- `OnlineSourceCachePolicy`: 在线源缓存策略
- `CacheEvictionCoordinator`: 缓存驱逐协调器

**Store (`data/store/`)** - 2 个持久化存储
- `AppSettings`: 应用设置（DataStore）
- `SearchHistoryStore`: 搜索历史存储

---

### 4. Media Layer (`media/`)

**职责**: 音频播放和元数据解析

#### 播放管理
- `PlaybackService`: Media3 播放服务
- `PlaybackManager`: 播放状态管理
- `AutoRewindManager`: 自动倒带（冷启动自我修复）
- `PlaybackAudioFocusManager`: 音频焦点管理
- `NotificationProgressPlayer`: 通知栏进度展示

#### 播放计划
- `BookPlaybackPlan`: 书籍播放计划（章节时间线）
- `PlaybackPlanBuilder`: 计划构建器
- `PlaybackFileLookup`: 文件查找
- `PlaybackSourcePreflight`: 播放源预检
- `VfsPlaybackDataSource`: VFS 数据源适配器

#### 元数据解析 (`media/parser/`)
- `RangeAudioParserRouter`: 路由到具体解析器
- `Mp3MetadataRangeParser`: MP3 ID3 解析
- `Mp4MetadataFrameReader`: MP4/M4A/M4B 解析
- `AacMetadataRangeParser`: AAC ADTS 解析
- `FlacMetadataRangeParser`: FLAC 解析
- `OggOpusMetadataRangeParser`: Ogg Opus 解析
- `WavMetadataRangeParser`: WAV RIFF 解析
- `CoverExtractor`: 封面提取（ID3v2/MP4 ilst）
- `MetadataResolver`: 元数据统一解析入口

#### Manifest 解析 (`media/manifest/`)
- `ManifestResolver`: CUE/M3U8 解析
- `HeuristicAudioAggregator`: 启发式音频聚合

#### 其他
- `ChapterTimeline`: 章节时间线计算
- `PlaybackMediaId`: Media3 MediaId 编解码
- `VfsPlaybackUri`: VFS URI 编码
- `ProgressSyncTracker`: 进度同步追踪

---

### 5. Library Layer (`library/`)

**职责**: 图书馆扫描、导入和虚拟文件系统

#### 扫描与导入 (`library/orchestrator/`)
- `ImportPipeline`: 导入流水线协调器
- `ImportContext`: 导入上下文
- `ImportScopeBuilder`: 导入范围构建
- `ImportConcurrency`: 并发控制

**导入步骤**
- `ManifestParseStep`: 解析 CUE/M3U8 清单
- `MetadataResolveStep`: 解析音频元数据
- `HeuristicGroupStep`: 启发式分组（自动识别多文件书籍）

#### 虚拟文件系统 (`library/vfs/`)
- `VirtualFileSystem`: VFS 抽象层
- `VfsFileInterface`: 文件接口（SAF/WebDAV/ABS 统一抽象）
- `VfsExternalInputReader`: 外部输入读取器

**VFS 缓存 (`library/vfs/cache/`)**
- `DirectoryListingCache`: 目录列表缓存
- `CachedRangeReader`: 范围读取缓存（优化远程媒体解析）
- `DirectoryCacheMapper`: 缓存映射器

**源提供者 (`library/vfs/sourceProvider/`)**
- `LibrarySourceProvider`: SAF/WebDAV/ABS 统一接口
- `WebDavConnectionTester`: WebDAV 连接测试
- `WebDavCredentialStore`: WebDAV 凭证存储

#### 其他
- `SourceInventoryScanner`: 源清单扫描
- `LibraryRootStore`: 库根存储
- `MissingBookFileRecoveryChecker`: 文件丢失恢复检查

---

### 6. Audiobookshelf Integration (`abs/`)

**职责**: 远程 Audiobookshelf 服务器同步

#### 网络层 (`abs/net/`)
- `AbsApiError`: API 错误定义
- `AbsLibraryDtos`: 库 DTO（书籍、章节）
- `AbsPlaybackDtos`: 播放会话 DTO
- `AbsStatusDto`: 服务器状态 DTO

#### 同步协调 (`abs/sync/`)
- `AbsSyncTaskCoordinator`: 同步任务协调器
- `AbsCatalogSynchronizer`: 目录同步器
- `AbsAuthorizedProgressSynchronizer`: 授权进度同步
- `AbsConnectionTester`: 连接测试
- `AbsSyncWorkScheduler`: WorkManager 调度

**镜像状态管理**
- `AbsItemMirrorDao` / `AbsItemMirrorEntity`: 镜像项实体
- `AbsSyncStateDao` / `AbsSyncStateEntity`: 同步状态
- `AbsMirrorState`: ACTIVE, STALE, REMOTE_DELETED

#### 播放会话同步 (`abs/playback/`)
- `AbsPlaybackSessionSyncer`: 会话同步器
- `AbsPlaybackSupport`: 播放支持
- `AbsProgressConflictCoordinator`: 进度冲突协调
- `AbsPendingProgressSyncDao`: 待同步进度队列
- `AbsPlaybackSessionDao`: 播放会话持久化

#### 其他
- `AbsRemoteIdMapper`: 远程 ID 映射（mapping/）
- `AbsVfsAdapter`: ABS VFS 适配器（vfs/）
- `AbsCoverCache`: 封面缓存和下载

---

### 7. 依赖注入与图 (`dependencies/` + `graph/`)

**职责**: 依赖容器和模块化组装

#### Dependency Interfaces (`dependencies/`)
- `AppShellDependencies`: 应用外壳依赖
- `HomeScreenDependencies`: 主页屏幕依赖
- `DetailScreenDependencies`: 详情屏幕依赖
- `PlayerScreenDependencies`: 播放器屏幕依赖
- `SearchScreenDependencies`: 搜索屏幕依赖
- `SettingsScreenDependencies`: 设置屏幕依赖
- `EditScreenDependencies`: 编辑屏幕依赖
- `PlaybackRuntimeDependencies`: 播放运行时依赖
- `VfsPlaybackDependencies`: VFS 播放依赖
- `LibrarySyncWorkerDependencies`: 库同步 Worker 依赖
- `AbsSyncWorkerDependencies`: ABS 同步 Worker 依赖
- `AppFeedbackDependencies`: 应用反馈依赖

#### Dependency Graphs (`graph/`)
- `DataGraph`: 数据层图（Database、DAOs、Services）
- `LibraryGraph`: 图书馆图（Scanner、VFS、Import Pipeline）
- `MediaGraph`: 媒体图（PlaybackManager、Parsers）
- `AbsGraph`: ABS 图（Sync、API Client）
- `UiEventGraph`: UI 事件图（AppEventSink）

**容器**
- `AppContainer`: 主容器接口（聚合所有依赖接口）
- `ProcessContainer`: 进程容器实现（实际图组装）
- `closeAppGraphsInLifecycleOrder()`: 生命周期顺序关闭

---

### 8. 其他核心模块

#### 事件系统 (`event/`)
- `AppEventSink`: 应用级事件汇聚（Toast、对话框）
- `PlaybackDomainEvent`: 播放领域事件（播放失败、进度更新）
- `feedback/`: 用户反馈机制

#### 日志系统 (`logger/`)
- `ImportTimingLogger`: 导入性能日志
- `PlaybackTimingLogger`: 播放性能日志
- `PlaybackFailureLogger`: 播放失败日志
- `AudioFocusLogger`: 音频焦点日志
- `CacheDiagnosticsLogger`: 缓存诊断日志
- `AutoRewindLogger`: 自动倒带日志
- **ABS 专用日志**:
  - `AbsAuthLogger`: 认证日志
  - `AbsCoverLogger`: 封面日志
  - `AbsPlaybackLogger`: 播放日志
  - `AbsStreamLogger`: 流媒体日志
  - `AbsSettingsLogger`: 设置日志
- `CoverImageCoilEventListener`: Coil 图片加载事件监听

#### 国际化 (`i18n/`)
- `AppLocaleController`: 应用语言控制器
- 支持语言: en, zh-CN, zh-HK, zh-TW, ja, fr, de, ru, es, pt

#### 后台任务 (`work/`)
- `LibrarySyncWorker`: 库同步 Worker
- `AbsSyncWorker`: ABS 同步 Worker

#### Widget (`widget/`)
- `PlayerWidgetReceiver`: Glance AppWidget 接收器

#### 网络 (`network/`)
- OkHttp 客户端配置
- Moshi JSON 序列化

#### Timeline (`timeline/`)
- 时间线计算和展示

---

## 核心流程

### 1. 应用启动流程

```
APlayerApplication.onCreate()
  ├─ AppLocaleController.ensurePlatformLocaleConfig()  // 语言同步
  ├─ StrictMode.setVmPolicy()                          // 调试模式检查
  └─ appScope.launch {                                 // 异步预热
       ├─ settingsRepository (DataStore 预加载)
       └─ createStartupWarmup().run()
            ├─ 检查 ABS 库根新鲜度
            ├─ 触发过期根的 WorkManager 同步
            └─ autoRewindManager.performColdStartSelfHealing()  // 进度自我修复
     }
```

### 2. 书籍导入流程

```
用户选择文件夹 (SAF)
  ↓
LibraryRootEntity 创建 (sourceType=SAF, status=ACTIVE)
  ↓
SourceInventoryScanner.scan()
  ↓
ImportPipeline.execute()
  ├─ ManifestParseStep: 解析 .cue / .m3u8
  ├─ HeuristicGroupStep: 启发式分组（同目录多文件）
  └─ MetadataResolveStep: 
       ├─ RangeAudioParserRouter 路由到具体解析器
       ├─ 解析元数据（标题、作者、时长、章节）
       └─ CoverExtractor 提取封面
  ↓
写入数据库:
  ├─ BookEntity (sourceType, status=READY)
  ├─ BookFileEntity (role=AUDIO / SOURCE_MANIFEST)
  └─ ChapterEntity (chapterSource)
```

### 3. 播放流程

```
用户点击播放书籍
  ↓
BuildPlaybackPlanUseCase.execute(bookId)
  ├─ PlaybackPlanService.buildPlan()
  │    ├─ 查询 ChapterEntity 列表
  │    ├─ 查询 BookFileEntity
  │    ├─ 构建 ChapterTimeline
  │    └─ 生成 BookPlaybackPlan
  └─ PlaybackFileLookup.resolve() (验证文件可访问性)
  ↓
PlaybackManager.play(plan)
  ├─ 编码 PlaybackMediaId (bookId + chapterIndex)
  ├─ 构建 MediaItem 列表
  │    └─ VfsPlaybackUri.encode() (vfs://...)
  └─ ExoPlayer.setMediaItems()
  ↓
PlaybackService (Media3 Foreground Service)
  ├─ VfsPlaybackDataSource 读取音频流
  │    └─ VirtualFileSystem.openInputStream()
  ├─ AutoRewindManager 监听暂停事件
  │    └─ 自动倒带 N 秒 (可配置)
  ├─ ProgressSyncTracker 追踪进度
  │    └─ 定期写入 BookProgressEntity
  └─ AbsPlaybackSessionSyncer (如果是 ABS 书籍)
       └─ 同步进度到服务器
```

### 4. ABS 同步流程

```
AbsSyncWorkScheduler.scheduleSync()
  ↓
AbsSyncWorker.doWork()
  ↓
AbsSyncTaskCoordinator.execute()
  ├─ Stage 1: 拉取服务器书籍列表
  │    └─ 更新 AbsItemMirrorEntity (mirrorState=ACTIVE)
  ├─ Stage 2: 标记本地缺失的书籍为 STALE
  ├─ Stage 3: 下载新书元数据
  │    ├─ 创建 BookEntity (sourceType=ABS_REMOTE)
  │    └─ 创建 ChapterEntity (chapterSource=ABS)
  ├─ Stage 4: 进度冲突解决
  │    └─ AbsProgressConflictCoordinator (服务器进度 vs 本地进度)
  └─ Stage 5: 清理 REMOTE_DELETED 的书籍
```

---

## 数据模型核心状态机

### BookStatus 状态转换

```
导入成功 → READY
   ├─ 部分文件丢失 → PARTIAL
   ├─ 所有文件丢失 → UNAVAILABLE
   └─ 用户删除 → DELETED (软删除)
      └─ 恢复 → READY/PARTIAL/UNAVAILABLE (根据文件状态)
```

### FileStatus 状态
- `READY`: 文件可访问
- `MISSING`: 文件丢失（触发 BookStatus 降级）

### LibrarySourceType 和 VFS 抽象

| SourceType | 描述 | 文件访问 |
|-----------|------|---------|
| `SAF` | Storage Access Framework | DocumentFile |
| `WEBDAV` | WebDAV 服务器 | OkHttp PROPFIND |
| `ABS` | Audiobookshelf | REST API + 流式传输 |

### ChapterSource 来源优先级

```
1. EMBEDDED    (内嵌章节，最高优先级)
2. CUE         (CUE 文件章节)
3. M3U8        (M3U8 文件章节)
4. ABS         (服务器章节)
5. MANUAL      (用户手动编辑)
6. GENERATED   (启发式生成，最低优先级)
```

---

## 关键设计模式

### 1. Gateway Pattern (网关模式)
**位置**: `data/gateway/`

**目的**: 隔离数据访问逻辑，提供稳定的业务层接口

**示例**:
```kotlin
interface BookCatalogGateway {
    fun observeAllBooks(): Flow<List<BookWithProgress>>
    suspend fun getBookById(id: Long): BookEntity?
    suspend fun updateBook(book: BookEntity)
}
```

### 2. Read Model / Command 分离 (CQRS 轻量化)
**位置**: `application/library/*/`

**Read Model**: 只读查询，返回 Flow，用于 UI 订阅
```kotlin
class HomeLibraryReadModel {
    fun observeBooks(statusFilter: BookStatus?): Flow<List<BookCard>>
}
```

**Command**: 写操作，返回 Result
```kotlin
class DeleteBookUseCase {
    suspend fun execute(bookId: Long): Result<Unit>
}
```

### 3. VFS 抽象层
**位置**: `library/vfs/`

**目的**: 统一 SAF、WebDAV、ABS 的文件访问接口

```kotlin
interface VfsFileInterface {
    suspend fun listFiles(uri: String): List<VfsFileInfo>
    suspend fun openInputStream(uri: String): InputStream
    suspend fun exists(uri: String): Boolean
}
```

**缓存策略**:
- `DirectoryListingCache`: 目录列表缓存（减少 SAF 调用）
- `CachedRangeReader`: 范围读取缓存（优化远程元数据解析）

### 4. 依赖注入图分层
**位置**: `graph/` + `dependencies/`

**原则**: 
- 图（Graph）负责实例化和生命周期管理
- 依赖接口（Dependencies）定义需求契约
- 容器（AppContainer）聚合所有依赖接口

**生命周期顺序**:
```kotlin
closeAppGraphsInLifecycleOrder() {
    // 1. 关闭 UI 事件流
    uiEventGraph.close()
    // 2. 关闭媒体播放器
    mediaGraph.close()
    // 3. 关闭 ABS 同步
    absGraph.close()
    // 4. 关闭图书馆扫描
    libraryGraph.close()
    // 5. 最后关闭数据库
    dataGraph.close()
}
```

### 5. 元数据解析器路由
**位置**: `media/parser/RangeAudioParserRouter`

**策略**: 基于文件扩展名 + 魔数头部识别

**支持的格式**:
- MP3: `Mp3MetadataRangeParser`
- M4A/M4B: `Mp4MetadataFrameReader`
- AAC: `AacMetadataRangeParser`
- FLAC: `FlacMetadataRangeParser`
- Ogg Opus: `OggOpusMetadataRangeParser`
- WAV: `WavMetadataRangeParser`

**范围读取优化**: 只读取必要的字节范围（减少远程 I/O）

### 6. 进度同步与冲突解决
**位置**: `abs/playback/AbsProgressConflictCoordinator`

**冲突策略**: 
1. 比较本地进度和服务器进度的时间戳
2. 选择较新的进度（last-write-wins）
3. 如果本地进度更新，标记为待同步（AbsPendingProgressSyncEntity）

### 7. 自动倒带机制
**位置**: `media/AutoRewindManager`

**功能**: 
- 监听播放器暂停事件
- 暂停超过阈值（如 5 秒），自动倒带 N 秒
- 冷启动自我修复：检测异常中断，恢复到安全位置

### 8. 启发式音频分组
**位置**: `library/orchestrator/steps/HeuristicGroupStep`

**逻辑**: 
- 同目录下多个音频文件
- 无 CUE/M3U8 清单
- 文件名包含顺序标记（如 `001.mp3`, `002.mp3`）
- 自动聚合为一本书 + 生成章节（sourceType=GENERATED_M3U8）

---

## 性能优化策略

### 1. 数据库优化
- **Room Schema 导出**: 启用 `room.schemaLocation`（版本控制审计）
- **索引**: 为常用查询字段添加索引（bookId, libraryRootId）
- **事务批处理**: 大量导入时使用 `@Transaction`

### 2. 图片加载优化
- **Coil**: 内存缓存 + 磁盘缓存
- **封面提取**: 只读取 ID3v2/MP4 封面帧（避免加载整个文件）
- **缓存失效**: `CoverCacheInvalidationPolicy` 根据元数据变更失效缓存

### 3. VFS 缓存策略
- **目录列表缓存**: `DirectoryListingCache` 减少 SAF 调用
- **范围读取缓存**: `CachedRangeReader` 优化远程元数据解析
- **缓存驱逐**: `CacheEvictionCoordinator` 根据空间压力驱逐

### 4. 远程音频流优化
- **范围请求**: 使用 HTTP Range 头只下载必要片段
- **预缓冲**: Media3 自动管理缓冲区
- **网络切换**: 监听网络状态，暂停远程播放

### 5. WorkManager 调度
- **库同步**: `LibrarySyncWorker` 周期性扫描变更
- **ABS 同步**: `AbsSyncWorker` 拉取服务器更新
- **约束**: 仅在 WiFi + 充电时执行重型同步

### 6. 搜索防抖
**位置**: `data/service/SearchService`

**策略**: 
- 用户输入防抖 300ms
- 避免频繁查询 Room
- 使用 `kotlinx.coroutines.test` 进行单元测试

---

## 测试架构

### 单元测试 (`app/src/test/`)
- **Room 测试**: 使用 Robolectric（JVM 环境运行 Android SDK）
- **协程测试**: `kotlinx-coroutines-test` 提供虚拟时间控制
- **网络测试**: `MockWebServer` 模拟 ABS API

**关键测试**:
- `AbsApiContractTest`: ABS API 契约测试
- `CachedRangeReaderTest`: 范围读取缓存测试
- `DirectoryCacheMapperTest`: 目录缓存映射测试
- `TrackProjectionChapterTest`: 章节投影测试

### 单元测试 (`app/src/test/`)
- **Room 测试**: 使用 Robolectric（JVM 环境运行 Android SDK）
- **协程测试**: `kotlinx-coroutines-test` 提供虚拟时间控制
- **网络测试**: `MockWebServer` 模拟 ABS API

**关键测试**:
- `AbsApiContractTest`: ABS API 契约测试
- `CachedRangeReaderTest`: 范围读取缓存测试
- `DirectoryCacheMapperTest`: 目录缓存映射测试
- `TrackProjectionChapterTest`: 章节投影测试

---

## 配置与构建

### Gradle 配置 (`app/build.gradle.kts`)
- **Kotlin 版本**: JVM 21
- **SDK 版本**: compileSdk 37, targetSdk 36, minSdk 32
- **Compose 编译器**: 启用 Kotlin Compose Plugin
- **KSP**: Room 编译器 + Kotlin Serialization
- **ProGuard**: Release 构建启用代码混淆和资源压缩

### 关键依赖
```kotlin
// UI
implementation("androidx.compose.material3:*")
implementation("androidx.navigation:navigation3-*")
implementation("dev.chrisbanes.haze:haze")

// 数据
implementation("androidx.room:room-*")
implementation("androidx.datastore:datastore-preferences")

// 媒体
implementation("androidx.media3:media3-exoplayer")
implementation("androidx.media3:media3-session")

// 网络
implementation("com.squareup.okhttp3:okhttp")
implementation("com.squareup.moshi:moshi-kotlin")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

// 图片
implementation("io.coil-kt:coil-compose")

// Widget
implementation("androidx.glance:glance-*")
```

### 语言资源
**支持的语言**: 
- 英语 (en)
- 简体中文 (zh-CN)
- 繁体中文 (zh-HK, zh-TW)
- 日语 (ja)
- 法语 (fr)
- 德语 (de)
- 俄语 (ru)
- 西班牙语 (es)
- 葡萄牙语 (pt)

---

## 常见开发任务

### 添加新的书籍状态
1. 修改 `AudiobookSchema.BookStatus` 添加新状态常量
2. 更新 `HomeLibraryReadModel` 的筛选逻辑
3. 修改 UI 层的状态展示（`home/` 组件）
4. 添加状态转换逻辑到相关 UseCase

### 支持新的音频格式
1. 在 `media/parser/` 创建新的解析器（如 `OpusMetadataRangeParser`）
2. 实现 `RangeAudioFormatParser` 接口
3. 在 `RangeAudioParserRouter` 的 `parsers` 列表中注册
4. 添加单元测试验证解析正确性

### 添加新的库源类型
1. 在 `AudiobookSchema.LibrarySourceType` 添加常量
2. 实现 `VfsFileInterface` 适配器
3. 在 `LibrarySourceProvider` 注册新类型
4. 更新 `SettingsScreenDependencies` 添加配置入口

### 扩展 ABS 同步逻辑
1. 修改 `abs/sync/AbsSyncTaskCoordinator` 添加新阶段
2. 更新 `AbsItemMirrorEntity` 如需新字段
3. 调整 `AbsCatalogSynchronizer` 的同步策略
4. 使用 `MockWebServer` 编写集成测试

### 调试播放问题
**关键日志**:
- `PlaybackTimingLogger`: 播放性能指标
- `PlaybackFailureLogger`: 播放失败原因
- `AutoRewindLogger`: 倒带行为
- `AudioFocusLogger`: 音频焦点竞争

**检查点**:
1. VFS 文件可访问性 (`PlaybackSourcePreflight`)
2. 播放计划构建正确性 (`BookPlaybackPlan`)
3. Media3 ExoPlayer 错误事件
4. VFS URI 编码是否正确 (`VfsPlaybackUri`)

---

## 架构原则与约定

### 1. 分层依赖规则
```
UI → Application → Data → Infrastructure
```
- **禁止反向依赖**: Data 层不能依赖 UI 层
- **接口隔离**: 使用 Gateway 和 Dependencies 接口解耦

### 2. 状态管理
- **单向数据流**: UI 发送 Command，订阅 Read Model
- **Flow 优先**: 使用 `Flow<T>` 而非 LiveData
- **不可变性**: 数据类使用 `val`，避免 `var`

### 3. 错误处理
- **Result 类型**: UseCase 返回 `Result<T>`
- **领域事件**: 使用 `PlaybackDomainEvent` 和 `AppEventSink` 传播错误
- **日志记录**: 关键路径使用专用 Logger

### 4. 命名约定
- **Entity**: 数据库实体，后缀 `Entity`
- **DTO**: 网络传输对象，后缀 `Dto`
- **Gateway**: 数据访问抽象，后缀 `Gateway`
- **UseCase**: 业务用例，后缀 `UseCase`
- **ReadModel**: 只读视图，后缀 `ReadModel`
- **Commands**: 写操作集合，后缀 `Commands`

### 5. 注释规范
- **架构注释**: 使用 `// Comment Title (Explanation)` 格式
- **示例**: `// Remote Audio Source Identifier (Flags remote tracks...)`
- **保留原始手写 JSON**: 导入管道中的手写 JSON 是故意的设计

### 6. 测试策略
- **单元测试**: 业务逻辑和算法（parser、mapper）
- **集成测试**: 网络 API 和数据库操作
- **UI 测试**: 关键用户流程（Espresso）

---

## 技术债务与未来改进

### 已知限制
1. **WebDAV 支持**: 基础设施已就绪（包括 UI 设置界面），但完整功能流程待验证
2. **离线模式**: ABS 书籍通过在线流式播放，无离线下载和预缓存机制
3. **多用户支持**: 当前仅支持单用户进度追踪

### 性能优化方向
1. **增量扫描**: 当前全量扫描，需要增量变更检测
2. **封面懒加载**: 列表滚动时延迟加载封面
3. **内存优化**: 减少大型列表的内存占用

### 代码质量改进
1. **类型安全**: 部分 String 常量可以改为枚举类
2. **协程作用域**: 统一协程作用域管理策略
3. **依赖注入**: 考虑引入 Hilt/Koin 简化图构建
4. **模块化**: 拆分为多模块 Gradle 项目

---

## 快速参考

### 关键文件位置
```
核心配置:
  app/build.gradle.kts              # 构建配置
  app/src/main/AndroidManifest.xml # 清单文件

应用入口:
  APlayerApplication.kt             # Application 类
  MainActivity.kt                   # 主 Activity
  AppContainer.kt                   # 依赖容器

数据库:
  data/db/AppDatabase.kt            # Room 数据库
  data/db/AudiobookSchema.kt        # 状态常量

播放核心:
  media/service/PlaybackService.kt  # 播放服务
  media/PlaybackManager.kt          # 播放管理器

VFS 抽象:
  library/vfs/VirtualFileSystem.kt  # 虚拟文件系统
```

### 常用命令
```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew test

# 运行仪器化测试
./gradlew connectedAndroidTest

# 查看依赖树
./gradlew app:dependencies

# 生成 Room Schema
./gradlew kspDebugKotlin
```

---

## 贡献指南

### 提交代码前检查
1. ✅ 代码符合 Kotlin 编码规范
2. ✅ 添加必要的单元测试
3. ✅ 更新相关注释和文档
4. ✅ 确保构建通过 (`./gradlew build`)
5. ✅ 检查 ProGuard 规则是否需要更新

### Git 分支策略
- `master`: 主分支，稳定版本
- `feature/*`: 功能分支
- `bugfix/*`: 修复分支
- `refactor/*`: 重构分支

---

**文档版本**: 2026-06-10  
**维护者**: 项目团队  
**最后更新**: 已验证并更新至当前代码库状态

