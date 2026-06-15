# 代码收敛审计报告（Consolidation Audit）

> 目的：定位项目中散落、重复、可收敛的功能与函数。本报告只做**识别**，不改动代码。
> 范围：`app/src/main/java/com/viel/aplayer/**`（约 440 个 Kotlin 文件，5.8 万行）
> 生成日期：2026-06-16
> 验证方式：所有发现均经 grep 直接验证关键行号；逐字节相同的复制段已抽样核对。

---

## 如何阅读

每条发现标注：
- **严重度**：🔴 高（量大 / 已有规范位却没用）/ 🟡 中 / 🟢 低（可选清理）
- **已有规范位**：✅ 存在但被绕过 / ❌ 尚无 / 🔶 部分
- **重复点**：`文件:行` 列表
- **建议**：收敛方式 + 注意事项（caveat）

末尾有**优先级汇总表**和**两个不该动的点**（避免误收敛）。

---

## 一、媒体解析器：最大的逐字节复制（约 350 行）

<!-- Mark heading of section 1.1 as struck through since it has been resolved -->
### ~~1.1 ID3 解析栈在 Mp3 与 Aac 之间逐字节相同 🔴~~

`Mp3MetadataRangeParser` 与 `AacMetadataRangeParser` **各有一套完整的 ID3 解析实现，10 个方法签名一一对应、函数体逐字相同**。这是全项目最集中的重复。

| 方法                        | Mp3 行                                        | Aac 行                                       |
|---------------------------|----------------------------------------------|---------------------------------------------|
| `readId3v2`               | `media/parser/Mp3MetadataRangeParser.kt:154` | `media/parser/AacMetadataRangeParser.kt:74` |
| `readId3v1`               | `:192`                                       | `:112`                                      |
| `parseId3Frames`          | `:211`                                       | `:131`                                      |
| `decodeId3TextFrame`      | `:293`                                       | `:214`                                      |
| `decodeId3UserTextFrame`  | `:304`                                       | `:225`                                      |
| `decodeId3CommentFrame`   | `:317`                                       | `:238`                                      |
| `decodeId3ApicFrame`      | `:329`                                       | `:250`                                      |
| `id3Charset`              | `:345`                                       | `:266`                                      |
| `removeUnsynchronization` | `:354`                                       | `:275`                                      |
| `parseId3ChapterFrame`    | `:369`                                       | `:290`                                      |
| `finalizeChapters`        | `:414`                                       | `:335`                                      |
| `ParsedId3Tag` 数据类        | `:351-363`                                   | `:351-363`                                  |

抽样验证（`readId3v1`，两文件 18 行完全一致）：

```kotlin
private suspend fun readId3v1(input: RangeAudioParserInput): ParsedId3Tag? {
    if (input.fileSize < ID3V1_BYTES) return null
    val bytes = input.readRange(input.fileSize - ID3V1_BYTES, ID3V1_BYTES) ?: return null
    if (bytes.size < ID3V1_BYTES || !bytes.copyOfRange(0, 3).contentEquals("TAG".toByteArray(StandardCharsets.ISO_8859_1))) {
        return null
    }
    ...
}
```

**建议**：抽取一个 `internal object Id3TagReader`（含上述全部方法 + `ParsedId3Tag` 数据类）。Mp3 / Aac 各自只保留格式特有的帧头识别（MP3 同步字检测 / ADTS 帧游走）。
- **注意事项**：`ParsedId3Tag` 当前是各自 private 的内部类，需提升为 internal 共享。
- **已有基础**：`RangeAudioParserSupport.kt` 已提供 `readUInt16/24/32BE`、`readSyncSafeInt`、`cString` 等原子工具，是天然落点。

<!-- Mark heading of section 1.2 as struck through since it has been resolved -->
### ~~1.2 FLAC 图片块解析在 FLAC 与 Ogg/Opus 之间重复 🔴~~

`FlacMetadataRangeParser.kt:149-183`（`parseFlacPictureBlock`）与 `OggOpusMetadataRangeParser.kt:137-172`（同名）结构相同：相同的 4 次 `readUInt32BE(cursor)` 游走（type/mimeLen/descLen/pictureLen）、相同的边界保护、相同的最终 `imageBytes.takeIf{...}?.let{ EmbeddedCoverBytes(...) }`。配套的 `decodeBase64Picture` 也各有一份（`:144` / `:132`）。

