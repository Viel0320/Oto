<!-- Architecture Plan Document Purpose: Records the staged scene-module refactor plan, rollback points, verification commands, and unit-test scope so implementation can proceed in small recoverable slices. -->

# APlayer 场景 Module 架构改造方案

## 目标

本方案目标是把 UI 场景从宽泛的 `LibraryFacade` 中逐步迁出，形成更小、更清晰、更可测试的场景 module。改造原则是简洁直观、适当解耦、包边界清晰，不为了拆而拆。

<!-- Implementation Status Snapshot: Records the completed refactor scope and the remaining architectural distance after the staged work landed. -->

## 当前实施状态（2026-06-08）

已完成：

- `LibraryFacade` 已退役，UI 入口不再通过宽门面访问图书馆能力。
- `Search`、`Detail`、`Settings`、`Player`、`Edit`、`Home` 已收敛到场景级 dependency view 与 `application/library/*` module。
- `Home` 与 `Player` 已改用场景投影，UI 不再直接消费 `BookWithProgress`、`BookEntity`、`BookmarkEntity`、`ChapterWithBookFile` 等 Room 形状。
- 原 `domain/usecase` 已迁移到 `application/usecase`，避免把应用编排用例放进领域模型包。
- 播放运行时由 `MediaGraph` 持有；删除用例通过 `PlaybackStopper` 协调播放停止，不直接依赖 `PlaybackManager`。
- 已补架构回归测试：`ApplicationUseCasePackageArchitectureTest`、`LibraryFacadeRetirementArchitectureTest`、`HomeLibraryReadModelArchitectureTest`、`PlayerEditArchitectureTest` 等。

当前距离目标结构：约 90% 到 95%。目标包边界已经落地，剩余主要是更深层的纯度整理：例如推荐相关内部模型仍可继续从 `BookWithProgress` 过渡到更明确的 application/domain 投影，但它已经不再泄漏到 UI 场景接口。

已验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

优先顺序：

1. 先做 `Search`，作为低风险样板。
2. 再迁移 `Detail`、`Settings`、`Player`、`Edit`。
3. 最后收掉 `LibraryFacade` 过渡入口，并把播放生命周期从 `DataGraph` 移回媒体侧。

## 预想包结构

```text
com.viel.aplayer
├─ app
│  ├─ APlayerApplication.kt
│  ├─ AppContainer.kt
│  └─ graph
│     ├─ DataGraph.kt
│     ├─ MediaGraph.kt
│     ├─ LibraryGraph.kt
│     ├─ AbsGraph.kt
│     └─ UiEventGraph.kt
├─ application
│  ├─ library
│  │  ├─ search
│  │  │  ├─ SearchLibraryReadModel.kt
│  │  │  ├─ SearchLibraryCommands.kt
│  │  │  ├─ SearchQueryPlanner.kt
│  │  │  └─ DefaultSearchLibraryModule.kt
│  │  ├─ detail
│  │  │  ├─ DetailBookReadModel.kt
│  │  │  ├─ DetailBookCommands.kt
│  │  │  ├─ DetailSnapshot.kt
│  │  │  ├─ DetailSourceLocationFormatter.kt
│  │  │  └─ DefaultDetailBookModule.kt
│  │  ├─ home
│  │  │  ├─ HomeLibraryReadModel.kt
│  │  │  └─ HomeLibraryUseCases.kt
│  │  ├─ settings
│  │  │  ├─ SettingsRootReadModel.kt
│  │  │  ├─ SettingsRootCommands.kt
│  │  │  └─ DefaultSettingsRootModule.kt
│  │  ├─ player
│  │  │  ├─ PlayerLibraryReadModel.kt
│  │  │  ├─ PlayerBookmarkCommands.kt
│  │  │  └─ DefaultPlayerLibraryModule.kt
│  │  └─ edit
│  │     ├─ EditBookReadModel.kt
│  │     ├─ EditBookCommands.kt
│  │     └─ DefaultEditBookModule.kt
│  ├─ playback
│  │  ├─ BuildPlaybackPlanUseCase.kt
│  │  ├─ PlaybackStopper.kt
│  │  └─ DeletePlaybackGuard.kt
│  └─ deletion
│     ├─ DeleteBookUseCase.kt
│     └─ DeleteLibraryRootUseCase.kt
├─ domain
│  ├─ library
│  │  ├─ Audiobook.kt
│  │  ├─ LibraryRoot.kt
│  │  └─ RelatedSection.kt
│  ├─ playback
│  │  └─ PlaybackTimeline.kt
│  └─ feedback
│     └─ FeedbackMessage.kt
├─ data
│  ├─ db
│  ├─ dao
│  ├─ entity
│  ├─ gateway
│  ├─ service
│  └─ cache
├─ library
│  ├─ orchestrator
│  ├─ scan
│  ├─ vfs
│  ├─ availability
│  └─ readmodel
├─ media
│  ├─ service
│  ├─ parser
│  ├─ subtitle
│  └─ playback runtime classes
├─ abs
│  ├─ auth
│  ├─ net
│  ├─ mapping
│  ├─ playback
│  ├─ sync
│  └─ vfs
└─ ui
   ├─ home
   ├─ detail
   ├─ search
   ├─ settings
   ├─ player
   ├─ edit
   ├─ common
   ├─ navigation
   └─ motion
```

