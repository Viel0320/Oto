# APlayer 代码仓库审查计划（Code Review Plan）

- **项目**：APlayer（`com.viel.aplayer`）—— Android 有声书播放器
- **审查分支**：`uirefactor`（基线提交 `2416bd5`）
- **计划制定日期**：2026-05-30
- **审查负责人**：Claude Code（自动化代码审查）
- **交付目录**：`docs/cc/`
- **配套交付物**：本计划（`code_review_plan.md`） + 审查报告（`code_review_report.md`）

---

## 1. 项目概况（Project Profile）

| 维度 | 现状 |
| --- | --- |
| 语言 / 平台 | Kotlin（JVM 21）/ Android，单 `app` 模块 |
| UI 框架 | Jetpack Compose（Material3，Compose BOM `2026.05.01`） |
| 构建 | AGP `9.2.1`，Kotlin `2.3.21`，KSP；`compileSdk=37`，`minSdk=33`，`targetSdk=36` |
| 媒体 | Media3 / ExoPlayer `1.10.1`（`MediaSessionService` + `MediaController`） |
| 持久化 | Room `2.8.4`（DB version 33）+ DataStore Preferences |
| 后台 | WorkManager（库同步）、前台播放 Service |
| 远程源 | WebDAV（OkHttp `5.3.2`）、本地 SAF（DocumentFile） |
| 其他 | Glance 桌面小组件、Coil、Palette、miuix-blur |
| 规模 | 约 **180** 个 Kotlin 文件，**约 32,740 行**主源码 |
| 测试 | **0 个**单元/插桩测试（`app/src/test`、`app/src/androidTest` 为空） |

### 1.1 分层架构（自下而上）

```
UI (Compose: home/search/settings/player/detail/edit/miniplayer/navigation) — 16,712 行 / 86 文件
        │  (ViewModel + *State + *Actions 无状态化重构进行中)
Facade  (LibraryFacade)
Gateway (BookQuery/Progress/Scan/LibraryRoot/Cover/SearchHistory)
Service (BookQueryService/CoverService/ProgressService/ScanService/...)
Media   (PlaybackManager / PlaybackService / 元数据&清单解析 / 字幕) — 6,986 行 / 36 文件
Library (导入编排 orchestrator / VFS / SAF & WebDAV Provider) — 4,767 行 / 32 文件
Data    (Room DAO / Entity / DataStore Store) — 2,929 行 / 37 文件
DI      (AppContainer 手写依赖容器)
```

### 1.2 初步侦察中已发现的高风险信号（待报告阶段核实定级）

- 签名口令与密钥别名硬编码于 `app/build.gradle.kts`；`app/release.jks` 已被 git 跟踪。
- `local.properties` 已被 git 跟踪。
- ~~`AppDatabase` 生产唯一初始化路径启用 `fallbackToDestructiveMigration(true)`（schema 不匹配即静默清库）。~~ 已按 2026-06-09 的 Room 41 基线策略移除 destructive fallback，并删除 41 之前 schema fixture。
- `PlaybackService` 在 manifest 中 `exported="true"` 且 `android:permission="TODO"`（占位字符串）。
- `PlayerWidgetReceiver` `exported="true"`、自定义媒体控制广播无权限保护。
- WebDAV 密码以 Base64（非加密）存入 `SharedPreferences`，且 `allowBackup="true"`。
- 大量用户可见文案以中文硬编码于代码（约 52 个文件含 CJK 字面量），与声明的 `zh/en` 双语本地化不一致。
- `largeHeap="true"` 用于绕过 Media3 大文件解析 OOM（疑似治标）。

> 以上信号仅用于指导审查重点，**最终结论以报告阶段逐条核实代码为准**。

---

## 2. 审查目标与范围（Objectives & Scope）

### 2.1 目标
1. 发现可能导致崩溃、数据丢失、安全/隐私风险的**正确性与安全缺陷**。
2. 评估在并发、生命周期、资源管理上的**健壮性**。
3. 审视进行中的"无状态化/解耦"重构在**架构一致性**上的落地质量。
4. 识别**性能、可维护性、国际化**层面的系统性问题。
5. 输出**按严重级别排序、带证据（`file:line`）与可执行修复建议**的报告与优先级路线图。

### 2.2 范围内（In Scope）
- `app/src/main/**` 全部 Kotlin 源码。
- 构建脚本（`build.gradle.kts`、`gradle/libs.versions.toml`）、`AndroidManifest.xml`、`res/xml`（network security、backup rules、widget info）、ProGuard 规则。
- git 跟踪文件中的敏感物料（keystore、properties）。