**建议**：抽取 `internal object FlacPictureCodec { decodeBase64(value); parseBlock(bytes) }`。

### 1.3 封面结果构造与章节兜底标题（小事，随上面一起做）🟢

- `imageBytes.takeIf { it.isNotEmpty() }?.let { EmbeddedCoverBytes(...) }` 出现在 4 个解析器（Mp3:342、Aac:263、Flac:183、Ogg:171）→ 加到 `RangeAudioParserSupport.embeddedCover(bytes, mime)`。
- `"Chapter ${index + 1}"` 兜底出现在 Mp3:424、Aac:345、Mp4:443（Mp4 变体为 `chapters.size+1`）。
- FLAC `:49-53` 手写 5 字节 `totalSamples` 读取，绕过了已有的 `readUInt32BE`。

---

## 二、日志层：7 个 ABS Logger 转发 + 两套互不兼容的时钟

<!-- Mark heading of section 2.1 as struck through since it has been resolved -->
### ~~2.1 Bearer Token 脱敏正则在 4 处被手写复制 🔴~~

完全相同的 `Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)` 被内联复制 4 次：

| 文件                                                                          | 行      |
|-----------------------------------------------------------------------------|--------|
| `abs/sync/AbsCatalogSynchronizer.kt`                                        | `:42`  |
| `abs/sync/AbsSyncTaskCoordinator.kt`（`String.redactAbsError()`）             | `:134` |
| `application/usecase/FormatSettingsRootUseCase.kt`（`redactAbsError(error)`） | `:26`  |
| `logger/CacheDiagnosticsLogger.kt`                                          | `:80`  |

**已有规范位被绕过**：`logger/AbsLogSupport.kt:24-43` 的 `AbsLogSanitizer.sanitizeText()` 不仅脱敏 Bearer，还覆盖 token JSON、password JSON、Authorization 头、token query 参数。这 4 处内联副本**只覆盖了 Bearer 一个向量**，是真实的脱敏缺口。

**建议**：4 处统一改调 `AbsLogSanitizer.sanitizeText()`。

### 2.2 7 个 ABS Logger 各自转发同一对 `mark()/elapsedMs()` 🟡

`AbsAuthLogger`、`AbsCoverLogger`、`AbsPlaybackLogger`、`AbsSettingsLogger`、`AbsStreamLogger`、`AbsSyncLogger`、`DownloadSyncLogger` 每个对象都写：
```kotlin
fun mark(): Long = AbsLogClock.mark()
fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)
```
纯转发样板。

### 2.3 三个 WorkflowLogger 逐行同构 🟡

`LibraryWorkflowLogger`、`PlaybackWorkflowLogger`、`ScanWorkflowLogger` **均为 29 行**，除 `TAG` 常量（`"LibraryFlow"` / `"PlaybackFlow"` / `"ScanFlow"`）和注释外完全相同——同样的 `info/debug/warn/error` 方法、同样的 `runCatching { Log.i/d(TAG, message) }`、`warn/error` 同样委托 `SecureLog`。

**建议**：改为 `WorkflowLogger(tag)` 工厂或一个抽象基类。

### 2.4 两套互不兼容的计时时钟 🟡

- `nanoTime()` 系：`AbsLogClock`（`AbsLogSupport.kt:13-17`），被 7 个 ABS Logger 转发。
- `SystemClock.elapsedRealtime()`（毫秒）系：`ImportTimingLogger.kt:12-15`、`LibraryLogger.kt:19`、`PlaybackTimingLogger.kt:20`、`VfsLogger.kt:22-25`——同样公式、不同时钟，无共享基类。

**建议**：统一到一个 `Clock` 抽象（接口或 composition），各 Logger 不再各自声明 `mark/elapsedMs`。注意两套时钟单位不同（ns vs ms），合并时须选定其一并全局换算。

### 2.5 `compact()` 日志截断出现 5 份 🟢