说明：

- `application/library/*` 放场景级 module，承接 UI 需要的读取和命令。
- `data/entity` 只表示 Room 持久化结构，不应继续扩散到新 UI 场景 interface。
- `data/gateway` 是 application module 和 data adapter 之间的 seam。
- `graph` 负责装配，不承载业务规则。
- `LibraryFacade` 只作为迁移过渡 module，不能继续成为新 UI 的默认入口。

## 阶段 0：基线与架构守护

目标：先锁住现状，避免迁移过程中旧入口继续扩张。

任务：

- 记录当前 UI 仍依赖 `LibraryFacade` 的调用点：`Search`、`Detail`、`Settings`、`Player`、`Edit`。
- 扩展现有架构测试，保证 `Home` 不回退到 `LibraryFacade`。
- 新增一条待迁移清单测试，明确哪些 UI 场景暂时允许依赖 `LibraryFacade`。
- 确认当前编译和单元测试基线通过。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 该阶段只新增测试和清单，不改业务行为。
- 如果测试误报，回退测试文件即可。

## 阶段 1：Search 场景 Module

目标：用最小风险场景建立迁移样板。

任务：

- 新增 `application/library/search/SearchLibraryReadModel.kt`。
- 新增 `application/library/search/SearchLibraryCommands.kt`。
- 新增 `application/library/search/SearchQueryPlanner.kt`。
- 新增 `application/library/search/DefaultSearchLibraryModule.kt`。
- 在 `LibraryGraph` 装配 Search module。
- 新增 `SearchScreenDependencies`，只暴露 Search 场景需要的 interface。
- `SearchViewModel` 改为依赖 `SearchLibraryReadModel` 和 `SearchLibraryCommands`。
- `SearchViewModel` 删除 `LibraryFacade` 和 `getLibraryPresentationDependencies` 依赖。

建议 interface：

```kotlin
interface SearchLibraryReadModel {
    val searchHistory: Flow<List<SearchHistoryEntry>>
    fun search(query: String): Flow<List<BookWithProgress>>
}

interface SearchLibraryCommands {
    suspend fun saveSearchHistory(query: String)
    suspend fun deleteSearchHistory(history: SearchHistoryEntry)
    suspend fun clearSearchHistory()
}
```

单元测试：

- `SearchQueryPlannerTest`
  - 空查询返回空列表。
  - 普通 token 调用 `searchAudiobooks`。
  - `year:2024` 调用年份筛选。
  - `author:`、`writer:`、`narrator:` 正确分派。
  - 多 token 查询按 `bookId` 求交集。
- `SearchLibraryModuleTest`
  - `searchHistory` 正确暴露。
  - `saveSearchHistory` 忽略空白输入。
  - `deleteSearchHistory` 正确委派。
  - `clearSearchHistory` 正确委派。
- `SearchSceneArchitectureTest`
  - `SearchViewModel` 不 import `LibraryFacade`。
  - `SearchViewModel` 不调用 `getLibraryPresentationDependencies`。
  - `SearchScreenDependencies` 不继承 `LibraryPresentationDependencies`。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 只影响搜索 overlay。
- 如果出现行为异常，可把 `SearchViewModel` 临时切回旧 `LibraryFacade` 路径。

## 阶段 2：Detail 场景 Module

目标：把详情页来源展示、可用性刷新、实时书籍快照从 ViewModel 移出。

任务：

