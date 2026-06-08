<!-- Review Artifact Purpose: Records the coordinated multi-agent engineering review in UTF-8 Markdown so future remediation work can reference stable findings, verification commands, and phased rollback-friendly recommendations. -->

# APlayer 严格工程审查报告

日期：2026-06-08  
范围：`app` Android 客户端、Gradle/CI/Manifest/Room/VFS/ABS/播放/Compose UI/测试与文档  
验证：本地执行 `.\gradlew.bat testDebugUnitTest`，结果通过；构建链包含 `compileDebugKotlin` 与 `testDebugUnitTest`。

## 审查方式

本次按用户要求调用 4 个子智能体并行审查：

- 子智能体 A：架构、模块职责、依赖方向、长期可维护性。
- 子智能体 B：隐含 Bug、异常处理、边界条件、稳定性。
- 子智能体 C：Android 生产适配、用户体验、性能。
- 子智能体 D：构建配置、发布规则、测试与文档一致性。

主审复核了关键坐标，并补充了本地源码扫描与测试结果。旧的 `docs/architecture-review/*.html` 在当前读取环境存在编码显示问题，本报告作为后续跟进的主文档。

## 总体判断

项目已经从早期大容器/大仓库形态向更清晰的依赖视图、Graph 组合根、Gateway、ReadModel、全局反馈事件流推进，方向是对的。当前最危险的问题不在“功能是否能跑”，而在发布安全、用户数据持久化、远程源明文/凭据策略、ABS 播放会话时序这些真实生产场景。

建议先停止扩大架构重构面，按阶段收口高风险项：先堵发布与安全，再修数据持久化和远程播放正确性，然后再做领域边界与 UI 适配优化。

## P0 阻断项

### P0-1 Release 签名材料和密码进入仓库

证据：

- `app/build.gradle.kts:41-54` 使用 `release.jks`，并写入 `storePassword = "password"`、`keyPassword = "password"`。
- `app/release.jks` 位于工作区。

影响：

- 如果该 keystore 用于真实发布，签名链已不可审计。
- CI、本地、正式发版无法区分测试签名和生产签名。
- 即便当前只是占位，也会把错误发布流程固化进项目。

建议：

- 将 release signing 改为环境变量或 Gradle property 注入。
- 仓库只允许 debug/CI 专用签名材料；真实 keystore 移出仓库并轮换。
- 在 `ReleasePolicyTest` 中禁止 `storePassword = "password"`、`keyPassword = "password"`、入库 release keystore。

### P0-2 远程源明文策略实际失效

当前状态：

- 已修复。新增 `UnsafeNetworkPolicy`，把 cleartext HTTP 与 insecure TLS 都收敛到全局设置策略；`AppSettings.isCleartextTrafficAllowed` 和 `AppSettings.isAllowInsecureTls` 默认均为 `false`。
- Root 创建/更新、WebDAV 连接测试、WebDAV VFS 请求、ABS REST 请求、ABS 媒体流请求、播放 preflight 均接入 `UnsafeNetworkPolicy`；播放路径已移除 `hasHttp=false` 占位，改为基于 `LibraryRootEntity.sourceUri` 判断真实远程 root。
- WebDAV per-root `allowInsecureTls` 不再参与运行时放行；旧 SharedPreferences 字段仅在保存/删除凭据时清理，unsafe TLS 只有全局开关。
- `ReleasePolicyTest` 已改为守护默认拒绝、`UnsafeNetworkPolicy` 存在，以及平台 cleartext 放行时必须有运行时入口调用策略。

证据：

- `app/src/main/res/xml/network_security_config.xml:5` 全局 `cleartextTrafficPermitted="true"`。
- 原始问题为：`AppSettingsRepository.kt:117` 默认 `isCleartextTrafficAllowed = true`。
- 原始问题为：`PlaybackManager.kt:303` 存在 `val hasHttp = false`，导致播放路径的明文拦截永远不会触发。
- WebDAV Basic Auth 与 ABS token 均可能走 HTTP 明文传输。