`AbsLogSupport.kt:58-61`、`CacheDiagnosticsLogger.kt:85-86`、`ImportTimingLogger.kt:55-62`、`VfsLogger.kt:176-182`，外加 `AbsLogSupport.shortId`。各自阈值不同（48/96/160/180）。

---

## 三、字符串 / 文件名 / URL 工具函数

> 说明：项目**没有 `shared/` 包**，最接近的规范位是 `ui/common/TimeUtils.kt`。下面多数是散落在角落的小重复。

### 3.1 父路径提取（`substringBeforeLast('/', missingDelimiterValue = "")`）🔴（8 处）

无规范位。出现在：
`abs/vfs/AbsSourceProvider.kt:78`、`library/availability/AvailabilityChecker.kt:133`、`library/vfs/sourceProvider/saf/SafSourceProvider.kt:201`、`library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt:573-574`（唯一具名 `parentSourcePathOf`）、`library/vfs/VfsFileInterface.kt:145`、`library/orchestrator/ExistingClaimIndex.kt:31`、`media/parser/CoverRecoveryHelper.kt:203`、`media/subtitle/SubtitleFileResolver.kt:92`。

**建议**：新建 `String.parentSourcePath()` 扩展。

### 3.2 文件名去扩展名（`substringBeforeLast('.')`）🔴（15+ 处）

无规范位，且**对"无点号"情况的处理不一致**（隐患）：有的 `ifBlank {}` 回退原值、有的用 `missingDelimiterValue`、有的两者都没做。出现在：
`BookDraftFactory.kt:158,180,290,385,392`、`HeuristicAudioAggregator.kt:116,127`、`ManifestSidecarSelectionPolicy.kt:24,32,46`、`ManifestSidecarSupport.kt:38`、`MetadataResolver.kt:152,166`、`Mp4MetadataFrameReader.kt:235`、`SubtitleFileResolver.kt:77,94,100`、`BookQueryService.kt:372`。

**建议**：统一扩展 `String.fileNameWithoutExtension()`，并明确无点号语义。

<!-- Mark heading of section 3.3 as struck through since it has been resolved -->
### ~~3.3 字节大小格式化（`formatBytes` vs `formatFileSize`）🟡~~

**已有规范位被绕过**：`ui/common/TimeUtils.kt:21` 的 `formatFileSize(Long)`（`log10/pow` 实现，已被 Detail/Cache/Settings/Download 页使用）。
而 `media/service/AndroidManualDownloadNotificationGateway.kt:133-144` 自己又写了一个 `formatBytes(Long)`（`while` 循环 + `BYTES_PER_UNIT=1024.0`），**且两者对相同输入会产生不同输出**（`.0f` vs 10 阈值取整差异）。

**建议**：删掉 `formatBytes`，通知网关改调 `TimeUtils.formatFileSize`。

<!-- Mark heading of section 3.4 as struck through since it has been resolved -->
### ~~3.4 baseUrl 规范化被 3 处内联绕过 🔴~~

**已有规范位**：`abs/auth/AbsCredentialStore.kt:98` 的 `normalizeBaseUrl` 与 `application/usecase/AbsSettingsConnectionUseCase.kt:147` 的 `normalizeAbsBaseUrlForReuse`。
**绕过点**（grep 实测）：
- `abs/net/AbsApiClient.kt:535`（`resolveBaseUrl`）
- `abs/vfs/AbsSourceProvider.kt:330`（`resolveContentUrl`）+ `:355`（`encodedPath.trimEnd('/')`）
- `abs/sync/AbsCoverCache.kt:54`（`baseUrl.trimEnd('/').toHttpUrl()`）

**建议**：升级为返回 `HttpUrl` 的 `AbsUrlResolver`，5 处共用；同时收口下条的封面 URL 构造。

### 3.5 枚举从偏好解析样板（9 处）🟡

`data/AppSettingsRepository.kt` 中 `:154,159,165,169,174,179,184,206,210` 共 9 处相同形态：
```kotlin
themeMode = preferences[PreferencesKeys.THEME_MODE]
    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
    ?: ThemeMode.System
```
亦见 `data/store/AppSettings.kt:136`、`data/service/ScanService.kt:91-92,121-122`。

