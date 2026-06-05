# APlayer 缓存层实施任务表

<!-- Document Purpose (Implementation task breakdown) This document converts the cache-layer review into executable tasks with fixed scope, target files, acceptance criteria, and regression checks. -->

日期：2026-06-05

本文只定义缓存层实施任务，不包含离线下载。任务按可回归阶段拆分，每个阶段完成后都能独立编译、独立测试、独立回退。

## 0. 结论边界

<!-- Boundary Summary (Prevent broad cache managers) This section fixes the cache ownership boundaries before listing implementation tasks, so each task stays close to its domain model. -->

当前代码不需要新增统一 `CacheManager`。缓存能力按领域边界拆开落地：

| 缓存方向 | 落地边界 | 不合并的对象 |
| --- | --- | --- |
| 封面失效 | `CoverCacheInvalidationPolicy` | 不合并 `CoverImageRequestFactory` 和 `AbsCoverCache` |
| 目录扫描缓存 | `library/vfs/cache` + Room 目录子项表 | 不合并 ABS catalog mirror |
| 缓存清理 | `CacheEvictionCoordinator` | 不接管扫描、同步、播放状态 |
| 缓存观测 | `CacheDiagnosticsLogger` | 不替换领域 logger |
| 小范围 Range 缓存 | `VfsRangeCache` + VFS readRange 装饰 | 不接播放主音频流 |

固定禁止项：

- 不新增 `CacheManager`、`AbsCacheManager`、`PlaybackCacheProvider`。
- 不把播放整轨音频写入磁盘缓存。
- 不把 ABS REST DTO 暴露给 UI 或播放层。
- 不让 `AvailabilityChecker` 读取缓存结果；可用性检查继续穿透真实 provider。
- 不在日志、缓存 key、文件名中写入 token、完整本地路径、完整远程 URL。

## 1. 阶段总览

<!-- Phase Overview (Regression-friendly order) This section orders the work by risk and dependency so every phase has a clear completion point. -->

| 阶段 | 目标 | 任务 | 完成标准 |
| --- | --- | --- | --- |
| P1 | 封面失效规则收口 | COV-01 到 COV-04 | 现有封面更新时间规则集中，ABS 路径不变但远程版本变化时能刷新 UI key |
| P2 | 目录扫描缓存落地 | DIR-01 到 DIR-06 | WebDAV 目录重复扫描可命中本地 children 快照，SAF 和 ABS 不走该缓存 |
| P3 | 缓存清理协调 | EVICT-01 到 EVICT-05 | 删除 root 时先收集缓存路径，再级联删除数据，再清理相关缓存文件 |
| P4 | 缓存观测汇总 | DIAG-01 到 DIAG-04 | directory/range/cover/absMirror 事件使用统一字段输出 |
| P5 | VFS 小范围 Range 缓存试点 | RANGE-01 到 RANGE-07 | metadata/cover 小范围重复读取命中磁盘块缓存，播放流直通 |

第一轮执行顺序固定为 P1、P2。P3 在 P2 后执行。P4 在 P2 或 P3 后执行。P5 最后执行。

## 2. P1 封面失效规则收口

<!-- P1 Scope (Cover invalidation policy) This phase extracts cover invalidation decisions into a policy object while continuing to use the existing BookEntity.lastScannedAt cache-version field. -->

### COV-01 新增封面失效策略类

目标文件：

- `app/src/main/java/com/viel/aplayer/data/cache/CoverCacheInvalidationPolicy.kt`

实施动作：

1. 新增 `object CoverCacheInvalidationPolicy`。
2. 新增函数：

```kotlin
fun resolveLastScannedAt(
    existing: BookEntity?,
    nextCoverPath: String?,
    nextThumbnailPath: String?,
    syncedAt: Long,
    remoteVersionChanged: Boolean = false
): Long
```

3. 函数规则固定为：

| 输入状态 | 返回值 |
| --- | --- |
| `existing == null` 且 `nextCoverPath` 或 `nextThumbnailPath` 非空 | `syncedAt` |
| `existing == null` 且两个路径都为空 | `0L` |
| `existing != null` 且 `coverPath` 变化 | `syncedAt` |
| `existing != null` 且 `thumbnailPath` 变化 | `syncedAt` |
| `existing != null` 且 `remoteVersionChanged == true` | `syncedAt` |
| 其他状态 | `existing.lastScannedAt` |

