# APlayer 代码审查报告（Code Review Report）

- **项目**：APlayer（`com.viel.aplayer`）—— Android 有声书播放器
- **审查分支**：`uirefactor`（基线提交 `2416bd5`）
- **审查日期**：2026-05-30
- **审查方式**：静态代码审查（按 `docs/cc/code_review_plan.md` 的 7 单元 / 12 维度执行）
- **代码规模**：约 180 个 Kotlin 文件，约 32,740 行
- **配套文档**：`docs/cc/code_review_plan.md`

> 说明：所有 **Critical/High** 发现均已对当前分支源码逐条打开核实；部分 Medium/Low 标注"子系统审查结论，建议复验"。`.kiro/` 历史审查仅作交叉参考，结论独立成立。

---

## 1. 执行摘要（Executive Summary）

APlayer 是一个架构相当完整、工程化程度高的有声书播放器：清晰的分层（Data → Gateway → Service → Facade → UI）、进行中的"无状态化 Compose"重构、自研的 VFS（SAF/WebDAV）与字节范围元数据解析、Media3 后台播放与 Glance 小组件。整体代码质量与设计意识在个人项目中属上乘，且已存在若干良好的防御性实践（见 §8）。

但本次审查发现了**若干必须在发布前修复的安全与数据完整性问题**，集中在三个方面：

1. **机密物料管理**：发布签名 keystore 连同明文口令被提交入库——这是最高优先级问题。
2. **数据持久化安全网缺失**：数据库唯一初始化路径启用"破坏性迁移"，且数据层**完全没有写事务**，多步写入序列在异常/取消时会留下撕裂状态或丢数据。
3. **组件暴露面**：导出的小组件接收器可被任意 App 驱动播放并强拉前台服务；播放服务的导出权限是占位字符串 `"TODO"`。

此外，远程（WebDAV）凭据以可逆的 Base64 存储且被纳入云备份；播放数据源在 ExoPlayer 加载线程上使用不可中断的 `runBlocking` 处理远程 I/O；UI 层存在若干热路径重组开销与系统性的硬编码文案问题。**当前没有任何自动化测试**。

### 1.1 发现统计

| 严重级别                  | 数量   |
| ------------------------- | ------ |
| **Critical（严重）**      | 2      |
| **High（高）**            | 7      |
| **Medium（中）**          | 18     |
| **Low / Info（低/提示）** | 13     |
| **合计**                  | **40** |

| 维度              | 发现数 |
| ----------------- | ------ |
| D2 并发/线程      | 7      |
| D3 安全/隐私      | 7      |
| D4 资源/生命周期  | 6      |
| D9 数据完整性     | 5      |
| D7 Compose 正确性 | 5      |
| D1 正确性         | 4      |
| D6 性能/内存      | 4      |
| D5 架构           | 3      |
| D8 错误处理       | 2      |
| D10 国际化        | 1      |
| D11/D12 构建/质量 | 3      |

### 1.2 Top 风险（按优先级）

1. **C-01** — 发布签名 keystore 与口令入库（`app/release.jks` + `build.gradle.kts:38-45`）。
2. **C-02** — `fallbackToDestructiveMigration(true)` 静默清库（`AppDatabase.kt:60`）。
3. **H-06** — 数据层零写事务，多写非原子导致数据丢失/撕裂。
4. **H-03 / H-04** — 导出组件暴露面（widget 接收器 + `permission="TODO"`）。
5. **H-01 / H-02** — WebDAV 凭据可逆存储+入备份；明文 HTTP 传输凭据。

---

## 2. 发现索引（Findings Index）