**建议**：`inline fun <reified E: Enum<E>> enumValueOfOrNull(name)` 或 `Preferences.Key<String>.enumOf<E>(default)`。

### 3.6 `ImportScope.displayUri()` / `timingScopeId()` 逐字复制 🟢

`library/orchestrator/DirectoryAudioImporter.kt:284-290` 与 `library/orchestrator/ScopeOrchestrator.kt:304-310` 完全相同（两个扩展各 7 行 + 1 行）。直接抽公共扩展。

---

## 四、ABS 网络层：手动鉴权 + 双 Moshi

<!-- Mark heading of section 4.1 as struck through since it has been resolved -->
### ~~4.1 无 OkHttp 鉴权拦截器，每次请求手动塞 Header 🔴（13 处）~~

全项目 grep `Authorization.*Bearer` 实测：
- `AbsApiClient.kt` 内 **10 处** `.header("Authorization", bearer(token))`（行 187/229/252/268/290/324/355/385/529/568），靠私有 `bearer()`（:564）与 `Request.withBearerToken()`（:566-569）。
- `AbsCoverCache.kt:72` 内联 `.header("Authorization", "Bearer ${credential.token}")`。
- `AbsSourceProvider.kt:292` 内联同样字符串。
- `library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt`（132/205/273）走独立 WebDAV 鉴权。

**共 3 种不同写法**。且 grep `addInterceptor` 只返回 4 处 SSL/trust 配置，**没有任何 AuthInterceptor**。

**建议**：在共享 ABS client 上加一个 `Interceptor`，从凭证提供者读 token 并注入 `Authorization`；删除 `bearer()` 与两处内联字面量。这是收益最高的一项。

### 4.2 两份相同的 Moshi 实例 + 解析样板 🟡

`AbsCredentialStore.kt:27` 和 `AbsApiClient.kt:95` 各自 `Moshi.Builder().build()`（两实例完全相同、无自定义 adapter），后者还声明了 9 个 adapter。两处都有 `runCatching { adapter.fromJson(...) }` 的解析样板。

**建议**：抽 `object AbsJson { val moshi }`，单实例 + 统一 `parseOrError` 辅助。

<!-- Mark heading of section 4.3 as struck through since it has been resolved -->
### ~~4.3 封面 URL 构造分散 🟡~~

`AbsCoverCache.kt:54-61` 硬编码 `api/items/{id}/cover` 拼 URL；`AbsSourceProvider` 走通用 `resolveContentUrl`（:329）。两者各自重新规范化 baseUrl（见 3.4）。`ui/common/CoverImageRequestFactory.kt` 只管 Coil 请求与缓存键，**不算重复**。

**建议**：一个 `AbsUrls.cover(baseUrl, itemId)`，供两处共用。

### 4.4 进度 LWW 比较已集中，但协调封装散在 3 类 🟡

比较逻辑已规范集中在 `AbsProgressConflictResolver`（`:81-99` 用 `remoteUpdatedAt > localUpdatedAt`，`:106-116` 判断上传）。
但**协调封装**分散在 3 类：`AbsAuthorizedProgressSynchronizer.kt:104`、`AbsProgressConflictCoordinator.kt:72,155`、`AbsPlaybackSessionSyncer.kt:283,380-385`。后者 `toProgressEntity`（:380）专门为重新进入协调器而手动重建 `BookProgressEntity`，且 `currentTimeSec → globalPositionMs` 的单位换算在 `AbsPlaybackSessionSyncer.kt:383` 与 `AbsProgressConflictResolver.resolvedPositionMs`（:122-126）各有一份。

**建议**：统一 probe→decide→apply 流程；把时间单位换算下沉到 resolver/mapper。
**注意事项**：比较逻辑本身没重复，风险主要在协调层重构，优先级可低于 4.1。

### 4.5 HttpUrl 脱敏 / URL 尾部剥离重复 🟡

`HttpUrl.redactedForLog()` 在 `logger/VfsLogger.kt:168-169` 与 `library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt:519-520` 逐字相同，`abs/net/AbsApiClient.kt:571-572` 还有近似变体（不清 username/password）。配套的 `substringBefore('#').substringBefore('?')` 剥离助手在 `AbsLogSupport.kt:53,70` 与 `VfsLogger.kt:160` 各有。