验收条件：

- 策略类不访问 Room。
- 策略类不下载封面。
- 策略类不创建 Coil `ImageRequest`。
- 策略类只依赖 `BookEntity`。

回归验证：

- 新增 `app/src/test/java/com/viel/aplayer/data/cache/CoverCacheInvalidationPolicyTest.kt`。
- 运行 `.\gradlew.bat compileDebugKotlin`。
- 运行包含 `CoverCacheInvalidationPolicyTest` 的 unit test。

### COV-02 替换 ABS 同步里的封面失效函数

目标文件：

- `app/src/main/java/com/viel/aplayer/abs/sync/AbsCatalogSynchronizer.kt`
- `app/src/test/java/com/viel/aplayer/abs/AbsCoverInvalidationRuleTest.kt`

实施动作：

1. 删除或停止使用 `resolveAbsCoverLastScannedAt()`。
2. 在 `AbsCatalogSynchronizer.upsertItem()` 中调用 `CoverCacheInvalidationPolicy.resolveLastScannedAt()`。
3. 在调用前计算 `remoteVersionChanged`：
   - 读取当前 item 的 `item.updatedAt`。
   - 读取当前 remote item 对应的 `AbsItemMirrorEntity.remoteUpdatedAt`。
   - 两者都非空且数值不相等时，`remoteVersionChanged = true`。
   - 其他状态为 `false`。
4. `upsertItem()` 增加 `existingMirror: AbsItemMirrorEntity?` 参数。
5. `syncRootInternal()` 调用 `upsertItem()` 时传入 `existingMirrors[remoteItemId]`。
6. `AbsCoverInvalidationRuleTest` 改为验证新策略类，测试包名保持可编译。

验收条件：

- ABS 首次同步有封面路径时，`lastScannedAt = syncedAt`。
- ABS 首次同步无封面路径时，`lastScannedAt = 0L`。
- ABS 封面路径不变且 `item.updatedAt == existingMirror.remoteUpdatedAt` 时，保留旧 `lastScannedAt`。
- ABS 封面路径不变且 `item.updatedAt != existingMirror.remoteUpdatedAt` 时，刷新为 `syncedAt`。
- ABS 封面路径变化时，刷新为 `syncedAt`。

回归验证：

- `.\gradlew.bat compileDebugKotlin`
- `AbsCoverInvalidationRuleTest`
- `AbsIncrementalStage6Test`
- `AbsCatalogStage2Test`

### COV-03 收口本地封面写入的更新时间规则

目标文件：

- `app/src/main/java/com/viel/aplayer/data/service/CoverService.kt`
- `app/src/main/java/com/viel/aplayer/media/parser/CoverRecoveryHelper.kt`
- `app/src/main/java/com/viel/aplayer/data/dao/BookDao.kt`

实施动作：

1. 检查 `BookDao.updateCoverPaths()` 的所有调用点。
2. 所有成功写入本地封面路径的调用都传入 `System.currentTimeMillis()`。
3. 所有未产生新封面路径的调用不更新 `lastScannedAt`。
4. `CoverRecoveryHelper` 保留短窗口检查 key：`bookId + coverPath + thumbnailPath + lastScannedAt`。
5. 不改 `CoverImageRequestFactory.cacheKey()`。

验收条件：

- 用户自定义封面保存成功后，UI key 中的 `lastUpdated` 变化。
- 封面恢复成功后，UI key 中的 `lastUpdated` 变化。
- 封面恢复失败后，`lastScannedAt` 不变化。
- `CoverImageCacheRuleTest` 继续通过。

回归验证：

- `.\gradlew.bat compileDebugKotlin`
- `CoverImageCacheRuleTest`

### COV-04 固定不新增 `coverVersion` 字段

目标文件：

- `docs/cache.md`

实施动作：

1. 本阶段继续使用 `BookEntity.lastScannedAt` 作为封面 UI 缓存版本。
2. 不修改 `BookEntity` schema。
3. 不提升 Room database version。
4. 不新增 migration。