| ID       | 级别       | 维度      | 标题                                                         | 位置                                                |
| -------- | ---------- | --------- | ------------------------------------------------------------ | --------------------------------------------------- |
| C-01     | Critical   | D3        | 发布签名 keystore 入库且口令硬编码                           | `build.gradle.kts:38-45`                            |
| C-02     | Critical   | D9        | 生产唯一初始化路径启用破坏性迁移                             | `AppDatabase.kt:60`                                 |
| H-01     | High       | D3        | WebDAV 凭据 Base64 存储且被纳入云备份                        | `WebDavCredentialStore.kt:26,47`                    |
| H-02     | High       | D3        | 全局明文 HTTP，凭据明文传输无应用层拦截                      | `network_security_config.xml:5`                     |
| ~~H-03~~ | ~~High~~   | ~~D3/D4~~ | ~~[已修复] 导出 widget 接收器可被任意 App 控制播放~~          | `PlayerWidgetReceiver.kt:40`                        |
| H-04     | High       | D11/D3    | PlaybackService 导出且 `permission="TODO"`                   | `AndroidManifest.xml:97`                            |
| ~~H-05~~ | ~~High~~   | ~~D2/D4~~ | ~~[已修复] loader 线程 runBlocking 远程 I/O 不可中断~~       | `VfsPlaybackDataSource.kt:41,47`                    |
| ~~H-06~~ | ~~High~~   | ~~D9/D2~~ | ~~[已修复] 数据层零写事务，多写序列非原子~~                 | `BookQueryService.kt:253` 等                        |
| ~~H-07~~ | ~~High~~   | ~~D5/D4~~ | ~~[已修复] container lateinit 无初始化顺序/多进程守卫~~     | `APlayerApplication.kt:16`                          |
| ~~M-01~~ | ~~Medium~~ | ~~D6~~    | ~~[已修复] 元数据/封面解析持有完整字节数组致 OOM 风险~~       | `Mp4MetadataFrameReader`、`ImageProcessor`          |
| ~~M-02~~ | ~~Medium~~ | ~~D1/D6~~ | ~~[已修复] FLAC/Ogg 图片块长度字段未校验负值~~               | `FlacMetadataRangeParser.kt:40`                     |
| ~~M-03~~ | ~~Medium~~ | ~~D1~~    | ~~[已修复] MP4 v1 64 位时长换算整型溢出~~                     | `Mp4MetadataFrameReader.kt:407`                     |
| M-04     | Medium     | D2        | `uiEvents` SharedFlow 容量 1 可丢事件                        | `PlaybackManager.kt:78`                             |
| ~~M-05~~ | ~~Medium~~ | ~~D4~~    | ~~[已修复] PlaybackManager Listener 未在 release 前移除~~    | `PlaybackManager.kt:180,416`                        |
| ~~M-06~~ | ~~Medium~~ | ~~D6~~    | ~~[已修复] checkCovers() 在 Flow.map 中做磁盘 I/O~~          | `BookQueryService.kt:61`                            |
| ~~M-07~~ | ~~Medium~~ | ~~D4/D2~~ | ~~[已修复] Service 协程作用域永不取消~~                      | `LibraryRootService.kt:52` 等                       |
| M-08     | Medium     | D9        | 软删除书籍的文件归属污染重扫去重                             | `BookDao.kt`, `BookQueryService.kt:125`             |
| ~~M-09~~ | ~~Medium~~ | ~~D9~~    | ~~[已修复] 破坏性迁移遗留磁盘封面文件~~                      | `AppDatabase.kt:60`                                 |
| ~~M-10~~ | ~~Medium~~ | ~~D3~~    | ~~[已修复] 未转义路径字符串拼接 JSON~~                       | `OwnershipClaimStep.kt:286`                         |
| ~~M-11~~ | ~~Medium~~ | ~~D4~~    | ~~[已修复] WebDAV openInputStream 响应泄漏+提前记成功~~      | `WebDavSourceProvider.kt:139`                       |
| ~~M-12~~ | ~~Medium~~ | ~~D8~~    | ~~[已修复] PROPFIND 响应体无大小上限全量解析~~               | `WebDavSourceProvider.kt:252`                       |
| ~~M-13~~ | ~~Medium~~ | ~~D6~~    | ~~[已修复] 无 widget 时仍每次播放事件查库+刷新~~             | `PlaybackService.kt:392`                            |
| ~~M-14~~ | ~~Medium~~ | ~~D4/D6~~ | ~~[已修复] Glance 组合内同步解码 Bitmap~~                    | `PlayerWidget.kt:77`                                |
| ~~M-15~~ | ~~Medium~~ | ~~D2~~    | ~~[已修复] 扫描回调读 WhileSubscribed 门控的 uiState~~       | `LibraryViewModel.kt:171`                           |
| ~~M-16~~ | ~~Medium~~ | ~~D7~~    | ~~[已修复] SearchOverlay 用非生命周期感知的 collectAsState~~ | `SearchOverlay.kt:37`                               |
| M-17     | Medium     | D7        | 热路径误用 `remember`/`derivedStateOf`                       | `SubtitlesView.kt:51`、`PlaybackProgress.kt:41`     |
| ~~M-18~~ | ~~Medium~~ | ~~D1~~    | ~~[已修复] 未播放保护期 300ms vs 3000ms 不一致~~             | `DetailViewModel.kt:56`                             |
| L-01     | Low        | D10       | 用户文案大面积硬编码（中英混杂）绕过 strings.xml             | 系统性                                              |
| L-02     | Info       | D12       | 项目零自动化测试                                             | `app/src/test`、`androidTest` 空                    |
| ~~L-03~~ | ~~Low~~    | ~~D3~~    | ~~[已修复] local.properties 被 git 跟踪~~                    | git index                                           |
| ~~L-04~~ | ~~Low~~    | ~~D2~~    | ~~[已修复] RunClaimLedger 共享可变 Map 非线程安全~~          | `RunClaimLedger.kt:9`                               |
| ~~L-05~~ | ~~Low~~    | ~~D5~~    | ~~[已修复] DI 容器混用注入与 ambient 单例、重复解析 DB~~     | `AppContainer.kt:252`                               |
| ~~L-06~~ | ~~Low~~    | ~~D1~~    | ~~[已修复] 失效的路由守卫与无操作分支~~                      | `MiniPlayerOverlay.kt:69`、`PlayerViewModel.kt:369` |
| ~~L-07~~ | ~~Low~~    | ~~D7~~    | ~~[已修复] context as Activity 非安全强转~~                  | `Theme.kt:94`                                       |
| ~~L-08~~ | ~~Low~~    | ~~D8~~    | ~~[已修复] LibrarySyncWorker 瞬时失败不重试~~                | `LibrarySyncWorker.kt:26`                           |
| L-09     | Low        | D11       | `compileSdk=37` 为非稳定/预览 SDK                            | `build.gradle.kts:17`                               |
| ~~L-10~~ | ~~Low~~    | ~~D5~~    | ~~[已修复] `DetailContent` 重新包装已拆解的 UiState~~        | `DetailContent.kt:140`                              |
| L-11     | Info       | D3        | 发布构建已剥离 Log.v/d/i（隐私缓解，正向）                   | `proguard-rules.pro`                                |
| ~~L-12~~ | ~~Low~~    | ~~D2~~    | ~~[已修复] CUE 帧→毫秒未校验 frames∈0..74~~                  | `CueManifestParser.kt:201`                          |
| ~~L-13~~ | ~~Low~~    | ~~D6~~    | ~~[已修复] PillCompactMediaPlayer 旋转角度无界增长~~         | `PillCompactMediaPlayer.kt:94`                      |