影响：

- 用户关闭“允许明文 HTTP”后，播放路径仍可能不拦截。
- WebDAV 密码、ABS token 可能在局域网或代理链路中明文暴露。
- 配置、运行时设置和播放行为之间优先级不清。

建议：

- 生产默认禁用明文；仅对用户显式允许的 root/host 放行。
- 在 root 创建、连接测试、VFS/ABS 播放 URL resolve 三处统一校验 scheme。
- 移除 `hasHttp=false` 占位，按 `sourceType/root` 生成真实网络策略判断。
- `ReleasePolicyTest` 增加断言：平台 cleartext 放行必须有运行时 root 级校验覆盖。

### P0-3 凭据未加密且备份规则未覆盖真实敏感存储

证据：

- `WebDavCredentialStore.kt:26` WebDAV 密码仅 Base64 后写入 `webdav_credentials` SharedPreferences。
- `AbsCredentialStore.kt:15` ABS token 写入 `abs_credentials` Preferences DataStore。
- `backup_rules.xml:3` 只 include/exclude `sharedpref`，排除项只有 `device.xml`。
- `data_extraction_rules.xml:3-8` 只排除 `sharedpref/device.xml`。
- `AndroidManifest.xml:35` 开启 `allowBackup="true"`。

影响：

- 云备份或设备迁移可能带走 ABS token 和 WebDAV 密码。
- Base64 不是加密，无法满足凭据保护要求。

建议：

- 短期：显式排除 `webdav_credentials.xml` 与 `datastore/abs_credentials*`。
- 中期：迁移到 Keystore 支撑的加密存储。
- 建立“敏感存储清单”，让 release policy 测试基于清单校验备份规则，而不是只检查 `device.xml`。

### P0-4 Room 生产路径允许静默清库

证据：

- `AppDatabase.kt:55` schema version 已到 40。
- `AppDatabase.kt:80`、`AppDatabase.kt:88` 只声明 `36->37`、`39->40`。
- `AppDatabase.kt:151-153` 注册有限迁移后仍启用 `.fallbackToDestructiveMigration(true)`。
- `app/schemas/...` 已存在多个历史版本 schema。

影响：

- 用户从 27-35、37、38 等版本升级到 40 时可能整库重建。
- 书库根、书籍、进度、书签、ABS mirror、待同步进度都属于长期用户资产，不能默认丢弃。

建议：

- 拆出 `MigrationRegistry`，补齐至少 `37->38`、`38->39`，并明确生产版本迁移链。
- release 禁止 destructive fallback；debug 可单独放宽。
- 引入 `MigrationTestHelper` 覆盖 `27->40`、`36->40`、`37->40`、`38->40`、`39->40`。

## P1 高风险项

### P1 修复状态（2026-06-08）

状态：

- P1-1：已修复。移除 Manifest 占位权限，`PlaybackService` 改为校验 controller UID 与包名绑定，并对非本应用/非系统 controller 做能力降级；新增 `ReleasePolicyTest.manifestDoesNotShipPlaceholderPermissions`。
- P1-2：已修复。`PlaybackManager.pause()`/`stopPlayback()` 改为本地控制先执行，ABS close 后台执行；close 前基于当前 `MediaController` 捕获进度快照并同步落库；pause-close 增加 latest-only 保护。
- P1-3：已修复。`setBookPlaybackPlan` 覆盖 plan 前快照旧 ABS session，后台按“关闭旧 session -> 打开新 session”顺序执行；`AbsPlaybackSessionSyncer` 用 `Mutex` 串行化 open/sync/close，并让 open idempotent。
- P1-4：已修复。`BookAvailabilityService` 保留 `AvailabilityResult`，只有确定 `NOT_FOUND` 才写 `MISSING`；TIMEOUT、NETWORK、SERVER_ERROR、AUTH_FAILED、PERMISSION_DENIED、UNKNOWN 保持原文件状态；新增 `AvailabilityPersistencePolicyTest`。
- P1-5：已修复。根编辑路径改走 `CacheEvictionCoordinator.evictRootCaches(rootId)`，统一清理 cover、directory、directory_child、VFS range cache；新增 root edit eviction 回归测试。
- P1-6：已修复。ABS offset open 在 `offset > 0` 时要求 206 或有效 `Content-Range`；服务端/代理忽略 Range 返回 200 时抛 `RANGE_IGNORED`；新增 `AbsSourceProviderStage3Test` 覆盖。
- P1-7：部分修复。已将 `PositionMapper` 从 `media` 迁移到中性 `timeline` 包，并新增 `BookDao` 不导入 media runtime 的架构守护测试；`DataGraph` 持有播放管理器、UI 直接依赖 `data.entity/AudiobookSchema`、`LibraryFacade` 宽门面仍待后续分阶段处理。