验收条件：

- `BookEntity.kt` 没有新增 `coverVersion` 字段。
- `AppDatabase.version` 不因 P1 变化。
- P1 完成后只改变策略类、调用点和测试。

回归验证：

- `git diff -- app/src/main/java/com/viel/aplayer/data/entity/BookEntity.kt app/src/main/java/com/viel/aplayer/data/db/AppDatabase.kt`

## 3. P2 目录扫描缓存落地

<!-- P2 Scope (Directory listing cache) This phase adds a real directory children snapshot because the existing directory_cache table only stores directory state and cannot return child listings by itself. -->

### DIR-01 新增目录子项缓存实体

目标文件：

- `app/src/main/java/com/viel/aplayer/data/entity/DirectoryChildCacheEntity.kt`
- `app/src/main/java/com/viel/aplayer/data/db/AppDatabase.kt`

实施动作：

1. 新增 Room entity，表名固定为 `directory_child_cache`。
2. 字段固定为：

```kotlin
@PrimaryKey val cacheKey: String
val rootId: String
val parentSourcePath: String
val sourcePath: String
val identity: String
val parentIdentity: String
val displayName: String
val isDirectory: Boolean
val fileSize: Long
val lastModified: Long
val etag: String?
val mimeType: String?
val cachedAt: Long
```

3. `cacheKey` 格式固定为：

```text
<rootId>|<parentSourcePath>|<sourcePath>
```

4. 增加外键：
   - `rootId` 指向 `LibraryRootEntity.id`。
   - `onDelete = CASCADE`。
5. 增加索引：
   - `Index("rootId", "parentSourcePath")`
   - `Index("rootId")`
6. `AppDatabase.entities` 加入该 entity。
7. `AppDatabase.version` 增加 1。

验收条件：

- KSP 生成 Room 代码成功。
- 删除 library root 时，`directory_child_cache` 对应记录级联删除。

回归验证：

- `.\gradlew.bat compileDebugKotlin`

### DIR-02 新增目录子项缓存 DAO

目标文件：

- `app/src/main/java/com/viel/aplayer/data/dao/DirectoryChildCacheDao.kt`
- `app/src/main/java/com/viel/aplayer/data/db/AppDatabase.kt`

实施动作：

1. 新增 DAO。
2. DAO 方法固定为：

```kotlin
@Query("SELECT * FROM directory_child_cache WHERE rootId = :rootId AND parentSourcePath = :parentSourcePath ORDER BY displayName ASC")
suspend fun getChildren(rootId: String, parentSourcePath: String): List<DirectoryChildCacheEntity>

@Query("DELETE FROM directory_child_cache WHERE rootId = :rootId AND parentSourcePath = :parentSourcePath")
suspend fun deleteChildren(rootId: String, parentSourcePath: String)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertChildren(children: List<DirectoryChildCacheEntity>)

@Transaction
open suspend fun replaceChildren(rootId: String, parentSourcePath: String, children: List<DirectoryChildCacheEntity>)

@Query("DELETE FROM directory_child_cache WHERE rootId = :rootId")
suspend fun deleteByRootId(rootId: String)
```

3. `replaceChildren()` 先删除 parent 下旧记录，再插入新记录。
4. `AppDatabase` 暴露 `directoryChildCacheDao()`。

验收条件：

- DAO 不包含模糊查询。
- DAO 查询只按 `rootId + parentSourcePath` 返回子项。
- DAO 不读取 `books` 或 `book_files`。

回归验证：

- `.\gradlew.bat compileDebugKotlin`

### DIR-03 新增目录缓存映射器

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/DirectoryCacheMapper.kt`

实施动作：

1. 新增 `DirectoryCacheMapper`。
2. 提供 `SourceFileMetadata -> DirectoryChildCacheEntity` 映射。
3. 提供 `DirectoryChildCacheEntity -> SourceFileMetadata` 映射。
4. `parentSourcePath` 使用当前目录 `VfsNode.metadata.sourcePath`。
5. `cachedAt` 使用写入时的 `System.currentTimeMillis()`。

验收条件：

- 映射保留 `sourcePath`、`identity`、`displayName`、`isDirectory`、`fileSize`、`lastModified`、`etag`、`mimeType`。
- 映射不持有 provider 原生对象。
- 映射不保存 URL 或 token。

回归验证：

- 新增 `DirectoryCacheMapperTest`。

### DIR-04 新增 `RoomDirectoryListingCache`

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/DirectoryListingCache.kt`
- `app/src/main/java/com/viel/aplayer/library/vfs/cache/RoomDirectoryListingCache.kt`