### 2.3 范围外（Out of Scope）
- 第三方库内部实现；`build/`、`.gradle/`、IDE 配置。
- 运行期动态测试 / 真机回归（无现成测试，且本次为静态审查）。
- `.kiro/` 历史审查产物——仅作交叉参考，不作为结论来源；所有结论独立基于当前代码核实。

---

## 3. 审查维度（Review Dimensions）

每条发现归入以下维度之一，并据 §5 评级。

| # | 维度 | 重点核查项 |
| --- | --- | --- |
| D1 | **正确性 / 逻辑** | 边界条件、空安全、整型/时间换算、索引越界、状态机错误、错误的早返回 |
| D2 | **并发 / 线程** | 协程作用域与取消、`runBlocking`、单例线程安全、`MediaController` 单线程约束、竞态、`StateFlow` 原子性、SharedFlow 丢事件 |
| D3 | **安全 / 隐私** | 凭据/密钥存储与入库、明文 HTTP、TLS 校验绕过、导出组件与权限、SAF/文件越权、备份规则、`networkSecurityConfig` |
| D4 | **资源 / 生命周期** | ExoPlayer/MediaController 释放、Cursor/InputStream/OkHttp 响应体关闭、协程作用域泄漏、Service 生命周期、单例 `release()` 后悬挂引用 |
| D5 | **架构 / 设计** | 分层依赖方向、解耦重构一致性、上帝类、DI 容器、SRP、抽象泄漏、循环依赖、`Application` 强转 |
| D6 | **性能 / 内存** | 主线程 I/O、`largeHeap`、解析期分配、重组开销、N+1 查询、未加索引查询、轮询频率 |
| D7 | **Compose 正确性** | 状态提升、稳定性/不必要重组、`remember`/`derivedStateOf` 误用、副作用（`LaunchedEffect` key）、列表 `key`、状态持有泄漏 |
| D8 | **错误处理 / 韧性** | 吞异常的空 `catch`、`printStackTrace`、`getOrDefault` 掩盖错误、无用户反馈、降级策略 |
| D9 | **数据完整性** | Room 迁移策略、事务边界、外键/索引、查询正确性、`@Transaction`、可空性、唯一约束 |
| D10 | **国际化 / 资源** | 硬编码中文文案、`strings.xml` 覆盖、复数/格式化、时间/数字本地化 |
| D11 | **构建 / 配置** | 版本与兼容性、`exported`、`permission`、ProGuard 对反射/Room/Media3 的保留、SDK 设定 |
| D12 | **可维护性 / 质量** | 死代码、重复、命名、注释噪声、文件体量、缺失测试、TODO 残留 |

---

## 4. 子系统分解与审查矩阵（Subsystem Breakdown）

将代码库切分为 7 个审查单元，按"主审维度"分配重点；每个单元均需覆盖全部 12 维度，矩阵标注高优先维度。

| 单元 | 路径范围 | 主审维度 | 关键文件 |
| --- | --- | --- | --- |
| **U0 构建与配置** | `build.gradle.kts`、`libs.versions.toml`、`AndroidManifest.xml`、`res/xml`、ProGuard、git 跟踪敏感文件 | D3, D11 | build script、manifest、network_security_config、backup rules |
| **U1 数据层** | `data/**`（DAO/Entity/db/service/gateway/store/usecase）+ `LibraryFacade` | D9, D2, D1 | `AppDatabase`、`*Dao`、`*Service`、`AppSettingsRepository`、DataStore Store |
| **U2 库导入 / VFS** | `library/**`（orchestrator/availability/sync/vfs + SAF/WebDAV provider）、`SourceInventoryScanner` | D2, D3, D4, D1 | `ImportPipeline`、`ScanSessionRunner`、`VirtualFileSystem`、`WebDavSourceProvider`、`WebDavCredentialStore`、`SafSourceProvider` |
| **U3 媒体播放** | `media/**`（PlaybackManager/Service/parser/manifest/subtitle/AutoRewind/Progress） | D2, D4, D6, D1 | `PlaybackManager`、`PlaybackService`、`VfsPlaybackDataSource`、`*MetadataRangeParser`、`Mp4MetadataFrameReader`、`ProgressSyncTracker` |
| **U4 UI — 播放器域** | `ui/player/**`、`ui/detail/**`、`ui/edit/**`、`ui/miniplayer/**` | D7, D5, D1 | `PlayerViewModel`、`PlayerScreen`、`DetailViewModel`、`EditBookScreen`、布局族 |
| **U5 UI — 库 / 搜索 / 设置 / 导航** | `ui/home/**`、`ui/search/**`、`ui/settings/**`、`ui/navigation/**`、`ui/common/**`、`ui/theme/**` | D7, D5, D10 | `LibraryViewModel`、`SearchViewModel`、`SettingsScreen`、`SleepTimerManager`、`APlayerApp`、`APlayerNavHost` |
| **U6 应用 / 小组件 / 日志 / DI** | `APlayerApplication`、`AppContainer`、`MainActivity`、`widget/**`、`logger/**` | D4, D5, D3 | `AppContainer`、`PlayerWidget*`、`MainActivity` |