---

## 3. Critical 发现明细

### C-01 — 发布签名 keystore 入库且签名口令硬编码

- **维度**：D3 安全 ｜ **位置**：`app/build.gradle.kts:38-45`，`app/release.jks`（已被 git 跟踪）
- **证据**：
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file("release.jks")
          storePassword = "password"
          keyAlias = "aplayer"
          keyPassword = "password"
      }
  }
  ```
  `git ls-files` 确认 `app/release.jks` 在版本库中。
- **影响**：任何能访问该仓库的人都能用本应用的发布密钥签名 APK。一旦应用发布到任何分发渠道，该密钥即被视为已泄露——攻击者可制作系统认定为"官方更新"的恶意包，冒用 `signature` 级权限、共享 UID 等。签名密钥一旦泄露**无法在不破坏既有安装升级链的情况下轮换**。
- **修复**：
  1. 立即将 `release.jks` 从 git 历史中清除（`git filter-repo` / BFG），并**视该密钥为已泄露、重新生成新密钥**。
  2. 将 keystore 移出仓库；口令通过 `local.properties`/环境变量/CI Secret 注入：
     ```kotlin
     storePassword = System.getenv("APLAYER_STORE_PASSWORD") ?: providers.gradleProperty("storePassword").orNull
     ```
  3. 将 `*.jks`、`*.keystore` 加入 `.gitignore`。

### C-02 — 数据库唯一初始化路径启用 `fallbackToDestructiveMigration(true)`

- **维度**：D9 数据完整性 ｜ **位置**：`app/src/main/java/com/viel/aplayer/data/db/AppDatabase.kt:60`
- **证据**：
  ```kotlin
  Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "aplayer_database")
      .fallbackToDestructiveMigration(true)   // version = 33
      .build()
  ```
  该 builder 是 App 唯一的生产初始化路径（`getInstance`）。
- **影响**：任何 schema 版本不匹配（例如开发者忘记升 version、或安装了旧版后再装新版）都会**静默删除整库重建**——用户的**收听进度、书签、书库根配置、扫描状态**全部丢失，且无任何提示或备份。对一个核心价值就是"记住你听到哪儿"的应用，这是灾难性的数据丢失风险。版本号已达 33，说明历史上经历过多次结构变更。
- **关联**：见 M-09（破坏性重建还会遗留磁盘上的封面/缩略图文件，永久无法回收）。
- **修复**：
  1. 为生产发布提供真实的 `Migration` 链；保留 `exportSchema=true` 已导出的 schema 用于编写迁移。
  2. 若确需兜底，至少改为 `fallbackToDestructiveMigrationOnDowngrade()` 仅在降级时清库，升级走显式迁移。
  3. 发布前对关键表（progress/bookmark/library_root）增加导出/恢复能力。

---

## 4. High 发现明细

### H-01 — WebDAV 凭据以可逆 Base64 存储，且被纳入云备份/设备迁移

- **维度**：D3 安全 ｜ **位置**：`WebDavCredentialStore.kt:26,47`；`res/xml/backup_rules.xml:3`；`AndroidManifest.xml`（`allowBackup="true"`）
- **证据**：
  ```kotlin
  val encodedPassword = Base64.encodeToString(password.toByteArray(...), Base64.NO_WRAP)  // 仅编码，非加密
  ...
  String(Base64.decode(encodedPassword, ...), Charsets.UTF_8)                              // 可直接还原
  ```
  存入 `getSharedPreferences("webdav_credentials", MODE_PRIVATE)`；而 `backup_rules.xml` 为 `<include domain="sharedpref" path="."/>`（仅排除 `device.xml`），`allowBackup="true"`。代码注释亦自承"后续可无侵入替换为 Keystore/加密存储"。
- **影响**：Base64 是编码不是加密，密码可被任何拿到该 prefs 文件者（root、备份提取、设备迁移镜像）瞬间还原。又因备份规则包含全部 sharedpref，**WebDAV 账号密码会被上传至云备份并随设备迁移转移**，离开设备边界。
- **修复**：
  1. 改用 `EncryptedSharedPreferences` + Android Keystore（`MasterKey`）存储凭据。
  2. 在 `backup_rules.xml` / `data_extraction_rules.xml` 中**显式排除** `webdav_credentials` prefs。
  3. 考虑将 `allowBackup` 收窄或对敏感域单独排除。

### H-02 — 全局允许明文 HTTP，Basic 认证凭据可明文传输且无应用层拦截

- **维度**：D3 安全 ｜ **位置**：`res/xml/network_security_config.xml:5`；`WebDavSourceProvider`（`applyAuth`/`urlFor`）
- **证据**：`cleartextTrafficPermitted="true"` 基础全局开启；XML 注释声称"由 AppSettings 在应用层动态拦截过滤"，但该子系统中**不存在**这样的拦截——`urlFor` 按 `root.sourceUri` 原样构造请求，`applyAuth` 无视 scheme 附加 Basic 凭据。`AppSettings.isCleartextTrafficAllowed` 默认 `true`。
- **影响**：用户添加 `http://` WebDAV 根时，其用户名/密码（Basic Auth）以明文经网络发送，可被链路上的中间人截获，且没有任何警告或显式 opt-in。注释承诺的"应用层防护"是不存在的。
- **修复**：默认 `isCleartextTrafficAllowed=false`；用 `domain-config` 收紧明文范围；对 `http://` 根强制显式 opt-in，并在 Basic 凭据将经明文传输时警示用户。