实施动作：

1. 新增接口：

```kotlin
interface DirectoryListingCache {
    suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>?
    suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>)
    suspend fun evictRoot(rootId: String)
}
```

2. 新增 `NoOpDirectoryListingCache`。
3. 新增 `RoomDirectoryListingCache`。
4. `getChildren()` 只对 `LibrarySourceKind.WEBDAV` 返回缓存。
5. `getChildren()` 对 `SAF` 和 `ABS` 固定返回 `null`。
6. `replaceChildren()` 只对 `WEBDAV` 写入缓存。
7. `replaceChildren()` 对 `SAF` 和 `ABS` 固定 no-op。

验收条件：

- SAF 不命中目录 children 缓存。
- ABS 不命中目录 children 缓存。
- WebDAV 可读取已写入 children 快照。

回归验证：

- 新增 `RoomDirectoryListingCacheTest`。
- `.\gradlew.bat compileDebugKotlin`

### DIR-05 接入 `VirtualFileSystem.listChildren()`

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/VirtualFileSystem.kt`

实施动作：

1. `VirtualFileSystem` 构造函数增加参数：

```kotlin
private val directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache
```

2. `listChildren(directory)` 执行顺序固定为：
   - 调用 `directoryListingCache.getChildren(directory)`。
   - 缓存返回非空列表时，把每个 `SourceFileMetadata` 包装成 `VfsNode` 返回。
   - 缓存返回 `null` 时，调用 provider `listChildren()`。
   - provider 成功返回后调用 `directoryListingCache.replaceChildren(directory, childMetadata)`。
   - 返回 provider 结果包装成的 `VfsNode`。
3. `exists()`、`openInputStream()`、`readRange()` 不接入该缓存。
4. `AvailabilityChecker` 构造的 `VirtualFileSystem` 使用默认 `NoOpDirectoryListingCache`。

验收条件：

- 目录 children 缓存只影响 `listChildren()`。
- 播放和 Range 读取不受目录缓存影响。
- 可用性检查不受目录缓存影响。

回归验证：

- 新增 `VirtualFileSystemDirectoryListingCacheTest`。
- `.\gradlew.bat compileDebugKotlin`

### DIR-06 在扫描链路注入目录缓存

目标文件：

- `app/src/main/java/com/viel/aplayer/library/SourceInventoryScanner.kt`
- `app/src/main/java/com/viel/aplayer/library/orchestrator/ScanSessionRunner.kt`
- `app/src/main/java/com/viel/aplayer/data/service/ScanService.kt`
- `app/src/main/java/com/viel/aplayer/AppContainer.kt`

实施动作：

1. `SourceInventoryScanner` 构造函数增加 `directoryListingCache: DirectoryListingCache` 参数。
2. `SourceInventoryScanner` 创建 `VirtualFileSystem` 时传入该 cache。
3. `ScanSessionRunner` 接收并传递 `DirectoryListingCache`。
4. `ScanService` 从构造函数接收 `DirectoryListingCache`。
5. `DefaultAppContainer` 新增 `RoomDirectoryListingCache` lazy 实例，依赖：
   - `database.directoryChildCacheDao()`
   - `DirectoryCacheMapper`
6. `DefaultAppContainer.scanScheduler` 构造 `ScanService` 时传入 `RoomDirectoryListingCache`。
7. `AvailabilityChecker` 不变。

验收条件：

- 只有扫描链路使用 `RoomDirectoryListingCache`。
- 播放链路的 `vfsFileInterface` 仍不读取目录 children 缓存。
- `SourceInventoryScanner` 测试能注入 `NoOpDirectoryListingCache`。

回归验证：

- `.\gradlew.bat compileDebugKotlin`
- 手动执行一次 WebDAV root 扫描，第二次扫描日志出现 directory cache hit。
- 手动执行一次 SAF root 扫描，日志不出现 SAF directory cache hit。

## 4. P3 缓存清理协调

<!-- P3 Scope (Eviction coordination) This phase centralizes cache cleanup around root deletion without taking over scan, sync, or playback responsibilities. -->

### EVICT-01 新增缓存清理协调器

目标文件：

- `app/src/main/java/com/viel/aplayer/data/cache/CacheEvictionCoordinator.kt`

实施动作：

1. 新增 `CacheEvictionCoordinator` class。
2. 构造参数固定为：

```kotlin
context: Context
bookDao: BookDao
directoryCacheDao: DirectoryCacheDao
directoryChildCacheDao: DirectoryChildCacheDao
```

3. 新增函数：

```kotlin
suspend fun evictBeforeRootDelete(root: LibraryRootEntity): CacheEvictionSummary
```

4. 新增 `CacheEvictionSummary`，字段固定为：

```kotlin
val rootId: String
val coverFilesDeleted: Int
val directoryRowsDeleted: Boolean
val directoryChildRowsDeleted: Boolean
```

验收条件：

- 协调器不调用扫描。
- 协调器不调用 ABS 同步。
- 协调器不读取或修改播放状态。

回归验证：

- `.\gradlew.bat compileDebugKotlin`

### EVICT-02 增加 root 下封面路径查询

目标文件：

- `app/src/main/java/com/viel/aplayer/data/dao/BookDao.kt`

实施动作：

1. 新增投影 data class：

```kotlin
data class BookCoverCachePaths(
    val coverPath: String?,
    val thumbnailPath: String?
)
```

2. 新增 DAO 方法：

```kotlin
@Query("SELECT coverPath, thumbnailPath FROM books WHERE rootId = :rootId")
suspend fun getCoverCachePathsByRootId(rootId: String): List<BookCoverCachePaths>
```

验收条件：

- 查询只返回 coverPath 和 thumbnailPath。
- 查询不返回书籍正文数据。

回归验证：

- `.\gradlew.bat compileDebugKotlin`

### EVICT-03 清理 root 关联封面文件

目标文件：

- `app/src/main/java/com/viel/aplayer/data/cache/CacheEvictionCoordinator.kt`

实施动作：

1. 在删除 root 前调用 `bookDao.getCoverCachePathsByRootId(root.id)`。
2. 收集非空 `coverPath` 和 `thumbnailPath`。
3. 只删除满足以下条件的文件：
   - 文件路径位于 `context.cacheDir/covers` 目录下。
   - 文件真实存在。
   - 文件是普通文件。
4. 不删除 `cacheDir/covers` 目录本身。
5. 不删除不在 `cacheDir/covers` 下的路径。

验收条件：

- 删除 root 不影响其他 root 的封面文件。
- 外部路径不会被删除。
- 缩略图和原图都能被统计。

回归验证：

- 新增 `CacheEvictionCoordinatorTest`。

### EVICT-04 清理目录缓存表

目标文件：

- `app/src/main/java/com/viel/aplayer/data/cache/CacheEvictionCoordinator.kt`

实施动作：

1. 调用 `directoryCacheDao.deleteByRootId(root.id)`。
2. 调用 `directoryChildCacheDao.deleteByRootId(root.id)`。
3. `CacheEvictionSummary.directoryRowsDeleted` 固定返回 `true`。
4. `CacheEvictionSummary.directoryChildRowsDeleted` 固定返回 `true`。

验收条件：

- root 删除前调用时，缓存表被清理。
- root 删除后 Room 外键级联仍保留兜底。

回归验证：

- `CacheEvictionCoordinatorTest`
- `.\gradlew.bat compileDebugKotlin`

### EVICT-05 接入 root 删除链路

目标文件：

- `app/src/main/java/com/viel/aplayer/data/service/LibraryRootService.kt`
- `app/src/main/java/com/viel/aplayer/AppContainer.kt`

实施动作：

1. `LibraryRootService` 构造函数增加 `cacheEvictionCoordinator: CacheEvictionCoordinator`。
2. `deleteLibraryRootDataOnly(root)` 第一行调用 `cacheEvictionCoordinator.evictBeforeRootDelete(root)`。
3. 调用完成后继续执行现有 root 数据删除逻辑。
4. `DefaultAppContainer` 创建并注入 `CacheEvictionCoordinator`。

验收条件：

- `deleteLibraryRootDataOnly()` 中缓存清理发生在 Room root 删除前。
- `DeleteLibraryRootUseCase` 不增加缓存清理逻辑。
- 播放停止逻辑仍由 `DeleteLibraryRootUseCase` 处理。

回归验证：

- `.\gradlew.bat compileDebugKotlin`
- 删除一个 SAF root。
- 删除一个 WebDAV root。
- 删除一个 ABS root。

## 5. P4 缓存观测汇总

<!-- P4 Scope (Diagnostics) This phase adds a cache-focused logger while preserving existing domain loggers. -->

### DIAG-01 新增缓存诊断 logger

目标文件：

- `app/src/main/java/com/viel/aplayer/logger/CacheDiagnosticsLogger.kt`

实施动作：

1. 新增 `object CacheDiagnosticsLogger`。
2. 新增统一函数：

```kotlin
fun logCacheEvent(
    cacheType: String,
    operation: String,
    hit: Boolean?,
    costMs: Long?,
    sourceHash: String?,
    sizeBytes: Long?,
    detail: String? = null
)
```

3. `cacheType` 固定枚举字符串：
   - `directory`
   - `range`
   - `cover`
   - `abs_mirror`
4. 日志 tag 固定为 `APlayerCache`。

验收条件：

- 日志不包含完整路径。
- 日志不包含完整 URL。
- 日志不包含 token。

回归验证：

- `.\gradlew.bat compileDebugKotlin`

### DIAG-02 接入目录缓存事件

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/RoomDirectoryListingCache.kt`