验证：

- `.\gradlew.bat compileDebugKotlin`：通过。
- `.\gradlew.bat testDebugUnitTest`：通过。

### P1-1 MediaSession 服务权限仍是占位，外部控制语义不稳定

证据：

- `AndroidManifest.xml:110-114` 中 `PlaybackService` 为 `exported="true"` 且 `android:permission="TODO"`。
- `PlaybackService.kt:386` 附近存在硬编码外部控制包名白名单。

影响：

- 锁屏、耳机、车机、Wear、OEM SystemUI、桌面 Widget 控制可能被错误拒绝。
- 安全审计会误以为已有有效权限保护。

建议：

- 移除占位权限或替换为合法权限模型。
- 按 Media3 服务连接语义做 caller 校验和能力降级，避免固定包名白名单。
- `ReleasePolicyTest` 增加 Manifest 禁止 `permission="TODO"` 的断言。

当前状态：

- 已修复。`AndroidManifest.xml` 已移除 `android:permission="TODO"`。
- `PlaybackService` 已改用 `PackageManager.getPackagesForUid(controller.uid)` 校验 controller 包名归属，并只向本应用或系统 controller 暴露自定义 rewind/forward/bookmark 控制。
- `ReleasePolicyTest` 已增加 Manifest 占位权限守护。

### P1-2 ABS 播放暂停/停止与会话关闭顺序会造成进度丢失或按钮延迟

证据：

- `PlaybackManager.kt:373-375` `pause()` 先 `closeAbsSessionIfNeeded()`，再执行本地暂停。
- `PlaybackManager.kt:478-479` `stopPlayback()` 也先 close ABS session。
- `ProgressSyncTracker.kt:51` 正常播放约 10 秒一次保存。
- `PlaybackManager.kt:508` 关闭时读取当前 plan/session，容易拿到已落库但非最新的位置。

影响：

- 慢网下用户点击暂停后，音频可能继续播放到远端 close 请求结束。
- ABS 服务端拿到旧位置，最后几秒到十几秒进度丢失。

建议：

- 控制命令优先本地生效：先 pause/stop，再后台 latest-only 上传 close/progress。
- close 前从当前 `MediaController` 计算最新位置，先本地持久化，再关闭远端 session。
- 用单协程 actor 或 mutex 串行化 ABS session open/close/sync。

当前状态：

- 已修复。`pause()` 与 `stopPlayback()` 不再等待远端 close 后才让本地播放器生效。
- close 使用 `AbsSessionSnapshot` 捕获当前 plan、文件序号、文件内位置和总时长，先通过 `ProgressGateway.saveProgress` 落库，再调用 ABS close。
- pause 的后台 close 增加 latest-only 检查，避免用户快速 pause/play 后旧 close 关闭已恢复的同一本书 session。
- `AbsPlaybackSessionSyncer` 已用 `Mutex` 串行化 open/sync/close。

### P1-3 切换 ABS 书籍时旧 session 可能不关闭

证据：