### ~~H-03 — 导出的小组件接收器可被任意 App 广播驱动播放并强拉前台服务~~

- **维度**：D3 安全 / D4 生命周期 ｜ **位置**：`AndroidManifest.xml:79-89`；`widget/PlayerWidgetReceiver.kt:40-91`
- **已修复**：引入了专用的、非公开的（`exported="false"`）播控广播接收器 `PlayerWidgetActionReceiver`。将快进、快退、播放/暂停等媒体控制指令全部剪切至此安全接收器中，导出的 Glance 接收器 `PlayerWidgetReceiver` 仅拦截基础数据更新 `APPWIDGET_UPDATE`。在 `PlayerWidget.kt` 中将 PendingIntent 的目的 Class 规范路由指向该非导出接收器。外部任何 App 发送虚假控制广播的行为将被 Android 操作系统自身直接物理拦截丢弃，完全化解了前台播放服务被恶意拉起与控制的重大漏洞。

### H-04 — PlaybackService 导出且 `android:permission="TODO"`（占位字符串）

- **维度**：D11 构建 / D3 安全 ｜ **位置**：`AndroidManifest.xml:97-99`
- **证据**：
  ```xml
  <service android:name=".media.service.PlaybackService"
      android:exported="true"
      android:foregroundServiceType="mediaPlayback"
      android:permission="TODO"> ...
  ```
- **影响**：`"TODO"` 不是有效权限常量，是明显的未完成占位。其运行时行为有歧义且都需修复：
  - 若系统将其视为未定义权限，则**没有任何 App 能满足**——可能连系统的媒体按钮路由/Android Auto/蓝牙媒体键都被挡在外面，导致外部媒体控制功能损坏；
  - 无论如何，这都不是预期的安全配置，是发布阻断项。
- **修复**：移除 `android:permission` 占位，依赖已实现的 `onConnect` 包名白名单（`PlaybackService.kt:298-307`）；若确需权限则声明一个真实的 `signature` 级权限。验证 `exported=true` 是否为系统媒体控制所必需（通常是）。

### ~~H-05 — 播放数据源在 ExoPlayer 加载线程上 `runBlocking` 处理远程 I/O（不可中断）~~

- **维度**：D2 并发 / D4 生命周期 ｜ **位置**：`media/VfsPlaybackDataSource.kt:41,47`
- **已修复**：重构了 `VfsPlaybackDataSource.open` 的查库及远程流打开流程。在两个 `runBlocking` 内部均引入了运行在 `Dispatchers.Default` 上的高频（50ms）线程打断守护协程（`interruptWatcher`）。一旦 loader 线程被 ExoPlayer 调用了 `Thread.interrupt()` 中断，守护协程会在毫秒级内感知并物理取消当前的协程 `Job`（强行抛出 `CancellationException` 打断底层的挂起流），随后将其中断原委以 `InterruptedIOException` 规范向上抛出，彻底根成了切换或暂停音频时 loader 线程僵死、系统 ANR 崩溃以及连接泄漏的隐患。
- **证据**：
  ```kotlin
  val file = runBlocking { fileLookup.getBookFileById(bookFileId) }       // DB 查询
  val stream = runBlocking { fileReader.open(file, dataSpec.position) }   // WebDAV → HTTP Range
  ```
  `open()` 由 ExoPlayer 内部 loader 线程调用。`read()`（:89）用普通阻塞 `stream.read`（这是正确的）。
- **影响**：`open()` 中对 WebDAV 的 `runBlocking` 会在 loader 线程上做网络握手且**不可被协程取消打断**；远程连接挂起时该线程无限阻塞，ExoPlayer `release()`/停止无法中断它，可能导致 loader 线程僵死、停止时 ANR、连接资源占用。注：DataSource 在 loader 线程阻塞本身是 ExoPlayer 的契约，问题在于"远程网络 + 不可中断的 runBlocking"。
- **修复**：为远程源提供**可中断的同步打开 API**（响应 `Thread.interrupt()` 关闭 socket），或将 `BookFileEntity`/流的解析前置到 loader 线程之外完成；`runBlocking` 仅用于快速本地 DB 查询。

### ~~H-06 — 数据层完全没有写事务，多步写入序列非原子~~

- **维度**：D9 数据完整性 / D2 并发 ｜ **位置**：`BookQueryService.kt:253-261`、`CoverService.kt:101-123`、`ProgressService.kt:38-76`
- **已修复**：为全数据层多步写入序列引入了强原子写事务支持。
  1. **DAO 层原子章节覆盖**：在 `ChapterDao` 中提供了基于 Room 原生 `@Transaction` 事务保护的 `replaceChapters` 挂起方法，将旧章节清除与新章节批量插入融合在单次物理写事务中，排除异常抛出与协程取消下的零章节脏状态残留，彻底杜绝了并发 Flow 订阅时的瞬时空列表闪烁。
  2. **高频进度写事务自愈**：在 `BookDao` 中设计了标注了 `@Transaction` 的 `updateProgressWithReadStatus` 挂起方法，把“进度读取 -> 音轨映射换算 -> 进度落盘 -> 书籍联动更新阅读状态”的读改写动作高度内聚地合并为单一原子事务。依托数据库排它锁，自动以完全串行化的机制执行高频进度上报，彻底消灭了不同协程交错写导致的“进度回退”竞态。
  3. **跨 DAO 业务层事务包裹**：重构了 `CoverService` 传入并注入 `AppDatabase`，在其强制重扫 `forceRegenerateCoverAndMetadata` 中使用 Room 官方提供的 `database.withTransaction { }` 强原子包裹书籍元数据覆盖、详情修改、章节删插这连续四步写 Room 操作，确保多表连携修改的 ACID 一致性。