实施动作：

1. `getChildren()` 命中时输出：
   - `cacheType = "directory"`
   - `operation = "getChildren"`
   - `hit = true`
2. `getChildren()` 未命中时输出 `hit = false`。
3. `replaceChildren()` 输出：
   - `operation = "replaceChildren"`
   - `sizeBytes = children.size.toLong()`

验收条件：

- WebDAV 第二次扫描能看到 directory hit。
- SAF 扫描不输出 directory hit。

回归验证：

- WebDAV 手动扫描两次。

### DIAG-03 接入 ABS mirror 复用事件

目标文件：

- `app/src/main/java/com/viel/aplayer/abs/sync/AbsCatalogSynchronizer.kt`

实施动作：

1. 在 `selectAbsDetailCandidateIds()` 后输出一条 `abs_mirror` 事件。
2. `operation = "selectDetailCandidates"`。
3. `sizeBytes` 写入复用数量：

```text
minifiedItems.size - detailCandidateIds.size
```

验收条件：

- ABS 增量同步时能看到复用数量。
- 日志不输出 remote item id 原文。

回归验证：

- `AbsIncrementalStage6Test`

### DIAG-04 接入封面缓存事件

目标文件：

- `app/src/main/java/com/viel/aplayer/ui/common/CoverImageRequestFactory.kt`
- `app/src/main/java/com/viel/aplayer/logger/CoverImageCacheLogger.kt`