**建议**：抽一个 `Urls.redactedForLog()` 扩展统一。

---

## 五、数据 / 应用层：实体映射 + 瘦网关

### 5.1 `BookEntity` / `ChapterEntity` 逐字段构造重复 🔴

`BookEntity(...)` 在 4 个独立处手工填字段（重叠字段：id/rootId/sourceType/title/author/narrator/description/year/totalDurationMs/totalFileSize/coverPath/series）：

| 站点                                                                                    | 行      |
|---------------------------------------------------------------------------------------|--------|
| `abs/mapping/AbsCatalogMapper.kt`（DTO→entity，设 status=READY、addedAt/lastScannedAt 策略） | `:43`  |
| `data/dao/BookDao.kt`（`BookMinEntity.toBookEntity()`，投影回填占位 `description=""`、若干 null） | `:66`  |
| `library/orchestrator/BookDraftFactory.kt`（单音频）                                       | `:54`  |
| `library/orchestrator/BookDraftFactory.kt`（cue）                                       | `:175` |
| `library/orchestrator/BookDraftFactory.kt`（m3u8）                                      | `:237` |

`ChapterEntity(...)` 同样重复约 7 处（BookDraftFactory:143,159,219；BookQueryService:329；Aac:302；Mp3:381；Mp4:438,522）。

**建议**：抽 `BookEntityFactory` + `ChapterEntityFactory`，接收 `BookDraftProps` / `ChapterDraftProps` 数据类。
**注意事项**：`BookMinEntity.toBookEntity()` 为投影省略列回填非空占位（`""`/`null`），需与 DTO/草稿工厂区分对待，不能简单合并。

### 5.2 瘦网关 / 单实现接口 🟡

**纯转发**（方法体 = `dao.foo()` 或 `withContext(IO){ store.foo() }`）：
- `data/service/SubtitleService.kt:21-25`——整类只是把 `subtitleResolver.loadSubtitlesForBookFile(...)` 包进 `withContext`。
- `data/service/SearchService.kt:15-41`——每个方法原样转发 `searchHistoryStore`（add/delete/clear），仅 `addToHistory` 加了 `isNotBlank()` 守卫。
- `data/service/BookQueryService.kt:106-127`——7 个筛选方法纯委托；`:157-178` 的多个方法是 `withContext(IO){ dao.foo(args) }`。

**单实现接口**（接口与实现一一对应，无第二实现，ISP 形同虚设）：`SubtitleGateway`→`SubtitleService`、`SearchHistoryGateway`→`SearchService`、`ProgressGateway`→`ProgressService`。

**God-class 反模式**：`BookQueryService`（:47-58）一个类同时实现 `BookCatalogGateway`、`BookMetadataGateway`、`BookmarkGateway`、`ChapterGateway`、`BookDeletionGateway`、`BookRootInventoryGateway` 六个网关——细粒度接口拆分被单一具体实现抵消，调用方并未获得可替换性。

**建议**：
- 若没有测试替换实现：合并 `SubtitleService/SubtitleGateway`、`SearchService/SearchHistoryGateway` 为一类。
- 抽 `inline fun io { }` 辅助消除重复的 `withContext(IO){ }` 外壳。
**注意事项**：`BookQueryService` 还持有真实逻辑（`checkCovers()` 封面自愈 :76-80、异步 `scope`），并非纯转发；按接口拆实现是较大重构，可分步。

### 5.3 ABS 镜像状态机的两段 filter/map 🟢

`abs/sync/AbsCatalogSynchronizer.kt:475-504` 两段连续的 `filter{}.map{ copy(state=…) }`（ACTIVE→STALE、STALE→REMOTE_DELETED）除状态枚举外完全相同。抽 `transitionMirrors(fromState, toState, runId, now)`。状态机本身没在别处重复。

### 5.4 Import/Export UseCase 的 `runCatching` 样板 🟢

13 个 UseCase 中只有 2 个共享 `suspend fun execute(): Result<Unit> = withContext(IO){ runCatching{…} }` 形态（`ExportUserDataUseCase.kt:15-60`、`ImportUserDataUseCase.kt:15-65`）。抽 `ioCatching { }`。**其余 11 个有意抛异常，不要强套 Result。**