### ~~H-07 — APlayerApplication.container 为 lateinit，跨组件强转访问无初始化顺序/多进程守卫~~

- **维度**：D5 架构 / D4 生命周期 ｜ **位置**：`APlayerApplication.kt:16-21`；`(appContext as APlayerApplication).container` 散布于 13+ 处（含 `LibrarySyncWorker`、`VfsPlaybackDataSource.Factory`、`PlaybackManager`）
- **已修复**：重构了 `APlayerApplication` 的容器获取机制。将原先的 `lateinit var container` 改造为只读的 getter 计算属性，无缝向前兼容了全部已有的直接强转读取代码。在伴生对象中设计了受 `@Volatile` 保护的双重锁校验 (Double-Check Locking) 惰性静态获取方法 `getContainer(Context)`，能够在外围组件（如 `ContentProvider`、非主进程等）发生早于 `onCreate` 的调用或高并发请求时，自动且线程安全地初始化 `DefaultAppContainer` 并缓存单例，彻底消灭了未初始化崩溃（`UninitializedPropertyAccessException`）。同时，将 `LibrarySyncWorker` 和 `VfsPlaybackDataSource.Factory` 处的强转逻辑主动替换为调用静态 `APlayerApplication.getContainer`，达成了绝对的时序与类型安全。

---

## 5. Medium 发现明细

> 以下为影响范围有限或触发条件较窄、但应纳入修复计划的问题。媒体解析类（M-01~M-03）涉及对**不受信任/损坏媒体文件**的健壮性。