实施动作：

1. 保留 `CoverImageCacheLogger` 现有日志。
2. 在 request success 时增加一条 `CacheDiagnosticsLogger.logCacheEvent()`。
3. `cacheType = "cover"`。
4. `operation = "decode"`。
5. `hit` 根据 `decodeSource` 映射：
   - memory cache 或 disk cache 为 `true`。
   - file decode 为 `false`。
6. `sourceHash` 使用现有 hash，不使用原始路径。

验收条件：

- 封面展示日志保留原有字段。
- 新增缓存汇总日志能判断 hit/miss。

回归验证：

- `CoverImageCacheRuleTest`
- 手动进入首页、详情页、播放页。

## 6. P5 VFS 小范围 Range 缓存试点

<!-- P5 Scope (Range cache pilot) This phase adds a bounded disk cache for metadata-sized range reads and explicitly keeps playback streams outside the cache. -->

### RANGE-01 新增 Range 缓存 key

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/VfsRangeCacheKey.kt`

实施动作：

1. 新增 data class：

```kotlin
data class VfsRangeCacheKey(
    val rootIdHash: String,
    val sourcePathHash: String,
    val version: String,
    val offset: Long,
    val length: Int
)
```

2. 新增 `toFileName()`：

```text
<rootIdHash>_<sourcePathHash>_<version>_<offset>_<length>.bin
```

3. `version` 规则固定为：
   - `etag` 非空时使用 `etag` 的 hash。
   - `etag` 为空时使用 `"${lastModified}_${fileSize}"` 的 hash。
4. `length <= 0` 不生成 key。
5. `offset < 0` 不生成 key。

验收条件：

- key 不包含原始 rootId。
- key 不包含原始 sourcePath。
- key 不包含完整 etag。

回归验证：

- 新增 `VfsRangeCacheKeyTest`。

### RANGE-02 新增 Range 缓存文件存储

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/VfsRangeCache.kt`