- `PlaybackManager.kt:292` 覆盖 `currentPlan`。
- `PlaybackManager.kt:294` 直接 `openAbsSessionIfNeeded(finalPlan.bookId)`。
- 旧 session 主要在 pause/stop 路径关闭。

影响：

- 播放 ABS 书 A 时直接点 ABS 书 B，A 的 session 可能长期残留。
- 服务端统计、最终进度和本地 `abs_playback_session` 状态不一致。

建议：

- `setBookPlaybackPlan` 覆盖前先快照旧 plan 和 controller 位置。
- 关闭旧 ABS session 后再打开新 session。
- 同 P1-2，将 session 操作收敛成单队列。

当前状态：

- 已修复。`setBookPlaybackPlan` 在覆盖 `currentPlan` 前会捕获旧 ABS session 快照。
- 切换书籍时后台先 close 旧 session，再 open 新 session。
- `openSession` 已做 idempotent 保护，避免恢复播放或重复 play 创建重复 ABS server session；新增 `open session should be idempotent while local session is active` 测试。

### P1-4 临时远程故障会被写成文件缺失

证据：

- `BookAvailabilityService.kt:177-188` 远程检查最终返回 Boolean。
- `BookAvailabilityService.kt:236` Boolean false 被写为 `FileStatus.MISSING`。
- `AvailabilityChecker.kt` 已有 `AvailabilityResult`，但刷新链路丢失了 `TIMEOUT/NETWORK_UNAVAILABLE/SERVER_ERROR/AUTH_FAILED` 等细节。

影响：

- WebDAV/ABS 短暂超时、弱网、5xx、认证波动会污染为“文件缺失”。
- 播放跳过曲目，书籍变成 `PARTIAL/UNAVAILABLE`，用户需要手动恢复。

建议：

- 刷新链路保留 `AvailabilityResult`。
- 只有 `NOT_FOUND`、明确 SAF 授权丢失等确定状态才写 `MISSING`。
- 临时错误保持原状态并提示重试。

当前状态：

- 已修复。刷新链路不再把远程检查折叠成 Boolean false 后直接写 `MISSING`。
- `AvailabilityPersistencePolicy` 明确持久化规则：`AVAILABLE -> READY`，`NOT_FOUND -> MISSING`，其他临时/权限/服务端状态保持原状态。
- 已新增 `AvailabilityPersistencePolicyTest` 覆盖 TIMEOUT、NETWORK_UNAVAILABLE、SERVER_ERROR、AUTH_FAILED、PERMISSION_DENIED、UNKNOWN。

### P1-5 根目录编辑清理缓存不完整

证据：

- `SettingsLibraryMaintenanceUseCase.kt:51-55` 只调用 `directoryCacheDao.deleteByRootId(rootId)`。
- `CacheEvictionCoordinator.kt:45` 删除根时会完整清理 cover、directory、directory_child、VFS range cache。

影响：

- 修改 WebDAV URL/basePath 或 SAF root 后复用旧 rootId，可能复用旧目录快照和 range cache。
- 扫描出现幽灵文件、漏扫新文件或旧 metadata/封面。

建议：

- 根编辑也走统一 root cache eviction。
- 清理 `directory_cache`、`directory_child_cache`、`VfsRangeCache.evictRoot(hash(rootId))`。
- 加回归测试覆盖 root 编辑后重新扫描。

当前状态：

- 已修复。`SettingsLibraryMaintenanceUseCase` 不再直接调用 `directoryCacheDao.deleteByRootId(rootId)`，改为调用 `CacheEvictionCoordinator.evictRootCaches(rootId)`。
- `CacheEvictionCoordinator` 增加 root 编辑可复用的 root-scoped cache eviction 方法，覆盖 directory、directory_child、cover、VFS range cache。
- 已新增 `root edit eviction should clear child directories and range blocks` 回归测试。

### P1-6 ABS offset 播放没有处理 HTTP 200 Range 忽略

证据：