<!-- Updated section 5.5 to reflect the successful deletion of the three dead transition comments -->
<!-- Mark heading of section 5.5 as struck through since it has been resolved -->
### ~~5.5 状态码魔法字符串——已迁移 🟢~~

`AudiobookSchema.kt` 已把状态码改为枚举常量，全代码 grep `"READY"/"PARTIAL"/"ABS_REMOTE"/"MISSING"/"DELETED"/"EMBEDDED"` 等字面量**零代码命中**（调用方都正确引用 `AudiobookSchema.BookStatus.READY` 等）。原有的 3 条过时重构 TODO 注释（`Mp3MetadataRangeParser.kt:390`、`Mp4MetadataFrameReader.kt:447,531`）已全部安全删除。仅有的漂移风险在集中的 `AudiobookDatabaseConverters.kt:132-134`。

---

## 六、UI 层

<!-- Updated section 6.1 to reflect that Modifier.glassOverlay has been successfully extracted and applied -->
<!-- Mark heading of section 6.1 as struck through since it has been resolved -->
### ~~6.1 玻璃 / 模糊修饰符脚手架——已修复 🔴~~

已将统一的 `Modifier.glassOverlay(hazeState, mode, shape, style)` 抽离到 `localhazematerial` 中，并应用于以下 5 个消费点：
`ui/common/BlurDialog.kt`、`BlurDropdownMenu.kt`、`BlurModalBottomSheet.kt`、`BlurSnackbar.kt`、`APlayerGlassTopBar.kt`。

这在保持各站点 shape、自定义 tint (如 Snackbar) 以及 borderMode (如 TopBar) 等特化样式的同时，消除了重复的判断与链式构造逻辑。

### 6.2 封面 AsyncImage 配置重复 🔴

6 处用相同的 `model=request, contentDescription=null, contentScale=Crop, fillMaxSize` 渲染封面：`ui/common/PlayerCover.kt:343-347`、`ui/common/AudiobookActionDialog.kt:912-916`、`ui/home/components/ListCardItem.kt:331-335`、`ui/home/components/ListItem.kt:311-315`、`ui/player/miniplayer/CompactPlayer.kt:233-239`、`ui/player/miniplayer/PillPlayer.kt:285-291`。

请求构造本身已规范集中在 `CoverImageRequestFactory.kt:140-159`（crossfade/placeholder/bitmapConfig）。

**建议**：抽 `CoverAsyncImage(request, modifier, onSuccess?, onError?)`，并把 `PlayerCover`、`CompactPlayer` 里重复的 `onSuccess { ImageProcessor.getDominantColor(...) }` 也收进去。
**注意事项**：只有 PlayerCover/CompactPlayer 提取主色；列表卡用 `isImageError` 翻转渲染回退 Box——onSuccess/onError 要做成可选回调，不要把主色提取强加给列表缩略图。

### 6.3 无共享空 / 加载 / 错误态 🔴

无共享 `EmptyState` / `LoadingState`，每屏手写 `Box(contentAlignment=Center){ Text(... onSurfaceVariant) }` 或裸 `CircularProgressIndicator()`：
`ui/player/components/ChapterList.kt:259-267`、`SubtitlesView.kt:96-97`、`search/SearchScreen.kt:485,491`、`settings/downloads/DownloadManagementScreen.kt:195,201`、`settings/recovery/DeletedBookRecoveryScreen.kt:195,201,258`、`edit/EditBookScreen.kt:309`。全 ui/ 共 27 处 `contentAlignment = Alignment.Center`。

**建议**：加 `EmptyState(message, icon?, modifier)` 与 `LoadingIndicator(size, strokeWidth)` 到 `ui/common/`。高度做成可选参数（有的用 200.dp，有的 fillMaxSize）。

### 6.4 对话框确认/取消按钮行 🟡