实施动作：

1. 缓存目录固定为 `context.cacheDir/vfs_range_cache`。
2. 单块上限固定为 `64 * 1024` bytes。
3. 总容量上限固定为 `64 * 1024 * 1024` bytes。
4. 新增函数：

```kotlin
suspend fun read(key: VfsRangeCacheKey): ByteArray?
suspend fun write(key: VfsRangeCacheKey, bytes: ByteArray)
suspend fun evictRoot(rootIdHash: String)
suspend fun trimToSize()
```

5. `write()` 对超过单块上限的数据固定 no-op。
6. `write()` 使用临时文件写入，再原子替换目标文件。
7. `trimToSize()` 按最后修改时间删除最旧文件。

验收条件：

- 读取不存在 key 返回 null。
- 写入后读取返回同一 ByteArray。
- 大于 64KB 的数据不写入。
- 总目录超过 64MB 后会删除旧文件。

回归验证：

- 新增 `VfsRangeCacheTest`。

### RANGE-03 新增 Range 缓存装饰器

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/CachedRangeReader.kt`

实施动作：

1. 新增 class `CachedRangeReader`。
2. 构造参数：

```kotlin
rangeCache: VfsRangeCache
readRange: suspend (offset: Long, length: Int) -> ByteArray?
```

3. 新增函数：

```kotlin
suspend fun read(file: VfsNode, offset: Long, length: Int): ByteArray?
```

4. 执行顺序：
   - 校验 provider capability `supportsRangeRead == true`。
   - 生成 `VfsRangeCacheKey`。
   - 先读 cache。
   - cache miss 后调用 delegate `readRange`。
   - delegate 返回非空且长度小于等于 64KB 时写 cache。
   - 返回 delegate 结果。

验收条件：

- 不处理 `openInputStream()`。
- 不处理播放 offset stream。
- provider 不支持 range 时直接调用 delegate。

回归验证：

- 新增 `CachedRangeReaderTest`。

### RANGE-04 接入元数据读取链路

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/VfsFileInterface.kt`
- `app/src/main/java/com/viel/aplayer/AppContainer.kt`

实施动作：

1. `VfsFileInterface` 构造函数增加可空 `rangeCache: VfsRangeCache? = null`。
2. `readRange(FileRef, offset, length)` 使用 `rangeCache` 包装读取。
3. `readRange(BookFileEntity, offset, length)` 使用 `rangeCache` 包装读取。
4. `open()` 和 `open(offset)` 保持原样。
5. `DefaultAppContainer.vfsFileInterface` 传入 `VfsRangeCache(context.applicationContext)`。

验收条件：

- MetadataResolver 和 CoverRecoveryHelper 通过现有 `vfsFileInterface` 获得 Range 缓存。
- `VfsPlaybackDataSource` 调用的 `open(file, offset)` 不走 Range 缓存。

回归验证：

- `.\gradlew.bat compileDebugKotlin`
- MP4 metadata 解析测试。
- 播放 seek 手动验证。

### RANGE-05 接入 Range 缓存日志

目标文件：

- `app/src/main/java/com/viel/aplayer/library/vfs/cache/CachedRangeReader.kt`

实施动作：