- `AbsSourceProvider.kt:90` offset > 0 时发送 `Range` header。
- `AbsSourceProvider.kt:92-121` 只处理 416 和非成功响应，HTTP 200 会直接当成功流返回。
- WebDAV 在 `WebDavSourceProvider.kt:153-155` 有本地 skip 兜底，但大文件下会有性能问题。

影响：

- ABS 或代理忽略 Range 返回整文件 200 时，恢复/seek 可能从文件头读取。
- 表现为重复播放、位置错乱或解析异常。

建议：

- offset > 0 时要求 206 或有效 `Content-Range`。
- 若必须支持 200 fallback，做 bounded skip 并明确超时/失败提示。
- 连接测试记录 Range 能力；不支持随机访问的源提示用户缓存后播放。

当前状态：

- 已修复播放路径。`AbsSourceProvider.openInputStream(file, offset)` 在 offset 大于 0 时只接受 206 或有效 `Content-Range`。
- 对忽略 Range 的 HTTP 200 返回 `RANGE_IGNORED`，避免 seek/resume 从文件头读取。
- 已新增 `offset open should reject ignored range responses` 测试。
- 尚未做连接测试层面的 Range 能力记录和用户提示，留作后续体验增强。

### P1-7 架构边界仍有反向依赖和宽接口

证据：

- `BookDao.kt:14` 数据层引入 `PositionMapper`，`BookDao.kt:244-249` DAO 中做播放坐标映射。
- `DataGraph.kt:19` 持有 `PlaybackManager`，`DataGraph.kt:29` 持有 `AutoRewindManager`。
- `domain/usecase` 下多处直接依赖 DAO、凭据仓库、ABS client、具体 `PlaybackManager`。
- `LibraryFacade.kt:22` 聚合并委托大量 gateway，`PresentationDependencies.kt` 仍暴露完整 `LibraryFacade`。
- 多个 ViewModel 直接依赖 `data.entity` 和 `AudiobookSchema`。

影响：

- `domain` 命名与实际应用编排职责冲突。
- UI 场景拿到超出需要的能力，后续功能容易继续扩大门面。
- Room schema 变更会直接影响 UI 层。

建议：

- 将 `domain/usecase` 改名为 `application/usecase`，或引入端口接口后再保留 domain 命名。
- 把播放坐标映射移到不依赖 Room 的领域/时间线模块，DAO 只负责读写。
- 沿用 `HomeLibraryReadModel` 模式，为 Detail/Search/Settings/Player 分别提供场景读模型和命令接口。
- 新增包级 import 规则测试：禁止 `data -> media`、禁止 `ui -> data.entity`、限制 `domain/application -> dao`。

当前状态：

- 部分修复。`PositionMapper` 已从 `media` 包移动到 `timeline` 包，`BookDao`、ABS progress mapper、library ownership migrator 与 media runtime 共同依赖中性时间线模块。
- 已新增 `PlaybackDependencyViewArchitectureTest.bookDaoDoesNotImportMediaRuntime`，防止 `BookDao` 回退到 `media` runtime 依赖。
- 未完成项：`DataGraph` 仍持有 `PlaybackManager/AutoRewindManager`；多个 UI/ViewModel 仍直接依赖 `data.entity` 与 `AudiobookSchema`；`LibraryFacade` 与部分 presentation dependency 面仍偏宽。这些需要后续按 Detail/Search/Settings/Player 场景分阶段收口。

## P2 中期优化项

### P2-1 WorkManager 策略不区分手动同步和后台防抖

证据：

- `AbsSyncWorkScheduler.kt:28-30` 使用 `ExistingWorkPolicy.KEEP`。
- `ScanService.kt:98-100` 使用 `ExistingWorkPolicy.KEEP`。
- 未看到 `Constraints`、`NetworkType.CONNECTED`、退避策略。

影响：

- 用户修改 root 后再次同步，旧任务排队时新输入可能被丢弃。
- 离线/弱网下任务立即失败，不能等网络恢复。

建议：