`TextButton(onDismiss){Text(action_cancel)} + TextButton/Button(onConfirm){...}` 反复出现无共享 `DialogActionRow`：`AudiobookActionDialog.kt:431-444`、`settings/SettingsDialogs.kt:345-361,488-502`、`cache/CacheSettingsScreen.kt:169-184`、`downloads/DownloadManagementScreen.kt:161-174`、`recovery/DeletedBookRecoveryScreen.kt:295-342`（5 对）、`SettingsScreen.kt:479-694`（6+）、`HomeViewPreferenceDialog.kt:206`、`navigation/APlayerAppDialogHost.kt:94-128`、`bookmarks/BookmarkDialog.kt:59-62`、`BookmarkList.kt:96-137`。

**建议**：`DialogConfirmDismissButtons(confirmText, onConfirm, dismissText=action_cancel, onDismiss, confirmEnabled=true, isDestructive=false)`。
**注意事项**：有的接在 Material `AlertDialog` 的 `confirmButton/dismissButton` 槽（非 Row），有的接自定义 `actions={}` 槽；helper 应返回 composable 对或支持槽式，别假定 Row。破坏性变体（error 色）对 Button（containerColor）与 TextButton（textColor）的染色路径不同。

### 6.5 顶栏颜色样板 🟢

`APlayerGlassTopBar` 已被 6 个设置/主页屏复用（HomeAppBar、About、CacheSettings、DownloadManagement、DeletedBookRecovery、SettingsScreen）。但 `ui/detail/DetailContent.kt:222,254`、`ui/edit/EditBookScreen.kt:263,287`、`ui/player/components/PlayerAppBar.kt:76,149` 直接用裸 `TopAppBar` + `TopAppBarDefaults.topAppBarColors(...)`。
**这 3 处偏离是合理的**（PlayerAppBar 要左对齐标题，Detail/Edit 要绕过 IME inset），不要硬塞进 `APlayerGlassTopBar`。只需把共享的 `topAppBarColors(...)`（haze 时 Transparent vs 背景）解析抽成 helper。

---

## 七、不该动的点（避免误收敛）

- **TimeUtils 时间格式化已完全收敛**：`ui/common/TimeUtils.kt` 是唯一时间格式化器，所有消费方都正确 import（DetailControlPanel、APlayerAppDialogHost、BookmarkList、ChapterList、PlaybackProgress…）。ui/ 内**无**绕过 `formatTime` 的内联 `String.format("%02d")` 或 `SimpleDateFormat`。无需任何动作。
- **`ui/motion/SharedElementKeys.kt` 键值无硬编码**：8 个构建器函数被全部 7 处消费方正确使用；grep 字面 key 前缀只命中 SharedElementKeys 自身。无需动作。
- **状态码魔法字符串已枚举化**（见 5.5），仅剩死注释。
- **每个类的 `private const val TAG`**（28+ 处）：这是 Android 惯用法，收敛会**降低**可 grep 性，不建议动。
- **`runCatching{}.getOrNull()`**（80+ 处）：惯用 Kotlin，非重复；仅 3.5 的枚举解析子模式值得抽。
- **媒体解析器的 range-fetch**：已统一在 `RangeAudioParserInput.readRange`，各解析器无重复 HTTP 字符串拼接。Mp4 的 `CoalescingRangeReader` 是为远程 WebDAV 窗口合并的合理特化，非复制。

---

## 八、优先级汇总表

按"收益 / 风险比"排序，可作为后续逐项收敛的工作清单：