1. cache hit 输出：
   - `cacheType = "range"`
   - `operation = "readRange"`
   - `hit = true`
2. cache miss 输出 `hit = false`。
3. 写入成功输出 `operation = "writeRange"`。

验收条件：

- 同一 metadata 读取第二次出现 range hit。
- 日志不包含完整 sourcePath。

回归验证：

- `CachedRangeReaderTest`

### RANGE-06 接入清理协调器

目标文件：

- `app/src/main/java/com/viel/aplayer/data/cache/CacheEvictionCoordinator.kt`
- `app/src/main/java/com/viel/aplayer/library/vfs/cache/VfsRangeCache.kt`

实施动作：

1. `CacheEvictionCoordinator` 构造函数增加 `vfsRangeCache: VfsRangeCache?`。
2. `evictBeforeRootDelete()` 计算 `rootIdHash`。
3. 调用 `vfsRangeCache.evictRoot(rootIdHash)`。
4. `CacheEvictionSummary` 增加 `rangeFilesDeleted: Int`。

验收条件：

- 删除 root 时删除该 root 的 Range 缓存文件。
- 不删除其他 root 的 Range 缓存文件。

回归验证：

- `CacheEvictionCoordinatorTest`
- `VfsRangeCacheTest`

### RANGE-07 固定不接播放流

目标文件：

- `app/src/main/java/com/viel/aplayer/media/VfsPlaybackDataSource.kt`

实施动作：

1. 不修改 `VfsPlaybackDataSource.open()` 的读取策略。
2. 不在 `VfsPlaybackDataSource` 中引用 `VfsRangeCache`。
3. 不在 `AbsSourceProvider.openInputStream()` 中写 Range 缓存。
4. 不在 `WebDavSourceProvider.openInputStream()` 中写 Range 缓存。

验收条件：

- `git diff -- app/src/main/java/com/viel/aplayer/media/VfsPlaybackDataSource.kt` 不包含 Range 缓存接入。
- 播放 seek 仍由 provider 处理。

回归验证：

- 手动播放 WebDAV 书籍并 seek。
- 手动播放 ABS 书籍并 seek。

## 7. 全局回归清单

<!-- Global Regression Checklist (Final verification) This section lists the commands and manual checks that must run after completing the full task set. -->

命令回归：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

重点单测：

- `CoverCacheInvalidationPolicyTest`
- `AbsCoverInvalidationRuleTest`
- `CoverImageCacheRuleTest`
- `DirectoryCacheMapperTest`
- `RoomDirectoryListingCacheTest`
- `VirtualFileSystemDirectoryListingCacheTest`
- `CacheEvictionCoordinatorTest`
- `VfsRangeCacheKeyTest`
- `VfsRangeCacheTest`
- `CachedRangeReaderTest`

手动回归：

| 场景 | 验证点 |
| --- | --- |
| SAF root 扫描 | 不出现 directory cache hit；扫描结果正常 |
| WebDAV root 连续扫描两次 | 第二次出现 directory cache hit；扫描结果不丢书 |
| ABS 同步两次 | 未变化 item 复用 mirror；未变化封面不刷新 key |
| ABS item updatedAt 变化 | 路径不变时刷新 `lastScannedAt` |
| 本地封面恢复 | 成功后 UI 封面 key 刷新 |
| 删除 root | 目录缓存、目录子项缓存、root 关联封面、Range 缓存被清理 |
| MP4 元数据重复读取 | 第二次读取出现 range cache hit |
| WebDAV 播放 seek | 播放流仍由 provider Range 处理 |
| ABS 播放 seek | 播放流仍由 provider Range 处理 |

最终审查：

```powershell
rg -n "CacheManager|AbsCacheManager|PlaybackCacheProvider" app\src\main\java
rg -n "VfsRangeCache" app\src\main\java\com\viel\aplayer\media app\src\main\java\com\viel\aplayer\abs\vfs app\src\main\java\com\viel\aplayer\library\vfs\sourceProvider\webdav
rg -n "token|Bearer" app\src\main\java\com\viel\aplayer\library\vfs\cache app\src\main\java\com\viel\aplayer\logger\CacheDiagnosticsLogger.kt
```