- ~~**M-01 [D6] [已修复] 元数据/封面解析持有完整字节数组致 OOM**~~（`Mp4MetadataFrameReader` `MAX_COVER_BYTES=16MB`、`ImageProcessor.saveEmbeddedImage`）：封面字节整块驻留内存，`MetadataResolver` 允许 4 路并发、`CoverRecoveryHelper` 2 路，叠加大 M4B 封面 + 位图解码可触顶。`largeHeap="true"` 即为治标。**已修复**：将内嵌封面提取限制 `MAX_COVER_BYTES` 安全降低至 6MB，同时重构 `ImageProcessor.saveEmbeddedImage`。在内嵌大字节落盘为物理文件后，缩略图直接通过 `createThumbnailFromFile` 借助 `inSampleSize` 采样率下采样解码，完全释放了堆内存中的大位图同步多重解析开销。
- ~~**M-02 [D1/D6] [已修复] FLAC/Ogg 图片块长度字段未校验负值**~~（`FlacMetadataRangeParser.kt:40` 一带）：`descriptionLength`/`mimeLength` 为大无符号值 `.toInt()` 后可为负，使游标后退或绕过 `+20` 守卫，崩 `IndexOutOfBounds`/`NegativeArraySize`。**已修复**：将长度字段均改用 `Long` 级读取并在转 `Int` 前以物理剩余区间 `[0, bytes.size - cursor]` 进行强拦截，防止了大无符号数溢出为负或累加整数溢出，彻底杜绝了越界崩溃隐患。
- ~~**M-03 [D1] [已修复] MP4 v1 64 位时长换算溢出**~~（`Mp4MetadataFrameReader.kt:407,576,931`）：`duration*1000L` 对大 `Long` 溢出 → 负/错 `durationMs` 传入 `PositionMapper`/`ChapterTimeline`，破坏 seek 与进度。**已修复**：重构 `durationToMs` 函数，采用数学分配律除余拆解算法 `(duration / timescale) * 1000L + ((duration % timescale) * 1000L) / timescale` 并在计算后调用 `coerceAtLeast(0L)` 强制夹取非负，从根源上消除了数值越界和负时长的产生隐患。
- **M-04 [D2] `uiEvents` SharedFlow 容量 1、`tryEmit` 可丢事件**（`PlaybackManager.kt:78,136-141,520`）：配置变更（UI collector 脱离）或两事件紧邻时 `tryEmit` 返回 false 静默丢失，导致"分轨不可用"自愈弹窗/静音跳过提示不出现。**修复**：增大 `extraBufferCapacity` 或检查 `tryEmit` 返回值。
- **M-05 [D4] [已修复] `Player.Listener` 未在 `release()` 前移除**（`PlaybackManager.kt:180,416-425`）：`release()` 仅 `releaseFuture`，匿名 Listener 及其捕获 of controller/scope 在 future 解析前仍被持有；`release()` 还在 `synchronized` 块外将 `INSTANCE=null`。**修复**：保存 Listener 引用并 `removeListener`，`INSTANCE=null` 纳入 `getInstance` 同一把锁。
- **M-06 [D6] [已修复] checkCovers() 在 Flow.map 内做同步磁盘 I/O**（`BookQueryService.kt:61-63,81-86`）：每次列表发射对每本书 `File(...).exists()`，运行在 collector 线程（常为主线程）→ 大库 jank/ANR。**修复**：`flowOn(IO)` 该副作用并去抖。
- **M-07 [D4/D2] [已修复] Service 协程作用域永不取消**（`LibraryRootService.kt:52` + `ProgressService`/`CoverService`/`BookQueryService`/`ScanService`）：`CoroutineScope(IO+SupervisorJob())` 无关闭钩子；`LibraryRootService.init` 起了一个永不结束的 `collect{}`。若非真单例则泄漏。**修复**：作用域绑定可管理生命周期，或用 `stateIn` 绑定有界 scope。
- **M-08 [D9] 软删除书籍的文件归属污染重扫去重**（`BookQueryService.kt:125-131` 软删 + `BookDao.getAllBookFilesOnce()`）：`ExistingClaimIndex` 由全量 `book_files` 构建，未排除 `status='DELETED'` 书籍的 claim → 重扫时新文件可能被匹配到死书或被跳过。**修复**：claim 查询排除 DELETED，或软删时释放 `book_files` claim。
- **M-09 [D9] [已修复] 破坏性迁移遗留磁盘封面文件**（关联 C-02）：清库绕过 `deleteLibraryRootDataOnly` 的封面删除路径，磁盘缓存永久成孤儿且路径记录已失，无法回收。**修复**：DB 重建后扫一遍封面目录清理。
- **M-10 [D3] [已修复] 未转义路径字符串拼接 JSON**（`OwnershipClaimStep.kt:286,298`）：`payloadJson`/`message` 用 `${source.sourceUri}`/`${source.displayName}` 原始插值，文件名含 `"`/`\` 会产出非法 JSON，下游解析 `PendingScanActionEntity.payloadJson` 抛错（本地、经构造文件名触发）。同模块 `BookDraftFactory.escapeJson` 已有转义可复用。**修复**：转义后嵌入或用真正的 JSON 编码器。
- **M-11 [D4] [已修复] WebDAV openInputStream 响应泄漏+提前记成功**（`WebDavSourceProvider.kt:139-164`）：`logWebDavOpen(success=true)` 在 offset 回退 `skipFully` 之前记录；正常返回路径未 `Response.close()`，依赖调用方消费/关闭流——seek 频繁换流时未关闭则连接泄漏（连接池耗尽）。对照 `readRange`/`propfind` 已用 `.use{}`，仅此路径遗漏。**修复**：成功日志移到 skip 之后；包装流使其 `close()` 同时关 `Response`。
- **M-12 [D8] [已修复] PROPFIND 响应体无大小上限全量读入并 DOM 解析**（`WebDavSourceProvider.kt:252` `response.body.string()`）：恶意/异常服务器可返回超大 body 致内存暴涨（DoS）。XXE 已禁用（:315-317，良好）。**修复**：读取前限制 body 大小，或改流式 pull parser。
- **M-13 [D6] [已修复] 无 widget 时仍每次播放事件查库+全量刷新**（`PlaybackService.kt:392`，由 :132/140/159/166 触发）：缓冲/状态抖动时每秒多次 `getBookById` + `updateAppWidgetState` + `updateAll`，空 widget 早退发生在查库之后。**修复**：先判 `getGlanceIds().isEmpty()` 再查库；对更新去抖（~250ms）。
- **M-14 [D4/D6] [已修复] Glance 组合内同步 File.exists() + BitmapFactory.decodeFile**（`PlayerWidget.kt:77-107`）：在组合协程上同步磁盘 I/O 与解码，且位图跨 RemoteViews IPC（`TransactionTooLargeException` 风险）。**修复**：在写状态前预生成下采样缩略图，硬限解码字节。
- **M-15 [D2] [已修复] 扫描完成回调读取被 `WhileSubscribed(5000)` 门控的 `uiState.value`**（`LibraryViewModel.kt:171`）：无 UI 订阅时 `uiState.value` 为初始空值，导致"媒体库为空，未扫描到有效书籍"提示误报。**修复**：回调内直接 `libraryFacade.audiobooks.first()`。
- **M-16 [D7] [已修复] SearchOverlay 用非生命周期感知的 collectAsState**（`SearchOverlay.kt:37,93-95`）：`searchResults` 背后是 `flatMapLatest`+`combine` over DB flows，STOPPED 时仍在后台重算；与全局其余处不一致。**修复**：统一改用 `collectAsStateWithLifecycle()`。
- **M-17 [D7] 热路径误用 `remember`/`derivedStateOf`（以 `currentPosition` 为 key）**（`SubtitlesView.kt:51-60`、`PlaybackProgress.kt:41-44`）：以高频 `currentPosition` 作 `remember` key 使缓存恒失效，`derivedStateOf` 退化为纯开销，每个进度 tick（~2 次/秒）分配+重算。**修复**：直接计算或将 `currentPosition` 改为在 `derivedStateOf` 内读取的 State 而非 key。
- **M-18 [D1] [已修复] 未播放保护期 300ms 与 3000ms 门控不一致**（`DetailViewModel.kt:56` `delay(3_00L)` vs :230 `< 3000L`）：`onPlayPressed` 在 300ms 后即清空 `_playbackStartedAt`，而 `updatePlaybackProgress` 按 3 秒判定保护期，二者矛盾（注释一处写"0.3 秒"、一处写"3 秒"）。明显的数值笔误，致防闪烁特性提前失效。**用户影响为轻微 UI 闪烁**。**修复**：`delay(3_00L)` → `delay(3_000L)`，并抽取共享常量。

---

## 6. Low / Info 发现

- **L-01 [D10] 用户文案大面积硬编码（中英混杂）绕过 `strings.xml`（系统性）**：示例——`EditBookScreen.kt:286/298/351/364/377/390/403/495`、`DetailContent.kt:223`、`ChapterList.kt:325/344`、`LibraryViewModel.kt:176-242`、`SleepTimerManager.kt:113-445`（部分带 `//todo 正式上线要移除toast`）、`AudiobookActionDialogs.kt`、`APlayerApp.kt:372/384/391`、`SearchScreen.kt`、`ScanResultDialog.kt`、`AboutScreen.kt`。同时存在硬编码英文串（`"Chapters"`、`"Start Listening"` 等）。`localeFilters=["zh","en"]` 形同虚设，英文用户会看到中文。**修复**：统一抽取至 `strings.xml`（带占位符复数），移除带 TODO 的调试 Toast。
- **L-02 [Info/D12] 项目零自动化测试**：`app/src/test`、`app/src/androidTest` 无任何测试源；本审查无行为基线可依。建议为 `PositionMapper`/`ChapterTimeline`/各 `*MetadataRangeParser`/数据层事务/`PlaybackPlanBuilder` 等纯逻辑补单元测试（这些恰是 bug 高发区）。
- **L-03 [D3] [已修复] local.properties 被 git 跟踪**：含本机 SDK 路径（敏感度低但不应入库）。**修复**：`git rm --cached` 并加入 `.gitignore`。
- **L-04 [D2] [已修复] RunClaimLedger 共享可变 Map 非线程安全**（`RunClaimLedger.kt:9,55-62`）：当前依赖 scope/子批"严格顺序执行"才正确；一旦并行化（架构已具备 `mapWithBoundedConcurrency`）即丢更新/`ConcurrentModificationException`。**修复**：显式注明单线程不变量或加 `Mutex`/并发 Map。
- **L-05 [D5] [已修复] DI 容器混用注入与 ambient 单例、重复解析 DB**（`AppContainer.kt:252-256,262-265`）：`vfsFileInterface`/`playbackFileLookup` 直接 `AppDatabase.getInstance` 而非复用 `database` 惰性字段；`PlaybackManager`/`SearchHistoryStore`/`AutoRewindManager` 为 ambient 单例。无运行期危害，但削弱可测性。**修复**：统一复用 `database`，ambient 单例收入容器属性。
- **L-06 [D1] [已修复] 失效的路由守卫与无操作分支**：`MiniPlayerOverlay.kt:69` `!currentRoute.startsWith("search")` 而 NavHost 仅有 `"home"` 路由（search 已是 overlay），守卫恒不命中；`PlayerViewModel.kt:369` `if(!isFullPlayerVisible){ setFullPlayerVisible(false) }` 条件与动作同为 `false`，**永远是无操作**（死代码，无功能危害但具误导性）。**修复**：删除或修正意图。
- **L-07 [D7] [已修复] Theme.kt:94 (view.context as Activity).window 非安全强转**：非 Activity 宿主（预览/嵌入）下 ClassCastException。修复：沿 ContextWrapper 链查找 Activity，找不到则 no-op。
- **L-08 [D8] [已修复] LibrarySyncWorker 任何异常都 Result.failure()**（:26-29）：瞬时失败（DB 忙、容器未就绪）不重试。修复：对 IOException 类瞬时错误返回 Result.retry()。
- **L-09 [D11] `compileSdk=37`、`targetSdk=36` 使用非稳定/预览 SDK**（`build.gradle.kts:17,24`）：影响构建可复现性与第三方兼容性判断。**修复**：除非确需预览特性，固定到当前稳定 SDK。
- **L-10 [D5] [已修复] `DetailContent` 把已拆解的参数重新包回 `DetailUiState`**（`DetailContent.kt:140-150`）：与"无状态化"重构方向相悖，且下游布局拿整对象更难跳过重组。**修复**：要么端到端传 `DetailUiState`，要么端到端传平铺参数，勿两者并存。
- **L-11 [Info/D3] 发布构建已用 `-assumenosideeffects` 剥离 `Log.v/d/i`（正向）**（`proguard-rules.pro`）：因此 `VfsLogger`/`LibraryLogger`/`ImportTimingLogger`（均用 `Log.d`）记录的 WebDAV URL/文件路径/Uri 在 **release 中被移除**，隐私风险主要限于 **debug 构建**。注意 `Log.w/e` 不被剥离——确保错误日志不含凭据。建议仍将 logger 以 `BuildConfig.DEBUG` 显式门控并对 URL userinfo 脱敏。
- **L-12 [D2] [已修复] CUE 帧→毫秒未校验 `frames∈0..74`**（`CueManifestParser.kt:201`）：整型截断 + 无界 frames，章节边界最多偏 ~13ms/轨。**修复**：校验帧范围。
- **L-13 [D6] [已修复] `PillCompactMediaPlayer` 旋转 `Animatable` 无界累加**（`:94-108`）：长会话浮点精度退化（仅视觉）。**修复**：循环顶 `snapTo(value % 360f)`。