- 新增 `application/library/detail/DetailBookReadModel.kt`。
- 新增 `application/library/detail/DetailBookCommands.kt`。
- 新增 `application/library/detail/DetailSnapshot.kt`。
- 新增 `application/library/detail/DetailSourceLocationFormatter.kt`。
- 新增 `application/library/detail/DefaultDetailBookModule.kt`。
- 在 `LibraryGraph` 装配 Detail module。
- 新增 `DetailScreenDependencies`。
- `DetailViewModel` 不再直接查询 root、files、availability。
- `DetailUiState` 分阶段从 `BookWithProgress` 迁到 `DetailSnapshot`。

单元测试：

- `DetailSourceLocationFormatterTest`
  - SAF root 展示注册名称和相对路径。
  - WebDAV root 不暴露原始 URL。
  - ABS root 不暴露播放 API 路径。
  - 缺失 root 时返回稳定降级文本。
- `DetailBookModuleTest`
  - 选中 book 后只更新当前 book。
  - 可用性刷新结果只写入匹配的 selection。
  - root cache 命中时不重复查全量 roots。
- `DetailSceneArchitectureTest`
  - `DetailViewModel` 不 import `LibraryFacade`。
  - detail 新增代码不 import `data.entity`，除过渡文件外。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 第一轮只改 ViewModel 到 module 的调用方式，不一次性改全部 UI layout 参数。
- 如详情页展示异常，可回退 `DetailViewModel` 和新增 detail module。

## 阶段 3：Settings Root 管理 Module

目标：把设置页 root 查询、注册、刷新、扫描触发收进同一个 settings-root module。

任务：

- 新增 `application/library/settings/SettingsRootReadModel.kt`。
- 新增 `application/library/settings/SettingsRootCommands.kt`。
- 新增 `application/library/settings/DefaultSettingsRootModule.kt`。
- 把以下调用从 `SettingsViewModel -> LibraryFacade` 迁出：
  - `refreshLibraryRootStatuses`
  - `refreshLibraryRootStatus`
  - `addLibraryRootAndScheduleSync`
  - `addWebDavLibraryRootAndScheduleSync`
  - `scheduleLibrarySync`
- `SettingsScreenDependencies` 不再继承 `LibraryPresentationDependencies`。

单元测试：

- `SettingsRootModuleTest`
  - 本地 root 注册后触发 `USER` sync。
  - WebDAV root 注册参数正确传递。
  - overlay 可见时刷新全部 root 状态。
  - 单 root 刷新返回 preflight 结果。
- `SettingsSceneArchitectureTest`
  - `SettingsViewModel` 不访问 `libraryFacade`。
  - `SettingsScreenDependencies` 不继承 `LibraryPresentationDependencies`。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 设置页 root 操作集中，回退范围主要是 `SettingsViewModel` 和 settings-root module。

## 阶段 4：Player 和 Edit 剩余 UI 调用点

目标：迁出最后几个 UI 对 `LibraryFacade` 的依赖。

任务：

- 新增 `application/library/player/PlayerLibraryReadModel.kt`。
- 新增 `application/library/player/PlayerBookmarkCommands.kt`。
- 新增 `application/library/player/DefaultPlayerLibraryModule.kt`。
- `BookmarkManager` 改为依赖 `PlayerBookmarkCommands`。
- `MediaPlaybackDelegate` 的封面轮询改为依赖窄 interface。
- 新增 `application/library/edit/EditBookReadModel.kt`。
- 新增 `application/library/edit/EditBookCommands.kt`。
- 新增 `application/library/edit/DefaultEditBookModule.kt`。
- `EditBookViewModel` 不再依赖 `LibraryFacade`。

单元测试：

- `PlayerLibraryModuleTest`
  - 书籍元数据、章节、书签 flow 正确组合。
  - 封面轮询只查询必要 book 信息。
- `PlayerBookmarkCommandsTest`
  - add/update/delete bookmark 正确委派。
- `EditBookModuleTest`
  - 读取 book。
  - 更新文本元数据。
  - 保存自定义封面。
- `PlayerEditArchitectureTest`
  - `PlayerViewModel`、`BookmarkManager`、`MediaPlaybackDelegate`、`EditBookViewModel` 不 import `LibraryFacade`。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- Player 和 Edit 分开提交，避免同时影响播放页与编辑页。
- Player 先迁 bookmark 和 metadata，再迁封面轮询。

## 阶段 5：LibraryFacade 退场

目标：让 `LibraryFacade` 从默认 UI seam 变成可删除的过渡 module。

任务：