- 用户触发或 root 配置变更使用 `REPLACE` 或带配置版本的 unique work。
- 自动冷启动防抖才使用 `KEEP`。
- ABS/WebDAV 同步加 `NetworkType.CONNECTED` 和退避策略。

当前状态：

- 已修复调度策略。新增 `WorkSchedulingPolicy`，集中定义 unique work 名称、`ExistingWorkPolicy`、网络约束和退避策略。
- Library sync 现在按触发来源区分：`COLD_START` 保持 `KEEP` 防抖，用户触发/root 编辑触发使用 `REPLACE`，避免新输入被旧排队任务吞掉。
- WebDAV root 编辑触发的库扫描设置 `requiresNetwork = true`，WorkManager 等待 `NetworkType.CONNECTED` 后执行；本地 SAF 用户扫描不强行卡联网。
- ABS root sync 改为 root-scoped `REPLACE`，并添加 `NetworkType.CONNECTED` 与指数退避。
- 新增 `WorkSchedulingPolicyTest` 覆盖冷启动/用户扫描、网络约束、ABS root sync 策略。

### P2-2 CI 没覆盖 release-only 风险

证据：

- `.github/workflows/ci.yml:31` 只运行 `testDebugUnitTest`。
- `app/build.gradle.kts:51` release 开启 minify/resource shrink/R8。

影响：

- R8、资源收缩、release Manifest merge、release 编译错误可能到发版才暴露。

建议：

- 增加 `lint`、`compileReleaseKotlin` 或无真实签名的 `assembleRelease` 验证。
- Release signing 外置后，用 CI 专用 keystore 或 unsigned release 验证 R8。

当前状态：

- 已修复 CI 覆盖面。`.github/workflows/ci.yml` 已拆分为独立 job：`Debug Kotlin Compile`、`Debug Unit Tests`、`Debug Android Lint`、`Release Android Lint`、`Release Assemble And R8`。
- Release lint 独立运行 `.\gradlew.bat lintRelease`，覆盖 release manifest merge、release resources 与生产构建类型 lint。
- Release/R8 独立运行 `.\gradlew.bat assembleRelease`，覆盖 release Kotlin 编译、资源收缩、R8、lintVital、release packaging。
- 已增加 `workflow_dispatch`，便于手动触发完整 CI。
- 本地验证通过：`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`lintRelease`、`assembleRelease`。
- 注意：当前 `assembleRelease` 仍依赖现有 release signing 配置；真实签名外置仍属于 P0-1 的后续修复范围。

### P2-3 ReleasePolicyTest 和架构守护测试偏字符串快照

证据：

- `ReleasePolicyTest.kt` 主要检查文档标题、release shrink、`device.xml` 排除。
- `PlaybackDependencyViewArchitectureTest.kt`、`ContainerAccessArchitectureTest.kt` 多处使用 `contains/subStringAfter`。

影响：

- 能挡一部分回归，但挡不住硬编码签名、Manifest TODO、destructive migration、凭据备份。
- 注释或格式重排可能误报，新增包路径也可能漏掉。

建议：

- 发布策略测试改成明确断言清单。
- 架构规则改成包级扫描 + deny/allow list；长期可引入 Kotlin PSI 或编译期 API 检查。

当前状态：

- 已加强当前可落地守护。`ReleasePolicyTest` 新增 CI gate 明确断言，覆盖 `compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`lintRelease`、`assembleRelease` 与 `workflow_dispatch`。
- `ReleasePolicyTest` 新增 R8 规则断言，禁止重新引入 `-dontwarn androidx.media3.**` 与 `-dontwarn coil.**`。
- `UserVisibleStringResourceTest` 已纳入 `PlayerViewModel` 与 `PlaybackControls`，防止播放器 toast 文案回退为硬编码字符串。
- `WorkSchedulingPolicyTest` 与 `VisualEffectPolicyTest` 将 WorkManager 策略和低端/省电降级策略从字符串快照提升为行为断言。
- 未完成项：P0 中 release signing、destructive migration、敏感凭据备份仍需后续完成后再加入“必须通过”的发布策略断言。