---

## 7. 各子系统评估（Subsystem Assessment）

| 单元                        | 评级             | 概述                                                                                                                                                                              |
| --------------------------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **U0 构建/配置**            | 🔴 需立即处理    | 含两个最高优先级问题（C-01 签名材料入库、H-04 `permission="TODO"`）。正向：ProGuard 已剥离调试日志（L-11）、PendingIntent 用 `FLAG_IMMUTABLE`。                                   |
| **U1 数据层**               | 🟠 结构性风险    | 分层清晰、查询正确、`BookImporter.applyImportRun` 有事务；但**全层零写事务**（H-06）+ 破坏性迁移（C-02）构成核心数据安全短板。                                                    |
| **U2 库导入/VFS**           | 🟡 基本稳健      | 取消协作（`yield`）、XXE 防护、SAF 流关闭良好；薄弱点在凭据安全（H-01/H-02）、`openInputStream` 泄漏（M-11）、PROPFIND 无界（M-12）。未发现导入路径的确定性崩溃或丢数据。         |
| **U3 媒体播放**             | 🟡 基本稳健      | MediaController 线程约束总体遵守、`onConnect` 白名单、明文播放有 `PlaybackFailureHandler` 把关；薄弱点在 loader 线程 `runBlocking`（H-05）与不受信任媒体解析健壮性（M-01~M-03）。 |
| **U4 UI 播放器域**          | 🟢 良好          | `collectAsStateWithLifecycle` 一致使用、list `key=` 正确、effect 清理到位；少量热路径重组开销（M-17）与一处笔误（M-18）、无操作死码（L-06）。                                     |
| **U5 UI 库/搜索/设置/导航** | 🟢 良好          | 导航图、节流、DataStore 收集均正确；薄弱点为组合内写状态（HomeScreenState）、`SearchOverlay` 收集方式（M-16）、扫描提示误报（M-15）、系统性硬编码文案（L-01）。                   |
| **U6 应用/小组件/日志/DI**  | 🟠 暴露面+启动期 | 导出接收器（H-03）、`container` lateinit（H-07）为主要风险；widget 更新效率（M-13/M-14）与 DI 一致性（L-05）次之。                                                                |