<!-- Added 'Fix Status' column on the right side of the priority summary table to show which items have been resolved in the codebase -->
<!-- Mark resolved rows in the priority summary table as struck through -->
| #   | 项目                                       | 重复量    | 已有规范位     | 建议优先级          | 修复状态 |
|-----|------------------------------------------|--------|-----------|----------------|------|
| ~~1.1~~ | ~~ID3 解析栈 Mp3↔Aac 逐字复制~~                     | ~~\~350 行~~ | ~~❌~~         | ~~★★★ 最高，零行为风险~~   | ~~已修复~~  |
| ~~4.1~~ | ~~OkHttp 鉴权无拦截器，13 处手动塞 Header、3 种写法~~       | ~~13 处~~   | ~~❌~~         | ~~★★★~~            | ~~已修复~~  |
| ~~6.1~~ | ~~玻璃/模糊修饰符脚手架~~                              | ~~5 处~~    | ~~❌~~         | ~~★★★~~            | ~~已修复~~  |
| 6.2 | 封面 AsyncImage 配置                         | 6 处    | 🔶(请求已集中) | ★★★            | 未修复  |
| 6.3 | 无共享空/加载/错误态                              | 27 处   | ❌         | ★★★            | 未修复  |
| ~~2.1~~ | ~~Bearer 脱敏正则手写复制~~                          | ~~4 处~~    | ~~✅(被绕过)~~    | ~~★★★（脱敏缺口）~~      | ~~已修复~~  |
| 5.1 | BookEntity/ChapterEntity 逐字段构造           | 4+7 处  | ❌         | ★★☆            | 未修复  |
| 3.1 | 父路径提取                                    | 8 处    | ❌         | ★★☆            | 未修复  |
| 3.2 | 文件名去扩展名（语义不一致隐患）                         | 15+ 处  | ❌         | ★★☆            | 未修复  |
| ~~3.4~~ | ~~baseUrl 规范化被绕过~~                           | ~~3 处~~    | ~~✅(被绕过)~~    | ~~★★☆~~            | ~~已修复~~  |
| ~~3.3~~ | ~~formatBytes vs formatFileSize（输出不一致）~~     | ~~2 实现~~   | ~~✅(被绕过)~~    | ~~★★☆~~            | ~~已修复~~  |
| ~~1.2~~ | ~~FLAC 图片块解析 FLAC↔Ogg~~                      | ~~\~40 行~~  | ~~❌~~         | ~~★★☆~~            | ~~已修复~~  |
| 4.2 | 双 Moshi 实例 + 解析样板                        | 2 实例   | ❌         | ★★☆            | 未修复  |
| 2.3 | 3 个 WorkflowLogger 同构                    | 3×29 行 | ❌         | ★★☆            | 未修复  |
| 2.2 | 7 个 ABS Logger 转发 mark/elapsedMs         | 7 处    | 🔶        | ★☆☆            | 未修复  |
| 5.2 | 瘦网关 / 单实现接口 / BookQueryService god-class | 多处     | ❌         | ★★☆（重构较大）      | 未修复  |
| 6.4 | 对话框确认/取消按钮行                              | 多处     | ❌         | ★★☆            | 未修复  |
| ~~4.3~~ | ~~封面 URL 构造分散~~                              | ~~2 处~~    | ~~❌~~         | ~~★☆☆~~            | ~~已修复~~  |
| 4.5 | HttpUrl 脱敏/URL 尾部剥离                      | 3+2 处  | ❌         | ★☆☆            | 未修复  |
| 3.5 | 枚举从偏好解析样板                                | 9 处    | ❌         | ★☆☆            | 未修复  |
| 4.4 | 进度 LWW 协调封装分散                            | 3 类    | ✅(比较已集中)  | ★☆☆（重构风险）      | 未修复  |
| 2.4 | 两套互不兼容计时时钟                               | 2 套    | 🔶        | ★☆☆（需全局换算）     | 未修复  |
| 2.5 | compact() 日志截断                           | 5 份    | 🔶        | ☆☆☆            | 未修复  |
| 5.3 | ABS 镜像状态机两段 filter/map                   | 2 段    | ❌         | ☆☆☆            | 未修复  |
| 5.4 | Import/Export UseCase runCatching 样板     | 2 处    | ❌         | ☆☆☆            | 未修复  |
| 3.6 | ImportScope.displayUri() 逐字复制            | 2 处    | ❌         | ☆☆☆            | 未修复  |
| 6.5 | 顶栏颜色样板（3 处偏离合理）                          | 3 处    | 🔶        | ☆☆☆（仅抽 helper） | 未修复  |
| ~~5.5~~ | ~~状态码魔法字符串（已迁移，余 3 死注释）~~                    | ~~3 注释~~   | ~~✅~~         | ~~☆☆☆（删注释）~~       | ~~已修复~~  |

---

**附注**：本报告刻意不改动任何代码。建议落地时优先做 1.1、4.1、6.1/6.2/6.3、2.1 这批"高收益低风险"项；5.2（BookQueryService 拆分）与 4.4（进度协调重构）牵涉面较大，建议单独评估后再动。