### P2-4 R8 规则过宽

证据：

- `app/proguard-rules.pro` 包含较宽的 `-dontwarn androidx.media3.**`、`-dontwarn coil.**`。

影响：

- 可能隐藏 release-only 缺失类或库升级后的 API 问题。

建议：

- 删除可由 consumer rules 覆盖的冗余规则。
- 将 `dontwarn` 缩小到具体 optional class。
- 用 release 构建验证每次规则变更。

当前状态：

- 已修复。`app/proguard-rules.pro` 已删除 `-dontwarn androidx.media3.**` 与 `-dontwarn coil.**`。
- 保留 Room、协程、Runnable、optional annotation 与 release log stripping 等当前仍有明确目的的规则。
- 新增 `ReleasePolicyTest.r8RulesDoNotSuppressWholeMedia3OrCoilPackages` 防止整包 suppress 回归。
- 本地 `.\gradlew.bat assembleRelease` 已通过，R8、资源收缩与 release packaging 未暴露新增缺失类。

### P2-5 用户可见文案资源化未完全收口

证据：

- `PlayerViewModel.kt:657` 仍有硬编码错误 toast。
- `PlayerViewModel.kt:805` 仍有 `"Bookmark added"`。
- `PlaybackControls.kt` 仍生成英文速度/睡眠 toast 文案。
- `UserVisibleStringResourceTest.kt` 已有守护，但没有覆盖全部用户可见字符串。

影响：

- 中英文混杂、本地化缺口、后续文案一致性差。

建议：

- 全量扫描 `app/src/main/java` 的用户可见字符串。
- 业务层只产生 `FeedbackMessage` key 和参数，渲染集中在 app shell。
- 日志、调试信息走白名单。

当前状态：

- 已修复本条证据中的播放器反馈。`PlayerViewModel.acceptRemoteAbsProgressConflict()` 改为发出 `FeedbackMessages.playbackRemoteProgressSaveFailed(...)`。
- `saveBookmarkFromDialog()` 改为使用 `FeedbackMessages.playbackBookmarkCreated()`，默认书签标题改为 `bookmark_default_title` 字符串资源。
- `PlaybackControls` 的速度和睡眠定时器提示不再生成英文裸字符串，改为 `FeedbackMessage` key 与格式参数。
- `PlaybackControlActions.onShowToast` 从 `(String) -> Unit` 收窄为 `(FeedbackMessage) -> Unit`，让播放器控制面只产生反馈事实。
- `UserVisibleStringResourceTest` 已加入相关资源 key 和调用方守护；遗留其他 UI 英文/中文硬编码需后续按界面分区继续资源化。

### P2-6 大屏、低端机和折叠屏适配仍偏静态

证据：

- `WindowClass.kt` 主要按宽高和横竖屏分类。
- Haze、全屏封面背景、小播放器动画在低端机上缺少自动降级策略。

影响：

- 分屏、自由窗口、折叠屏铰链、外接显示可能进入错误布局分支。
- 低内存/GPU 压力场景可能出现卡顿。

建议：

- 引入窗口姿态、fold feature、输入设备信息。
- 为播放器、详情页、设置页建立设备矩阵截图测试。
- 按低 RAM、省电模式、Haze 开关自动降级到 Material 背景或静态封面。

当前状态：

- 部分修复低端/省电降级。新增 `VisualEffectPolicy` 与 `VisualEffectEnvironment`，根据 `ActivityManager.isLowRamDevice` 和 `PowerManager.isPowerSaveMode` 将请求的 `GlassEffectMode.Haze` 自动降级为 `Material`。
- `APlayerApp` 现在统一计算 `activeGlassEffectMode`，并把有效模式传给 NavHost、Detail、MiniPlayer、Edit、Player、Search、Settings 和全局 DialogHost。
- 新增 `VisualEffectPolicyTest` 覆盖低 RAM、省电模式降级和正常设备保留 Haze。
- 未完成项：窗口姿态、fold feature、外接输入设备、设备矩阵截图测试仍未建立，需要后续配合更大范围 UI 适配推进。