---

## 5. 严重级别评级标准（Severity Rubric）

| 级别 | 定义 | 典型示例 |
| --- | --- | --- |
| **Critical（严重）** | 直接导致数据丢失、安全凭据泄露、确定性崩溃，或可被外部触发的安全问题 | 提交入库的签名密钥；schema 不匹配即清库；导出组件可被任意 App 触发危险操作 |
| **High（高）** | 高概率崩溃/资源泄漏/竞态，或显著隐私风险，需尽快修复 | `runBlocking` 阻塞、未释放的播放器、明文凭据存储、可达的空指针 |
| **Medium（中）** | 边界条件 bug、错误处理缺失、架构违例、明显性能问题，影响有限或触发条件较窄 | 吞异常的 `catch`、N+1 查询、重组性能、分层越界 |
| **Low（低）** | 可维护性、代码质量、轻微不一致、文案与本地化 | 死代码、命名、注释噪声、硬编码文案、TODO |
| **Info（提示）** | 非缺陷的改进建议或观察项 | 测试缺失、依赖升级建议、文档 |

每条发现记录字段：`ID | 严重级 | 维度 | 标题 | 位置(file:line) | 证据 | 影响 | 修复建议`。

---

## 6. 审查方法与流程（Methodology）

1. **静态精读 + 模式扫描结合**：核心高风险文件逐行精读；横切问题（吞异常、`runBlocking`、硬编码文案、`exported`、资源关闭）用结构化检索定位后逐处核实。
2. **分单元并行审查**：U0–U6 各单元独立审查，输出带 `file:line` 证据的原始发现。
3. **交叉验证**：Critical/High 级发现由主审二次打开源码核实，确认可达性与触发条件，剔除误报；不确定项明确标注"待验证/需运行确认"。
4. **去重与归并**：合并跨单元重复项，统一 ID 与定级。
5. **独立性约束**：`.kiro/` 既有产物仅供查漏，不直接采信；凡引用结论必基于当前分支代码重新核实。

### 审查检查清单（Checklist 摘要）
- [ ] 所有 `catch` 块：是否吞异常、是否有用户/日志反馈、是否误降级
- [ ] 所有协程 `launch`/作用域：取消传播、异常处理、线程约束
- [ ] 所有 `Closeable`（Cursor/InputStream/Response/Player）：是否 `use`/`finally` 关闭
- [ ] 所有 `exported` 组件：权限保护与输入校验
- [ ] 所有凭据/密钥：存储方式、入库情况、备份暴露
- [ ] 所有 Room 查询/迁移：事务、索引、迁移策略、可空性
- [ ] 所有用户可见文案：是否走 `strings.xml`
- [ ] 所有 Composable：状态提升、重组稳定性、副作用 key、列表 key

---

## 7. 执行计划（Execution Plan）

| 阶段 | 任务 | 产物 |
| --- | --- | --- |
| P0 | 架构侦察与高风险信号采集（已完成） | 本计划 §1.2 |
| P1 | 制定并输出审查计划 | `docs/cc/code_review_plan.md` |
| P2 | 分单元执行审查（U0–U6），收集原始发现 | 内部发现清单 |
| P3 | Critical/High 逐条核实、去重、定级 | 已核实发现集 |
| P4 | 撰写并输出审查报告（执行摘要 + 分级发现 + 子系统评估 + 修复路线图） | `docs/cc/code_review_report.md` |

---

## 8. 交付物（Deliverables）

1. **`docs/cc/code_review_plan.md`** —— 本文件。
2. **`docs/cc/code_review_report.md`** —— 审查报告，包含：
   - 执行摘要（总体评价 + 发现统计 + Top 风险）
   - 按严重级别分组的发现明细（含证据、影响、修复建议）
   - 7 个子系统的专项评估
   - 分阶段、按优先级排序的修复路线图
   - 附录：审查覆盖说明与局限。

---

## 9. 审查的局限性（Limitations）

- 本次为**静态代码审查**，未执行编译验证与真机运行；标注为"待运行确认"的项需在真机/编译期复验。
- 无既有测试可借力评估行为正确性，部分逻辑结论基于代码语义推断。
- 第三方库版本兼容性（如 `compileSdk=37` 的预览态）以静态判断为主。