---

## 8. 做得好的地方（Positives）

为保证评价平衡，以下实践值得肯定：

- **MediaSession `onConnect` 包名白名单**（`PlaybackService.kt:298-307`）限制了控制器连接来源。
- **PendingIntent 使用 `FLAG_IMMUTABLE`**（`PlaybackService.kt:233`）。
- **PROPFIND XML 解析禁用了 DOCTYPE/外部实体**，规避 XXE（`WebDavSourceProvider.kt:315-317`）。
- **发布构建剥离 `Log.v/d/i`**，缓解日志隐私泄漏（`proguard-rules.pro`）。
- **`collectAsStateWithLifecycle` 在 UI 层基本一致使用**；列表 `key=` 使用正确；`AudioProgressBar` 以 `() -> Float` + `rememberUpdatedState` 规避高频重组。
- **`BookImporter.applyImportRun` 是事务化的**，导入路径取消处理（`yield`/重抛 `CancellationException`）健全。
- **协程异常处理器**在 `PlaybackManager` 全局兜底，避免未捕获异常崩溃进程。
- 整体**分层与命名清晰**，重构方向（无状态化/解耦）正确。

---

## 9. 修复路线图（Prioritized Remediation Roadmap）

### 阶段一：发布阻断（立即，安全/数据）

1. **C-01**：清除入库 keystore、轮换密钥、口令外置。
2. **C-02**：编写 Room 迁移链，移除/收窄破坏性迁移。
3. **H-04**：移除 `permission="TODO"`，依赖 `onConnect` 白名单。
4. **H-03**：拆分非导出接收器承载自定义控制动作。
5. **H-01 / H-02**：凭据改 EncryptedSharedPreferences、排除备份；明文默认关闭并显式 opt-in。
6. **L-03**：`local.properties` 移出版本控制。

### 阶段二：稳定性与数据完整性（近期）

7. **H-06**：为多写序列引入 `withTransaction`，进度写入串行化。
8. **H-07**：容器初始化做线程安全/进程守卫。
9. **H-05**：远程数据源打开改为可中断同步路径。
10. **M-05 / M-04 / M-07**：播放器 Listener 释放、事件流容量、Service 作用域生命周期。
11. **M-08 / M-09**：软删 claim 清理、破坏性重建后封面清理。

### 阶段三：健壮性与性能（中期）

12. **M-01 / M-02 / M-03**：不受信任媒体解析的边界与溢出加固（同时移除 `largeHeap` 依赖）。
13. **M-06 / M-11 / M-12 / M-13 / M-14**：磁盘 I/O 移出 Flow.map、WebDAV 流/响应关闭、PROPFIND 限长、widget 更新去抖与异步解码。
14. **M-16 / M-17 / M-15 / M-18**：收集方式、热路径重组、扫描提示、保护期笔误。

### 阶段四：质量与可维护性（持续）

15. **L-01**：文案国际化（抽取 strings.xml、移除调试 Toast）。
16. **L-02**：为纯逻辑模块补单元测试（优先 `PositionMapper`/解析器/数据层事务）。
17. **L-04~L-13**：线程安全注记、DI 一致性、死码清理、Worker 重试、SDK 固定等。

---

## 10. 附录：审查覆盖与局限（Coverage & Limitations）

- **覆盖**：`app/src/main/**` 全部 Kotlin 源码（7 单元 / 12 维度）、构建脚本、`AndroidManifest.xml`、`res/xml`（network security、backup、widget info）、ProGuard 规则、git 跟踪敏感文件。
- **方法**：核心高风险文件逐行精读 + 横切模式检索后逐处核实；**所有 Critical/High 发现已对当前分支源码二次打开核实并据实校准严重级别**（例如将两处被高估为 High 的 UI 发现修正为 Medium/Low；将日志隐私因 release 剥离而下调；将 WebDAV 凭据因纳入备份而上调为 High）。
- **局限**：
  1. 本次为**静态审查**，未执行编译与真机运行；标注"建议复验/需运行确认"的项需在编译/真机阶段验证（尤其 H-04 中 `"TODO"` 权限的确切运行时行为、H-05 的 loader 线程实测）。
  2. **无既有测试**可借力，部分行为正确性结论基于代码语义推断。
  3. 第三方库版本兼容性（`compileSdk=37` 预览态）以静态判断为主。
  4. 个别 Medium/Low 标注为"子系统审查结论"，未逐一二次精读，已在条目中如实说明。