## 已改善但需要继续守住的方向

- 依赖视图已经落地：`APlayerApplication` 提供 `getPlaybackRuntimeDependencies`、`getVfsPlaybackDependencies`、`getHomeScreenDependencies` 等窄入口。
- Graph 拆分已经落地：`DataGraph`、`MediaGraph`、`LibraryGraph`、`AbsGraph`、`UiEventGraph` 提高了组合根可读性。
- `GraphLifecycleTest` 已覆盖 close order 和 lazy resource close，但仍需补真实资源行为测试。
- `PlaybackSessionState` 已把首帧失败与运行时失败分类集中，后续可继续扩展为播放会话状态机。
- `VfsPlaybackDataSource` 已显式记录 active loader thread，中断 watcher 方向已修正。
- `FeedbackMessage`、`AppEventSink`、`AppFeedbackRenderer` 已把很多 toast 从 ViewModel/Service 中移出，但遗留 String 路径还需收口。

## 可回归分阶段计划

### Phase 0：发布与安全止血

目标：只动配置、发布策略测试、凭据备份/加密策略，不做架构大迁移。

- 外置 release signing，移除入库 release 密码和真实 keystore。
- 移除 `android:permission="TODO"` 或替换为合法权限模型。
- 显式排除 ABS/WebDAV 凭据备份，或迁移加密存储。
- 默认禁用明文，修复 `PlaybackManager` 明文判断占位。
- 扩展 `ReleasePolicyTest` 覆盖以上契约。

回归命令：`.\gradlew.bat testDebugUnitTest`，再补 `lint`/release compile 后纳入 CI。

### Phase 1：数据持久化安全

目标：保护用户长期资产。

- 建立 `MigrationRegistry`。
- 补齐历史 schema 到 40 的迁移链。
- release 禁止 destructive fallback。
- 引入 Room migration fixture 测试。

回归重点：从多个历史版本升级后，书库根、书籍、章节、书签、进度、ABS mirror 不丢失。

### Phase 2：远程播放正确性

目标：修复 ABS/WebDAV 在慢网、断网、seek、切书、暂停时的行为一致性。

- ABS session open/close/sync 串行化。
- pause/stop 本地立即生效，远端 close 后台 latest-only。
- 可用性刷新保留 `AvailabilityResult`，临时故障不写 `MISSING`。
- ABS/WebDAV Range 能力显式检测，offset > 0 不接受不可信 200。
- root 编辑走统一 cache eviction。

回归重点：慢网、断网、Range ignored、切书、暂停、睡眠定时器。

### Phase 3：应用层边界收口

目标：降低长期维护成本，不追求一次性拆多模块。

- 明确 `domain/usecase` 是 `application/usecase`，或引入端口后再保留 domain。
- 切断 `data -> media` 反向依赖。
- 把 UI 从 Room entity/AudiobookSchema 上移到场景模型。
- 为 Detail/Search/Settings/Player 建立场景级 ReadModel/Command 接口。

回归重点：包级依赖测试、现有 UI 行为不变。

### Phase 4：生产体验与工具链

目标：让真实设备和发布流程更可预测。

- CI 加 release/lint/R8 验证。
- 缩小 R8 `dontwarn`。
- 建立 `.editorconfig`，再评估 ktlint/detekt/spotless。
- 大屏/折叠屏/低端机截图或基准测试。
- 文案资源化全量收口。

## 首要建议

先做 Phase 0。理由很直接：签名材料、明文策略、凭据备份、Manifest TODO、destructive migration 都是发布前必须止血的问题，且改动面相对小、可回归、收益最高。等这些生产边界稳住后，再推进 ABS 播放时序和架构边界收口。