- 删除或收缩 `LibraryPresentationDependencies`。
- 如果所有 UI 调用点已迁移，删除 `LibraryFacade`。
- 如果仍有非 UI 过渡调用点，给 `LibraryFacade` 添加明确过渡注释和架构测试白名单。
- 新增架构测试，禁止 `ui/**` import `LibraryFacade`。
- 更新 `AppContainer` 和 `LibraryGraph`，删除不再需要的 facade 暴露。

单元测试：

- `LibraryFacadeRetirementArchitectureTest`
  - `ui/**` 不 import `LibraryFacade`。
  - `dependencies/PresentationDependencies.kt` 不暴露 `LibraryPresentationDependencies`。
  - `AppContainer` 不再向 UI dependency view 暴露 `libraryFacade`。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 只有在 UI 调用点清零后才删除 facade。
- 若某场景迁移风险过高，可先保留 facade 但禁止新增依赖。

## 阶段 6：播放生命周期归位

目标：让 `DataGraph` 回到数据持久化职责，播放生命周期由 `MediaGraph` 管理。

任务：

- 新增 `application/playback/PlaybackStopper.kt`。
- `MediaGraph` 持有 `PlaybackManager` 和 `AutoRewindManager`。
- `DataGraph` 只保留 database、settings、datastore。
- `DeleteBookUseCase` 改为依赖 `PlaybackStopper`。
- `DeleteLibraryRootUseCase` 改为依赖 `PlaybackStopper`。
- `LibraryGraph` 从 `media` 获取删除所需的播放停止 seam。

建议 interface：

```kotlin
interface PlaybackStopper {
    val currentPlayingBookId: String?
    suspend fun stopPlayback()
}
```

单元测试：

- `PlaybackStopperTest`
  - 当前播放书籍匹配时触发 stop。
  - 当前播放书籍不匹配时不触发 stop。
- `DeleteBookUseCaseTest`
  - 删除当前播放书籍会先 stop 再 delete。
  - 删除非当前书籍不会 stop。
- `DeleteLibraryRootUseCaseTest`
  - 当前播放书籍属于删除 root 时 stop。
  - 查询当前书籍失败时仍能删除 root 数据。
- `PlaybackLifetimeArchitectureTest`
  - `DataGraph` 不 import `PlaybackManager`。
  - 删除用例不 import `PlaybackManager`。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 先新增 `PlaybackStopper` adapter，保持底层仍由原 `PlaybackManager` 实现。
- 确认删除流程稳定后，再移动 `PlaybackManager` 所属 graph。

## 阶段 7：UI 实体隔离

目标：让新场景 interface 不继续暴露 Room entity。

任务：

- 为搜索、详情、播放器、编辑页定义 UI-safe snapshot。
- `data/entity` 只留在 data adapter 和过渡 UI 文件中。
- 分页迁移 UI layout 参数，不做一次性全局替换。
- 新增架构测试，禁止新 UI 文件 import `data.entity`。

单元测试：

- `UiSnapshotMappingTest`
  - `BookWithProgress` 到场景 snapshot 的映射正确。
  - 空封面、缺失作者、缺失进度等边界值稳定。
- `UiEntityImportArchitectureTest`
  - 新增 UI 文件不 import `data.entity`。
  - 过渡名单必须显式维护。

验证：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

回归点：

- 每个 UI 页面独立迁移。
- snapshot 映射保持纯函数，方便快速回退。

## 总体验证矩阵

每个阶段至少执行：

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
```

影响发布、安全或资源规则时追加：

```powershell
.\gradlew.bat lintDebug
.\gradlew.bat lintRelease
.\gradlew.bat assembleRelease
```

手工回归重点：

- 搜索：普通搜索、命令搜索、历史新增、历史删除、清空历史。
- 详情：打开详情、播放按钮状态、来源路径展示、文件不可用提示。
- 设置：新增本地 root、新增 WebDAV root、编辑 root、手动扫描。
- 播放器：播放、暂停、章节跳转、书签新增/编辑/删除、封面刷新。
- 编辑：修改元数据、修改封面、保存后详情页刷新。

## 实施顺序建议

第一张任务单只做阶段 1：

```text
任务：迁移 SearchViewModel 到 SearchLibraryModule
范围：Search module + dependency view + LibraryGraph wiring + 单元测试 + 架构测试
验证：compileDebugKotlin + testDebugUnitTest
回归：SearchViewModel 可临时切回 LibraryFacade
```

完成后再进入 Detail。这样能先验证包结构、依赖注入方式、测试写法和回归路径，避免一次性迁移多个 UI 场景。
