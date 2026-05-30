<!-- 注释：本文档规划 UI 层与对应 ViewModel 的 Route/Screen/State/Action/Event 渐进式迁移；迁移范围包含页面 ViewModel 的状态、事件和公开方法边界整理，但不变更数据层、播放内核、VFS、导入扫描和能力层实现，避免把页面结构整理扩大成跨上下文架构重写。 -->
# UI + ViewModel Route / Screen / State / Action / Event 阶段性迁移计划

<!-- 注释：本节明确迁移目标和硬边界，确保后续每个阶段都只迁移一个页面域及其直属 ViewModel，不在同一阶段混入播放器、数据层或全局 overlay 的高风险调整。 -->
## 1. 迁移目标与边界

本次迁移目标是把 Compose 页面和对应 ViewModel 统一整理为以下职责：

- `XxxRoute`：页面接线层，负责持有或接收 ViewModel、收集 `StateFlow`、消费一次性 `Event`、处理导航、Activity/Toast 等 UI 副作用；overlay 页面由 Route 持有可见性动画外壳，避免旧 Overlay 为了控制显隐继续直连 ViewModel。
- `XxxScreen`：无 ViewModel 的渲染层，只接收 `state` 和 `onAction`，不直接访问 repository、ViewModel、NavController 或 Android UI 副作用。
- `XxxUiState`：ViewModel 对 UI 暴露的稳定页面状态，承载可渲染数据和业务推导结果。
- `XxxPrivateState`：页面私有 Compose 状态，承载 ActivityResult launcher、临时弹窗输入、滚动状态、焦点状态、拖拽进度、临时文件路径等不应进入 ViewModel 的 UI 运行态；简单页面可由 Route 内 `remember` 托管，复杂页面必须显式列出清单。
- `XxxViewModel`：页面业务状态生产者，负责组合应用层数据、维护 shared state、暴露一次性 event，并提供 Route 可调用的页面级业务方法。
- `XxxAction`：页面动作，主要来自 Screen 上报的用户意图；ActivityResult、系统返回、宿主请求等非 Screen 来源动作只能由 Route 产生或消费，并且必须在对应阶段明确标注来源。
- `XxxEvent`：一次性 UI 事件，例如 Toast、Snackbar、一次性确认弹窗、跳转请求。

本次迁移不调整以下内容：

- 不改 Room、DataStore、VFS、WebDAV、SAF、导入扫描和播放内核。
- 不改现有 `LibraryFacade`、`DeleteLibraryRootUseCase` 等应用层边界。
- 不把 ViewModel 重构扩展为 data/application/domain/infrastructure 分层重写；ViewModel 只整理页面状态、事件、公开方法和 Compose 依赖边界。
- 不重排 `APlayerApp` 的 overlay Z 轴顺序，直到各页面 Route 稳定。
- 不在播放器阶段之前迁移播放器内部高频状态。
- 不为了对称性强行给每个页面创建 `PrivateState` 文件；但每个阶段必须列出 private state 的归属，不能让 Screen 隐式持有 ActivityResult、导航、Toast 或业务副作用。

<!-- 注释：本节给出每阶段都必须执行的公共验证动作，避免只完成编译但遗漏 UI 交互回归。 -->
## 2. 公共验证要求

每个阶段完成后必须执行：

1. 编译验证：

```powershell
.\gradlew.bat compileDebugKotlin
```

2. 页面入口验证：

- 启动应用。
- 打开本阶段涉及页面。
- 返回上一级或关闭 overlay。
- 确认没有崩溃、黑屏、卡死和明显布局错位。

3. 变更范围验证：

```powershell
git diff -- app/src/main/java/com/viel/aplayer/ui docs/ui_route_screen_action_migration_plan.md
```

<!-- 注释：本仓库默认优先使用 rg 做结构搜索；如果本机没有安装 rg，则使用 PowerShell Select-String 兜底，避免检查步骤依赖单一外部工具。 -->
命令兜底规则：

- 文档中的 `rg -n "<pattern>" <path> -S` 如果不可用，改用：

```powershell
Get-ChildItem -Path <path> -Recurse -Filter *.kt | Select-String -Pattern '<pattern>'
```

- 如果 `<path>` 是单个文件，改用：

```powershell
Select-String -Path <path> -Pattern '<pattern>'
```

- 如果 `rg` 命令包含多个路径或 `--glob "*.kt"`，改用：

```powershell
$paths = @(<path1>, <path2>)
Get-ChildItem -Path $paths -Recurse -Filter *.kt | Select-String -Pattern '<pattern>'
```

- 如果只需检查某个方法名是否仍有调用，也可用单文件或目录版 `Select-String`，判定标准仍与原 `rg` 命令一致：只剩定义行才允许删除。

检查点：

- 当前阶段以外的页面没有被顺手重构。
- `Screen` 层没有新增 ViewModel 参数。
- `Route` 层没有直接承载大量渲染布局。
- 本阶段 ViewModel 没有新增 Compose、NavController、Activity、Toast、Modifier、LazyListState、LayerBackdrop 等 UI 运行时依赖。
- 本阶段 ViewModel 的公开方法和 `XxxAction` 一一对应或有明确保留理由，不再保留无人调用的旧 callback 桥接方法。
- 本阶段已列出 private state 归属：留在 Screen 的必须是纯 UI 临时状态，放到 Route 的必须是跨子组件协调状态，放到 ViewModel 的必须是配置变更后不能丢的 shared state。
- 本阶段任务必须能直接执行，不能只写“分析、检查、评估”；需要分析的地方必须给出命令、判定条件和后续动作。
- 新增注释只描述代码意图和迁移边界，不包含外部沟通内容。

<!-- 注释：阶段 0 只建立迁移规范和检查清单，不改页面行为，作为后续每页迁移的共同基线。 -->
## 3. 阶段 0：迁移基线

目标：建立命名、职责和验证基线，不改变任何页面行为。

改动文件：

- 新增或更新 UI 迁移说明文档。
- 不修改 Kotlin 页面代码。

具体任务：

1. 固化命名规则：
   - 页面入口统一命名为 `XxxRoute`。
   - 渲染层统一命名为 `XxxScreen`。
   - 页面动作统一命名为 `XxxAction`。
   - 页面状态统一命名为 `XxxUiState`。
   - 一次性事件统一命名为 `XxxEvent`，全局复用事件可继续使用 `UiEvent`。
2. 固化职责边界：
   - 只有 Route 可以收集 ViewModel 的 Flow；旧 Overlay 不再作为公共收集入口保留，阶段内必须把旧 Overlay 改为不接收 ViewModel、不执行 collect 的私有视觉外壳，并把外部调用点切到 Route。
   - 只有 Route 可以调用导航、Toast、Activity 启动、overlay 显隐桥接。
   - Screen 只能把用户操作转换为 `onAction(...)`；ActivityResult、系统回调和宿主回调不得由 Screen 直接启动或消费。
   - ViewModel 只处理业务状态、shared state、一次性 event 和页面级业务动作，不持有 Compose 控件状态。
   - ViewModel 不能新增 `androidx.compose.*` UI 类型；当前代码已有的 `SearchViewModel.query: TextFieldValue` 在搜索阶段固定保留，并在注释中写明保留光标、选区和输入组合状态的原因。
   - ViewModel 不能直接调用 `Toast.makeText(...)`、启动 Activity、持有 NavController 或持有 `LayerBackdrop`。
3. 固化 ViewModel 迁移执行清单：
   - 在对应阶段新增一张 `ViewModel 输出映射表` 注释块或文档小节，逐项写明每个 `StateFlow` / `SharedFlow` 的去向：进入 `XxxUiState`、保留独立 shared state、迁为 `XxxEvent`，或因高频隔离继续局部收集。
   - 在对应阶段新增一张 `ViewModel 方法映射表` 注释块或文档小节，逐项写明每个公开方法由哪个 `XxxAction`、Route、宿主或系统入口调用。
   - 对每个 ViewModel 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" <ViewModelFile>`，用结果核对映射表没有漏项。
   - 对迁移后不再使用的公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`，确认只有定义没有调用时删除。
   - 对必须保留但不对应页面 action 的方法，在方法注释中写明调用方，例如宿主生命周期入口、Widget 入口、播放器同步入口。
   - 保留应用层 use case / facade 依赖，不在本迁移中下沉或搬运业务实现。
4. 固化阶段自检模板：
   - 在每个阶段末尾补充 `阶段自检` 小节。
   - 自检必须回答四项：是否符合迁移目标与边界、是否符合当前代码现状、是否自洽、是否无歧义可执行。
   - 任一项回答为否时，先修正文档或当前阶段实现，不进入下一阶段。
5. 固化播放器迁移规则：
   - 播放器放在最后。
   - 播放进度、字幕滚动、mini player progress 等高频状态不得提前上提到全局大状态。
   - `PlayerViewModel` 放在播放器阶段统一执行状态输出映射表和方法映射表，不在首页、详情、搜索等阶段顺手调整其内部高频状态。
   - 第一轮播放器迁移只消除外层 `PlayerScreen` 的 ViewModel 直连，不一次性改完所有子组件。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 启动应用，确认首页、详情 overlay、搜索 overlay、播放器 overlay、设置页入口均可打开。

阶段自检：

- 迁移目标与边界：符合。阶段 0 只建立执行规则和验收模板，不要求改 Kotlin 页面代码。
- 当前代码现状：符合。规则已把当前存在的 overlay、ActivityResult、ViewModel 直连和播放器高频状态纳入后续阶段处理。
- 自洽性：符合。播放器明确最后处理，且每页 ViewModel 只在对应页面阶段处理。
- 任务清晰度：符合。后续阶段必须提供命令、映射表、删除条件和回归验证。

<!-- 注释：后续迁移顺序按当前代码的实际改动量从轻到重排列；已接近 Route/Screen 分离的页面优先，跨多个 ViewModel 或大量设置项的页面后置，播放器仍保持最后。 -->
阶段执行顺序：详情页 Detail -> 编辑页 EditBook -> 搜索页 Search -> 首页 Home -> 设置页 Settings -> 全局宿主 APlayerApp -> 播放器 Player -> 最终收口。

<!-- 注释：详情页已经较接近 Route/Screen 分离，第一阶段只整理动作入口，避免改动详情页复杂布局和背景采样。 -->
## 4. 阶段 1：详情页 Detail

目标：把详情页整理为 `DetailRoute + DetailScreen + DetailAction`。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/detail/DetailOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/detail/DetailRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/detail/DetailAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/detail/DetailScreen.kt`
- `app/src/main/java/com/viel/aplayer/ui/detail/DetailUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/detail/DetailViewModel.kt`

具体任务：

1. 新增 `DetailRoute`：
   - 收集 `DetailViewModel.uiState`。
   - 持有原 `DetailOverlay` 的 `AnimatedVisibility`、`layerBackdrop(detailBackdrop)` 和 overlay 外壳，因为 `uiState.isVisible` 已由 Route 收集。
   - 处理详情页动作分发。
   - 保留 `backdrop`、`detailBackdrop`、`fullPageBackdrop` 透传。
   - 不接管 `DetailScreen` 内部的拖拽、预测返回、更多菜单、信息 Dialog、coverBackdrop 等 private state；这些状态只服务详情页视觉和交互。
2. 执行 `DetailViewModel` 迁移任务：
   - 新增 `DetailViewModel 输出映射表` 注释，明确 `uiState: StateFlow<DetailUiState>` 是详情页 shared state 输出。
   - 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" app/src/main/java/com/viel/aplayer/ui/detail/DetailViewModel.kt`，把结果逐项对齐到输出映射表和方法映射表。
   - 新增 `DetailViewModel 方法映射表` 注释，写明 `setVisible(false)` 对应 `DetailAction.BackClicked`，`onPlayPressed` 由 `DetailAction.PlayClicked` 处理流程内部先调用，`selectBook` 是首页/搜索打开详情的外部入口，`dismissIfShowing` 是删除书籍后的宿主清理入口，`updatePlaybackProgress` 是全局播放器进度同步入口。
   - 保持真正播放动作在 `DetailRoute` / `APlayerApp` 调用 `playerViewModel.loadBook(...)`，不把播放器加载逻辑下沉进 `DetailViewModel`。
   - 确认 `DetailViewModel` 不新增 backdrop、navigation、Toast、Activity、Compose scroll state 或播放器 ViewModel 依赖。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
3. `DetailOverlay`：
   - 删除旧公共 `DetailOverlay` 入口，外部调用点统一改为 `DetailRoute`。
   - 将原动画外壳迁入 private `DetailOverlayShell`，只接收 `DetailUiState`、`DetailAction` 和视觉参数。
   - 不再收集 `DetailViewModel.uiState`。
   - 不再直接调用 `DetailScreen`。
4. 新增 `DetailAction`：
   - `BackClicked`
   - `PlayClicked`
   - `SearchClicked(query: String)`
   - `EditClicked(bookId: String)`
5. `DetailRoute` 处理 action：
   - 返回动作调用 `detailViewModel.setVisible(false)`。
   - `PlayClicked` 先调用 `detailViewModel.onPlayPressed()` 保留当前播放保护期逻辑，再从 `DetailUiState.book` 取 bookId 后调用外部 `onPlayBook`。
   - 更多菜单显隐保留在 `DetailScreen` 的 `showMenu` private state 中，不创建 `DetailAction.MoreClicked`。
   - 搜索动作先关闭详情 overlay，再经 `canStartNavigation()` 判断后调用外部 `onNavigateToSearch`。
   - 编辑动作调用外部 `onEditClick`。
6. `DetailScreen`：
   - 参数收敛为 `uiState`、`onAction`、视觉配置参数。
   - 不直接接收 ViewModel。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 从首页点击书籍打开详情 overlay 正常。
- 详情返回关闭 overlay 正常。
- 详情点击播放正常。
- 详情点击作者或播音搜索正常。
- 详情点击编辑正常打开编辑 overlay。
- 详情里的进度、简介、章节摘要和封面背景显示正常。
- MiuixBlur 下详情 overlay 背景采样正常。
- 检查 `DetailViewModel` 的外部入口列表与 Route/App 调用点一致，没有无人调用的旧桥接方法。
- 检查详情播放保护期逻辑在迁移后仍生效。

阶段自检：

- 迁移目标与边界：符合。详情阶段只迁移 overlay 接线、action 分发和 `DetailViewModel` 方法边界，不改播放加载和进度同步来源。
- 当前代码现状：符合。当前 `DetailOverlay` 已收集 `DetailViewModel.uiState` 并把 state 传给 `DetailScreen`，本阶段主要消除分散 callback。
- 自洽性：符合。`updatePlaybackProgress` 保留为宿主同步入口，未强行包装成用户 action。
- 任务清晰度：符合。哪些 private state 留在 `DetailScreen`、哪些动作上提 Route 已明确。

<!-- 注释：编辑页涉及表单输入和异步保存，第二阶段单独迁移可以精确回归输入状态、保存完成信号和详情刷新；保存完成只作为 Route 内部编排结果处理，不让 ViewModel 反向持有宿主回调。 -->
## 5. 阶段 2：编辑页 EditBook

目标：把编辑 overlay 的 ViewModel 收集和保存流程迁移到 `EditBookRoute`。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookViewModel.kt`
- `app/src/main/java/com/viel/aplayer/ui/edit/EditBookScreen.kt`

具体任务：

1. 新增 `EditBookUiState`：
   - `isVisible`
   - `book`
   - 本阶段不加入标题、作者、播音、年份、简介和封面临时路径字段；这些字段继续由 `EditBookScreen` 内的 `remember` 和临时文件状态管理。
   - 本阶段不新增保存中状态和表单错误提示，沿用当前保存后关闭 overlay、依赖 Room Flow 刷新的行为。
2. 执行 `EditBookViewModel` 迁移任务：
   - 将当前定义在 `EditBookOverlay.kt` 内的 `EditBookViewModel` 移动到独立文件 `EditBookViewModel.kt`。
   - 新增 `EditBookViewModel 输出映射表` 注释，明确 `isVisible` 和 `bookState` 进入 `EditBookUiState`。
   - 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" app/src/main/java/com/viel/aplayer/ui/edit/EditBookViewModel.kt`，把结果逐项对齐到输出映射表和方法映射表。
   - 新增 `EditBookViewModel 方法映射表` 注释，写明 `startEdit` 是详情页编辑入口，`setVisible(false)` 对应取消/返回关闭，`loadBook` 当前只由 `startEdit` 调用并改为 private，`saveBook` 对应 `EditBookAction.SaveClicked`。
   - 标题、作者、播音、年份、简介和封面临时路径固定保留在 `EditBookScreen`，不迁入 `EditBookViewModel`，不创建输入类 Action。
   - 保存方法改为 `suspend fun saveBook(...)` 或返回 `Result<Unit>` 的挂起方法，只调用 repository/facade 更新书籍元数据，不接收 `onComplete` 回调，不直接操作详情页、首页、Activity、Toast 或 overlay 显隐。
   - 改为挂起方法时必须删除 `saveBook(...)` 内部的 `viewModelScope.launch`，让调用方 `EditBookRoute` 拥有协程生命周期；ViewModel 不再在保存方法内部另起协程，否则 Route 无法可靠等待保存完成或捕获失败。
   - `saveBook(...)` 读取 `_bookState.value` 为空时应返回失败结果或直接 `return` 并由 Route 保持当前页面，不调用 `onSaveSuccess`，避免空书籍状态下误关闭 overlay。
   - 保存成功由 `EditBookRoute` 在协程中等待 `saveBook(...)` 完成后本地处理：先调用 `editBookViewModel.setVisible(false)`，再调用宿主传入的 `onSaveSuccess`；本阶段不新增 `EditBookEvent`，因为保存成功只需要 Route 内部完成关闭和宿主通知。
   - 保存失败如当前代码没有用户反馈则保持失败传播或日志语义，不在本阶段新增 Toast/Snackbar；若后续要展示失败，再单独新增 `EditBookEvent.SaveFailed`，不能把 Toast 写回 ViewModel。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
3. 新增 `EditBookRoute`：
   - 收集 `EditBookViewModel.isVisible`。
   - 收集 `EditBookViewModel.bookState`。
   - 持有原 `EditBookOverlay` 的 `AnimatedVisibility`、背景采样透传和 overlay 外壳，因为 `isVisible` 已由 Route 收集。
   - 不直接处理图片选择和临时文件清理；这些仍留在编辑渲染层，因为当前 `EditBookScreen` 依赖 ActivityResult launcher、Context cache 文件和临时封面路径。
4. `EditBookOverlay`：
   - 删除旧公共 `EditBookOverlay` 入口，外部调用点统一改为 `EditBookRoute`。
   - 将原动画外壳迁入 private `EditBookOverlayShell`，只接收 `EditBookUiState`、`EditBookAction`、`glassEffectMode` 和 `backdrop`。
   - 不再收集 `EditBookViewModel.isVisible` 和 `bookState`。
   - 不再直接调用 `EditBookScreen`。
5. 新增 `EditBookAction`：
   - `BackClicked`
   - `CancelClicked`
   - `SaveClicked(title: String, author: String, narrator: String, year: String, description: String, newCoverPath: String?)`
   - 不创建 `TitleChanged`、`AuthorChanged`、`NarratorChanged`、`YearChanged`、`DescriptionChanged`、`CoverChanged`，因为这些输入状态固定保留在 `EditBookScreen`。
6. `EditBookRoute` 处理 action：
   - 输入动作不进入 Route，`SaveClicked(...)` 一次性携带完整表单值。
   - `SaveClicked(...)` 在 Route 的协程中调用 `editBookViewModel.saveBook(...)`；保存完成后关闭 overlay 并调用 `onSaveSuccess`，不把宿主回调传入 ViewModel。
   - 取消动作关闭 overlay 且不保存。
7. `EditBookScreen`：
   - 不接收 `EditBookViewModel`。
   - 保留 `rememberLauncherForActivityResult(GetContent)`、`LocalContext`、`tempCoverPath`、预测返回状态、滚动状态等 private state。
   - 图片裁剪和临时文件删除仍在渲染层执行；Route 只接收最终 `newCoverPath`，不直接操作文件。
   - 配置变更后的未保存表单内容丢弃属于本阶段明确边界；回归只验证重新打开编辑页能恢复数据库中的书籍数据。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 从详情页打开编辑 overlay 正常。
- 标题、作者、播音、简介输入正常。
- 保存后 overlay 关闭。
- 保存后详情页展示刷新。
- 保存后首页列表展示刷新。
- 取消编辑不保存。
- 键盘弹出时保存和取消按钮不被遮挡。
- 检查 `EditBookViewModel` 已从 overlay 文件中独立出来。
- 检查旋转或配置变更后重新打开编辑页能恢复数据库中的书籍数据；未保存输入丢弃不作为本阶段失败。

阶段自检：

- 迁移目标与边界：符合。编辑阶段只迁移 overlay 接线、ViewModel 文件边界和保存 action，不改封面裁剪实现、VFS 外部输入读取和元数据持久化实现。
- 当前代码现状：符合。当前 `EditBookViewModel` 内嵌在 `EditBookOverlay.kt`，`EditBookScreen` 已无 ViewModel 但持有图片选择和临时文件 private state，本阶段明确保留归属。
- 自洽性：符合。临时封面文件由创建它的渲染层清理，Route 只等待保存结果并编排关闭，不把宿主回调下沉进 ViewModel。
- 任务清晰度：符合。表单状态固定留在 `EditBookScreen`，没有“一律迁入 ViewModel”的不确定分支。

<!-- 注释：搜索页当前 SearchOverlay 和 SearchScreen 承担状态收集，SearchContent 才是无 ViewModel 的渲染层；第三阶段拆出 SearchRoute，并把搜索结果播放链路固定为 Route 级编排，避免 SearchViewModel 依赖播放器或导航。 -->
## 6. 阶段 3：搜索页 Search

目标：拆清 `SearchOverlay`、`SearchRoute`、`SearchScreen/SearchContent`，让搜索渲染层不再接收 `SearchViewModel`。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/search/SearchOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/search/SearchRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/search/SearchAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/search/SearchUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/search/SearchScreen.kt`
- `app/src/main/java/com/viel/aplayer/ui/search/SearchViewModel.kt`
- `app/src/main/java/com/viel/aplayer/ui/navigation/APlayerApp.kt`

具体任务：

1. 新增 `SearchUiState`：
   - `isVisible`
   - `query: TextFieldValue`
   - `searchResults`
   - `searchHistory`
   - 不包含 `commandSuggestions`；命令建议继续由 `commandSuggestionsFor(query)` 纯函数即时计算并作为渲染参数传入，避免把纯 UI 派生结果扩进 ViewModel 输出状态。
2. 执行 `SearchViewModel` 迁移任务：
   - 新增 `SearchViewModel 输出映射表` 注释，明确 `isVisible`、`query`、`searchResults`、`searchHistory` 进入 `SearchUiState`。
   - 为 `query: TextFieldValue` 添加保留原因注释：用于保留光标、选区和输入组合状态；本阶段不把 query 改为 String。
   - 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" app/src/main/java/com/viel/aplayer/ui/search/SearchViewModel.kt`，把结果逐项对齐到输出映射表和方法映射表。
   - 新增 `SearchViewModel 方法映射表` 注释，写明 `setVisible` 对应 overlay 宿主显隐入口，`onQueryChange` 对应 `SearchAction.QueryChanged`，`search(query: String)` 对应 `SearchAction.SearchSubmitted`，`saveSearchHistory` 对应结果点击前的 Route 记录动作，`clearQuery` 对应 `SearchAction.QueryCleared`，`deleteHistory` 对应 `SearchAction.HistoryDeleteRequested`，`clearHistory` 对应 `SearchAction.HistoryClearRequested`。
   - 确认点击结果进入详情、点击结果播放、打开播放器只在 `SearchRoute` 调用外部 lambda，不新增到 `SearchViewModel`。
   - 确认 `SearchViewModel` 不新增 `LayerBackdrop`、focus requester、keyboard controller、scroll state、Toast、Activity 或播放控制依赖。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
3. 新增 `SearchRoute`：
   - 收集 `SearchViewModel.isVisible`。
   - 收集 `SearchViewModel.query`。
   - 收集 `SearchViewModel.searchResults`。
   - 收集 `SearchViewModel.searchHistory`。
   - 根据 `state.query` 调用 `commandSuggestionsFor(query)` 生成 command suggestions，并作为 `SearchScreen` 或 `SearchContent` 参数传入。
   - 持有原 `SearchOverlay` 中的 `AnimatedVisibility`、`Surface`、blur 背景与 overlay 外壳，因为 `isVisible` 已由 Route 收集。
   - 保留 `SearchContent` 内部的 `FocusRequester`、`LazyListState`、`focusManager`、autoFocus `LaunchedEffect`，因为这些是纯 UI private state。
4. `SearchOverlay`：
   - 删除旧公共 `SearchOverlay` 入口，外部调用点统一改为 `SearchRoute`。
   - 将原动画外壳迁入 private `SearchOverlayShell`，只接收 `SearchUiState`、`SearchAction`、`backdrop` 和 `glassEffectMode`。
   - 不再收集 `SearchViewModel.isVisible`。
   - 不再直接把 `SearchViewModel` 传给 `SearchScreen`。
   - 当前有状态 `SearchScreen` 函数实际定义在 `SearchOverlay.kt` 内，而不是 `SearchScreen.kt`；迁移时应先把该有状态适配层改名/移动为 `SearchRoute`，不要在 `SearchScreen.kt` 里寻找它。
   - 当前 `SearchScreen.kt` 内的 `SearchContent` 才是主要渲染层；迁移后可在该文件新增真正的无 ViewModel `SearchScreen(state, onAction, ...)` 包装 `SearchContent`，也可保留 `SearchContent` 作为内部渲染组件。
5. 新增 `SearchAction`：
   - `BackClicked`
   - `QueryChanged(value: TextFieldValue)`
   - `SearchSubmitted(query: String)`
   - `QueryCleared`
   - `HistoryDeleteRequested(entry: SearchHistoryEntry)`，与当前 `SearchViewModel.deleteHistory(history: SearchHistoryEntry)` 签名保持一致。
   - `HistoryClearRequested`
   - `ResultDetailRequested(bookId: String)`
   - `ResultPlaybackRequested(bookId: String)`
   - `PlayerRequested`
6. `SearchRoute` 处理 action：
   - 输入、搜索、清空、历史删除调用 `SearchViewModel`。
   - 点击详情前保存搜索历史，关闭 overlay，再调用外部 `onNavigateToDetail`。
   - 点击播放前关闭 overlay，先调用外部 `onLoadBook(bookId)` 加载书籍，再调用外部 `onNavigateToPlayer()` 展开播放器，保持当前搜索结果播放入口的完整链路。
   - `PlayerRequested` 仅用于搜索页内独立的打开播放器动作；搜索结果播放必须使用 `ResultPlaybackRequested(bookId)` 一次性表达加载并展开，避免 Screen 连续派发两个动作导致顺序不稳定。
7. `SearchScreen`：
   - 参数改为 `state: SearchUiState`、`commandSuggestions: List<SearchCommand>` 和 `onAction: (SearchAction) -> Unit`。
   - 不再接收 `SearchViewModel`。
   - 保留 `SearchContent` 作为真正无状态渲染组件，并在 `SearchScreen` 注释中写明 `SearchScreen` 是 action 适配层，`SearchContent` 是具体渲染层。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 从首页打开搜索 overlay 正常。
- 从详情页带关键词打开搜索 overlay 正常。
- 输入关键词后结果实时更新。
- 提交搜索后历史记录保存。
- 删除单条历史正常。
- 清空历史正常。
- 点击搜索结果进入详情正常。
- 点击搜索结果播放正常。
- 搜索 overlay 显示和关闭动画正常。
- MiuixBlur 和 Material 模式下背景均正常。
- 检查 `SearchViewModel` 没有新增导航、播放、Activity、Toast 和 Compose 控件状态依赖。
- 检查搜索输入状态迁移后光标、选区和自动聚焦行为没有退化。

阶段自检：

- 迁移目标与边界：符合。搜索只迁移 overlay 接线和 `SearchViewModel` 方法/状态边界，不把详情导航或播放逻辑下沉到搜索 ViewModel。
- 当前代码现状：符合。当前接收 ViewModel 的 `SearchScreen` 函数实际位于 `SearchOverlay.kt`，`SearchScreen.kt` 内的 `SearchContent` 已无 ViewModel；本阶段以这个物理结构为基线迁移。
- 自洽性：符合。`SearchSubmitted` 使用当前代码实际的 String 查询，`query` 状态仍保留 `TextFieldValue`。
- 任务清晰度：符合。焦点、滚动、autoFocus 的 private state 留在渲染层，业务状态由 Route 收集，搜索结果播放链路由 Route 串行编排。

<!-- 注释：首页已有 Stateful 容器和 Stateless 内容层雏形，但涉及多个 ViewModel 协调，放在详情、编辑和搜索之后迁移。 -->
## 7. 阶段 4：首页 Home

目标：把首页正式迁移为 `HomeRoute + HomeScreen + HomeAction`。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/home/HomeScreenState.kt`
- `app/src/main/java/com/viel/aplayer/ui/home/HomeScreenContent.kt`
- `app/src/main/java/com/viel/aplayer/ui/home/LibraryUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/home/LibraryViewModel.kt`
- `app/src/main/java/com/viel/aplayer/ui/home/HomeRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/home/HomeAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/navigation/APlayerNavHost.kt`

具体任务：

1. 将 `HomeScreenState.kt` 的职责迁移为 `HomeRoute`：
   - 保留 `LibraryViewModel`、`PlayerViewModel`、`DetailViewModel`、`SearchViewModel` 的接线。
   - 保留 `collectAsStateWithLifecycle()`。
   - 保留 `recentListState`、`prevFirstBookId`、`shouldScrollToStart` 这类 Compose private state。
   - 保留 `LaunchedEffect(shouldScrollToStart)` 的滚动修正逻辑。
2. 将 `HomeScreenContent.kt` 的职责迁移为 `HomeScreen`：
   - 删除页面命名中的 `Content`。
   - 不接收任何 ViewModel。
   - 不直接调用导航、Activity、Toast。
   - 将 `rememberLauncherForActivityResult(OpenDocumentTree)` 上提到 `HomeRoute`，因为目录选择会触发系统级副作用；`HomeScreen` 只上报 `HomeAction.AddLibraryRootClicked`。
   - 保留 `activeBookForMenu`、`actionDialogBackdrop`、`gridState`、`scope`、布局宽度判断等纯 UI private state 在 `HomeScreen`，因为这些状态不需要 ViewModel 知道。
3. 执行 `LibraryViewModel` 迁移任务：
   - 新增 `LibraryViewModel 输出映射表` 注释，明确 `uiState` 继续作为 `LibraryUiState` 输出，`uiEvents` 继续作为全局 `UiEvent` 输出，`scanResultDialogState` 继续作为扫描结果 Dialog shared state 输出。
   - 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" app/src/main/java/com/viel/aplayer/ui/home/LibraryViewModel.kt`，把结果逐项对齐到输出映射表和方法映射表。
   - 新增 `LibraryViewModel 方法映射表` 注释，写明 `setFilter` 对应 `HomeAction.FilterSelected`，`onLibraryRootSelected` 对应 `HomeAction.LibraryRootSelected`，`deleteBook` 对应 `HomeAction.BookDeleteRequested`，`updateBookReadStatus` 对应 `HomeAction.ReadStatusChanged`，`forceRegenerateCoverAndMetadata` 对应 `HomeAction.MetadataRegenerateRequested`，`dismissScanResultDialog` 对应扫描结果 Dialog dismiss 回调。
   - `deleteBook(bookId)` 必须移除内部 `PlaybackManager.getInstance(...).stopPlayback()` 直调；删除书籍前的停播只由 `HomeRoute` 调用 `playerViewModel.closePlayback(bookId)` 负责，避免 Route 与 ViewModel 双重停播、双重会话清理和状态竞争。
   - `deleteBook(bookId)` 保留书籍记录删除、源文件存在性检查和 `uiEvents` 结果反馈，不持有或调用播放内核。
   - 对 `clearSearchHistory`、`triggerRescan` 执行调用点搜索：`rg -n "clearSearchHistory|triggerRescan" app/src/main/java/com/viel/aplayer -S`。搜索结果只剩定义行时删除方法；出现实际调用时把调用方写入方法映射表并停止删除。
   - 对 `deleteLibraryRoot(...)` 执行调用点搜索：`rg -n "deleteLibraryRoot" app/src/main/java/com/viel/aplayer -S`。当前设置页已有 `SettingsViewModel.deleteLibraryRoot(...)`，`LibraryViewModel.deleteLibraryRoot(...)` 只剩定义行时删除；出现实际调用时把调用方迁到 `SettingsViewModel` 或对应 Route，再删除首页 ViewModel 中的设置页职责。
   - 如果 `LibraryViewModel.deleteLibraryRoot(...)` 因兼容调用暂时无法删除，必须把其中的 `Toast.makeText(...)` 改为 `_uiEvents.tryEmit(UiEvent.ShowToast(...))`，与 `SettingsViewModel` 的一次性反馈方式保持一致；不得在任何 ViewModel 中直接渲染 Toast。
   - 确认 `_selectedFilter`、`isFirstLoad`、`lastCompletedSessionId` 均保持 private，不向 Screen 暴露。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
   - 保持删除书籍前的停播协调在 `HomeRoute`，不把 `playerViewModel.closePlayback(...)` 下沉进 `LibraryViewModel`。
4. 新增 `HomeAction`：
   - `FilterSelected(filter: HomeFilter)`
   - `BookDetailRequested(bookId: String)`
   - `SearchRequested`
   - `BookPlaybackRequested(bookId: String)`
   - `PlayerRequested`
   - `AddLibraryRootClicked`
   - `LibraryRootSelected(uri: Uri)`：仅由 `HomeRoute` 内的目录选择 launcher 回调触发，不允许 `HomeScreen` 直接持有或启动 ActivityResult。
   - `SettingsRequested`
   - `ReadStatusChanged(bookId: String, status: String)`
   - `MetadataRegenerateRequested(bookId: String)`
   - `BookDeleteRequested(bookId: String)`
5. `HomeRoute` 负责处理 `HomeAction`：
   - 筛选动作调用 `libraryViewModel.setFilter(...)`。
   - 详情动作查找 `libraryUiState.audiobooks` 并调用 `detailViewModel.selectBook(...)`。
   - 搜索动作调用 `searchViewModel.setVisible(true)`。
   - 播放动作先调用 `playerViewModel.loadBook(bookId)`，再调用 `playerViewModel.setFullPlayerVisible(true)`，保持当前首页书籍播放入口“加载并展开播放器”的完整行为。
   - `PlayerRequested` 只处理单纯展开当前播放器：调用 `playerViewModel.setFullPlayerVisible(true)`，不重复加载书籍。
   - `AddLibraryRootClicked` 启动 Route 内的 `OpenDocumentTree` launcher；launcher 回调产生 `LibraryRootSelected(uri)` 后调用 `libraryViewModel.onLibraryRootSelected(uri)`。
   - `SettingsRequested` 在通过 `canStartNavigation()` 后创建 `SettingsActivity.createIntent(context)` 并由 Route 调用 `context.startActivity(intent)`；`HomeScreen` 不持有 `Context` 或启动 Activity。
   - 删除动作按现有顺序调用 `playerViewModel.closePlayback(bookId)` 再调用 `libraryViewModel.deleteBook(bookId)`；该顺序成立的前提是 `LibraryViewModel.deleteBook(...)` 已移除内部 PlaybackManager 停播直调。
6. `APlayerNavHost`：
   - `composable("home")` 调用 `HomeRoute`。
   - 不再直接调用 `HomeScreen`。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 首页三类筛选正常：未开始、进行中、已完成。
- 点击书籍进入详情 overlay 正常。
- 点击书籍播放入口能加载书籍并展开播放器。
- 点击搜索按钮能打开搜索 overlay。
- 点击设置按钮能打开设置 Activity。
- 添加媒体库目录入口仍能唤起目录选择。
- 长按书籍菜单中标记阅读状态、重建封面与元数据、删除书籍均正常。
- 最近书籍横向列表在新增或刷新后仍保持原有滚动修正行为。
- 检查 `LibraryViewModel` 没有新增 Compose UI 类型依赖。
- 检查 `LibraryViewModel.deleteBook(...)` 不再直接调用 `PlaybackManager`，删除当前播放书籍时只停播一次且没有重复 Toast 或状态抖动。
- 检查 `LibraryViewModel.deleteLibraryRoot(...)` 已删除，或其反馈已改为 `UiEvent.ShowToast`，不存在 `Toast.makeText(...)`。
- 检查 `HomeAction` 覆盖首页用户动作，且没有保留仅供旧 `HomeScreenContent` 使用的重复 callback。

阶段自检：

- 迁移目标与边界：符合。首页只迁移 UI 接线和 `LibraryViewModel` 边界，不改播放器内核、扫描实现和能力层。
- 当前代码现状：符合。当前 `HomeScreenState.kt` 已是 stateful 容器，`HomeScreenContent.kt` 已是无 ViewModel 渲染层，但目录选择 launcher 仍在内容层，本阶段明确上提。
- 自洽性：符合。删除书籍前停播只由 Route 协调，`LibraryViewModel` 不再直接持有 PlaybackManager 停播职责。
- 任务清晰度：符合。每个 ViewModel 方法都有保留、迁移或删除动作，系统目录选择和设置 Activity 启动均明确留在 Route。

<!-- 注释：设置页当前把 Route 职责写在 Activity 中，第五阶段将其抽出，降低 Activity 职责但不改变独立 Activity 形态。 -->
## 8. 阶段 5：设置页 Settings

目标：把 `SettingsActivity` 中的状态接线抽成 `SettingsRoute`，让 Activity 只负责生命周期入口。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/settings/SettingsActivity.kt`
- `app/src/main/java/com/viel/aplayer/ui/settings/SettingsRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/settings/SettingsAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/viel/aplayer/ui/settings/SettingsScreen.kt`
<!-- 注释：设置页使用的是持久化应用设置 AppSettings；PlayerSettingsState 属于播放器运行态，不纳入设置页迁移，避免把播放器 UI 状态误并入设置页边界。 -->

具体任务：

1. 新增 `SettingsRoute`：
   - 在 Route 内获取或接收 `SettingsViewModel`。
   - 收集 `settingsViewModel.settingsState`。
   - 收集 `settingsViewModel.libraryRoots`。
   - 维护 `showAboutLibraries` private state。
   - 保留 `BackHandler(enabled = showAboutLibraries)`。
   - 保留设置页与开源许可页之间的 `AnimatedContent`。
   - 将 `rememberLauncherForActivityResult(OpenDocumentTree)` 上提到 `SettingsRoute`，因为目录授权是系统副作用；`SettingsScreen` 只上报 `SettingsAction.AddLocalLibraryRootClicked`。
   - 将 `rootToDelete` 删除确认状态保留在 `SettingsScreen`，因为它只控制当前 AlertDialog。
   - 将 WebDAV 弹窗显隐和表单字段保留在 `SettingsScreen`，因为这些字段当前只服务一次弹窗提交；本阶段不处理 WebDAV 表单的配置变更持久化。
2. `SettingsActivity`：
   - 保留 `enableEdgeToEdge()`。
   - 保留 `APlayerTheme` 和顶层 `Surface`。
   - 调用 `SettingsRoute(onBack = { finish() })`。
   - 不再直接收集 `settingsState` 和 `libraryRoots`。
3. 执行 `SettingsViewModel` 迁移任务：
   - 新增 `SettingsViewModel 输出映射表` 注释，明确 `settingsState` 和 `libraryRoots` 继续作为设置页 shared state 输出。
   - 新增 `uiEvents: SharedFlow<UiEvent>` 或等价一次性事件流，复用现有 `UiEvent.ShowToast`，将 `deleteLibraryRoot(...)` 当前的 `Toast.makeText(...)` 改为事件发射，并在 `SettingsRoute` 中用 `LaunchedEffect` 消费；本阶段不新增 `SettingsEvent`，避免为单一 Toast 创建页面专属事件类。
   - 执行 `rg -n "fun |val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow" app/src/main/java/com/viel/aplayer/ui/settings/SettingsViewModel.kt`，把结果逐项对齐到输出映射表和方法映射表。
   - 新增 `SettingsViewModel 方法映射表` 注释，逐项把 `refreshLibraryRootStatuses/onLibraryRootSelected/onWebDavRootSubmitted/triggerRescan/deleteLibraryRoot/toggle*/update*` 映射到 Route 入口或对应 `SettingsAction`。
   - `refreshLibraryRootStatuses()` 由 `SettingsRoute` 进入页面时调用，用于保持当前 SAF 授权状态检查，不暴露给 `SettingsScreen`。
   - 把所有直接渲染 UI 的反馈从 ViewModel 中移出；当前 `deleteLibraryRoot(...)` 内存在 `Toast.makeText(...)`，迁移时改为发射 `UiEvent.ShowToast`，并执行 `rg -n "Toast\\.makeText" app/src/main/java/com/viel/aplayer/ui/settings/SettingsViewModel.kt -S` 确认设置页 ViewModel 不再直接渲染 Toast。
   - `SleepTimerManager` 中的运行时睡眠反馈不属于设置页 Route 迁移范围；不得为了通过设置页检查而顺手改播放器睡眠计时行为。
   - 设置页继续通过 `SettingsViewModel` 管理媒体库来源，不重新注入或创建 `LibraryViewModel`。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
4. 新增 `SettingsAction`：
   - `BackClicked`
   - `AddLocalLibraryRootClicked`
   - `LibraryRootSelected(uri: Uri)`：仅由 `SettingsRoute` 内的本地目录 launcher 回调触发，不允许 `SettingsScreen` 直接持有 ActivityResult launcher。
   - `WebDavRootSubmitted(url, username, password, displayName, basePath)`
   - `RescanClicked`
   - `LibraryRootDeleteRequested(root)`
   - `ChapterProgressModeChanged(enabled: Boolean)`
   - `CleartextTrafficAllowedChanged(enabled: Boolean)`
   - `SkipSilenceEnabledChanged(enabled: Boolean)`
   - `SleepFadeOutEnabledChanged(enabled: Boolean)`
   - `ShakeToResetEnabledChanged(enabled: Boolean)`
   - `SleepModeChanged(mode)`
   - `GlassEffectModeChanged(mode)`
   - `AutoRewindSecondsChanged(seconds: Int)`
   - `NotificationAvoidanceEnabledChanged(enabled: Boolean)`
   - `AboutLibrariesClicked`
   - `AboutLibrariesBackClicked`
5. `SettingsScreen`：
   - 将分散 callback 收敛为 `onAction(SettingsAction)`。
   - 保留已有 UI 布局和输入控件。
   - 不直接接收 `SettingsViewModel`。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 从首页进入设置页正常。
- 设置页返回按钮关闭 Activity。
- 系统返回键在开源许可页只返回设置首页，不关闭 Activity。
- 添加本地媒体库目录正常。
- 添加 WebDAV 来源正常。
- 删除媒体库 root 正常。
- 重新扫描正常触发。
- 章节进度、明文流量、跳过静音、睡眠淡出、摇动重置、通知避让等开关可变更并持久化。
- 睡眠模式、玻璃效果模式、自动回退秒数可变更并持久化。
- 检查 `SettingsViewModel` 没有直接依赖 Compose UI 类型、Activity、NavController。
- 检查 `SettingsViewModel` 的 Toast 或一次性反馈由 Route 消费，不在 ViewModel 里直接渲染；`SleepTimerManager` 的播放器运行时反馈不作为本阶段失败项。

阶段自检：

- 迁移目标与边界：符合。设置页只迁移 Activity 接线、`SettingsViewModel` 事件边界和 Screen action，不改 library root gateway、scan scheduler、播放器运行态或 use case 实现。
- 当前代码现状：符合。当前 `SettingsActivity` 直接 collect 状态，`SettingsScreen` 已无 ViewModel 但持有 launcher 和表单 private state，本阶段明确拆分归属。
- 自洽性：符合。媒体库来源管理继续留在 `SettingsViewModel`，不回退到 `LibraryViewModel`。
- 任务清晰度：符合。`deleteLibraryRoot` 的直接 Toast 有明确改法，所有设置项都有 action 映射。

<!-- 注释：在主要页面完成 Route 化后再清理全局宿主，能降低破坏 overlay 层级、事件消费和模糊采样源的风险。 -->
## 9. 阶段 6：全局宿主 APlayerApp 清理

目标：只整理全局宿主的页面接线，不迁移播放器内部。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/navigation/APlayerApp.kt`
- `app/src/main/java/com/viel/aplayer/ui/navigation/APlayerNavHost.kt`
- 各页面 Route 调用点

具体任务：

1. `APlayerNavHost`：
   - 只负责主导航 route 注册。
   - `home` destination 调用 `HomeRoute`。
2. `APlayerApp`：
   - 保留 ViewModel 创建位置。
   - 保留 `playerViewModel.initialize(context)`。
   - 保留 widget 打开播放器 overlay 的 `openPlayerOverlayRequest` 处理。
   - 保留 `playerViewModel.onRouteChanged()`。
   - 保留 `detailViewModel.updatePlaybackProgress(...)` 同步逻辑。
   - 只迁移已完成页面的 Route 调用方式，不在本阶段重构 `PlayerViewModel` 内部。
   - 保留 `PlayerOverlay(playerViewModel = ...)` 原样，不在本阶段引入 `PlayerRoute`。
   - 保留 `MiniPlayerOverlay(playerViewModel = ...)` 原样，不在本阶段迁移 mini player。
   - 检查 `APlayerApp` 中跨 ViewModel 协调逻辑是否仍是页面级编排；发现不属于页面级编排的业务规则时，停止本阶段，把该规则移回对应页面 ViewModel 阶段处理。
3. 全局事件：
   - 保留 `libraryViewModel.uiEvents` 消费。
   - 保留 `playerViewModel.uiEvents` 消费。
   - 不在本阶段引入新的全局事件中心。
4. overlay 顺序：
   - 保留底层 `APlayerNavHost`。
   - 保留普通页面 overlay 顺序：详情 Route、`MiniPlayerOverlay`、编辑 Route、`PlayerOverlay`、搜索 Route。
   - 保留扫描结果 Dialog 和分轨不可用 Dialog；它们属于确认/结果反馈层，允许高于普通页面 overlay，不纳入“搜索最高”的普通 overlay 判断。
5. blur 采样：
   - 保留 `appBackdrop`。
   - 保留 `detailBackdrop`。
   - 保留 `delayedBackdropState` 延迟切换策略。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 首页、详情、搜索、编辑、设置均可打开。
- overlay 层级正确：搜索在普通页面 overlay 中最高，播放器覆盖编辑和详情，编辑覆盖详情，mini player 不盖住全屏播放器；扫描结果 Dialog 和分轨不可用 Dialog 仍可在需要时覆盖普通 overlay。
- MiuixBlur 模式下切换详情、mini player、播放器、搜索没有黑底、穿帮和明显闪烁。
- Material 模式下背景和动画正常。
- Library 和 Player 的 Toast 仍正常显示。
- 分轨不可用确认弹窗仍能显示并执行跳过。

阶段自检：

- 迁移目标与边界：符合。宿主阶段只整理已完成页面的 Route 接线，播放器相关 overlay 和 `PlayerViewModel` 仍保持原状。
- 当前代码现状：符合。当前 `APlayerApp` 负责 overlay 兄弟层级、确认 Dialog、全局 Toast、blur backdrop 和跨 ViewModel 协调，本阶段保留这些高风险职责。
- 自洽性：符合。不会在播放器阶段之前引入 `PlayerRoute`，避免阶段顺序冲突。
- 任务清晰度：符合。保留项和禁止项均已列出。

<!-- 注释：播放器是最高风险页面，放在最后迁移，并且拆成外层迁移和子组件迁移两轮，避免高频播放状态导致大范围重组。 -->
## 10. 阶段 7：播放器 Player

目标：播放器最后迁移，先消除外层 ViewModel 直连，再逐步处理子组件。

改动文件：

- `app/src/main/java/com/viel/aplayer/ui/player/PlayerRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/PlayerAction.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/PlayerViewModel.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/PlayerScreen.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/PlayerUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/PlayerOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/layouts/PlayerPortrait.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/layouts/PlayerLandscapePhone.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/layouts/PlayerTablet.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/PlaybackControls.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/PlaybackProgress.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/ChapterList.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/ChapterDisplay.kt`
- `app/src/main/java/com/viel/aplayer/ui/player/components/SubtitlesView.kt`
- `app/src/main/java/com/viel/aplayer/ui/miniplayer/MiniPlayerOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/miniplayer/MiniPlayerRoute.kt`
- `app/src/main/java/com/viel/aplayer/ui/bookmarks/BookmarkList.kt`

具体任务：

1. 新增 `PlayerRoute` 并改造 `PlayerOverlay`：
   - 收集 `PlayerViewModel.settingsState` 中的 `isFullPlayerVisible`，并将原 `PlayerOverlay` 的 `AnimatedVisibility`、`playerBackdrop`、`layerBackdrop` 和可见性动画外壳迁入 `PlayerRoute`。
   - 将宿主中的 `PlayerOverlay(playerViewModel = ...)` 调用改为 `PlayerRoute(playerViewModel = ...)`。
   - 收集低频全屏播放器入口所需状态：`metadataState`、`settingsState`、`playbackControlState`、`uiState`；其中 `settingsState` 只用于可见性、内容 tab、弹窗和低频设置，不承载播放进度。
   - 在 `PlayerRoute` 中调用现有 `playerViewModel.rememberActions(...)` 创建 `PlayerActions` 兼容层。
   - 保留 `fullPageBackdrop` 透传。
2. 执行 `PlayerViewModel` 迁移任务：
   - 执行 `rg -n "val .*StateFlow|val .*SharedFlow|MutableStateFlow|MutableSharedFlow|stateIn|combine" app/src/main/java/com/viel/aplayer/ui/player/PlayerViewModel.kt`，生成 `PlayerViewModel 状态输出清单`。
   - 在 `PlayerViewModel.kt` 中新增 `PlayerViewModel 状态输出映射表` 注释，逐项标注播放状态、元数据、设置状态、播放控制状态、进度状态、章节状态、书签对话框状态、分轨不可用状态的迁移去向。
   - `PlayerScreen` 只接收一个低频页面状态对象和必要动作集合；低频页面状态可复用 `PlayerUiState`，或新增 `PlayerScreenState` 包装 `metadata/settings/controls/related`，但不得同时传入多个互相重叠的数据源导致状态来源不唯一。
   - 将播放进度、mini player progress、字幕滚动所需进度标注为高频隔离状态，不并入 `PlayerUiState` 或 `PlayerScreenState`。
   - 执行 `rg -n "^\\s*fun |^\\s*suspend fun " app/src/main/java/com/viel/aplayer/ui/player/PlayerViewModel.kt`，生成 `PlayerViewModel 公开方法清单`。
   - 在 `PlayerViewModel.kt` 中新增 `PlayerViewModel 方法映射表` 注释，逐项把公开方法映射到 `PlayerAction`、`PlaybackControlActions`、`BookmarkActions`、`ContentActions`、宿主生命周期入口或系统回调入口。
   - 为 `initialize(context)` 添加保留原因注释，明确它只能由宿主生命周期入口调用，不由 `PlayerScreen` 调用。
   - 保留播放服务、媒体会话、VFS 播放数据源相关调用，不在本阶段改变播放内核边界。
   - 将纯一次性反馈继续通过 `uiEvents` 暴露；需要用户确认的分轨不可用流程继续转为 `trackUnavailableDialogState` 这类 shared dialog state。
   - 对迁移后不再被调用的旧公开方法执行 `rg -n "<methodName>" app/src/main/java/com/viel/aplayer -S`；只有定义没有调用时删除方法。
   - 确认 `PlayerViewModel` 不新增 Compose UI 类型、backdrop、navigation、Toast 渲染依赖。
   - 确认 `APlayerApp` 的 overlay Z 轴职责不下沉到 `PlayerViewModel`。
3. 第一轮迁移 `PlayerScreen`：
   - `PlayerScreen` 不再直接接收 `PlayerViewModel`。
   - `PlayerScreen` 接收低频页面状态、`actions`、`navigationActions` 和视觉参数，不再自行 collect ViewModel Flow。
   - 第一轮结束时 `PlayerScreen` 不再收集 ViewModel Flow；临时保留的子组件直连必须集中在原有 `*Stateful` 局部组件中，或命名为 `PlayerProgressStateHolder`、`PlayerChapterStateHolder` 这类按职责拆分的小型私有 StateHolder。
   - 禁止新增一个持有所有播放器 Flow 的 `PlayerStatefulBridge` 或类似大桥接类；桥接只能按高频职责拆小，并在第二轮逐个删除。
   - 保留 `PlayerScreen` 内部的手势 private state：`currentMode`、`isPlayerBackActive`、`playerBackProgress`、`offsetY`、`sheetState`、`coverBackdrop`。
   - 保留 preview mock 数据，但迁移到 preview 参数构造，不再依赖 ViewModel 分支。
4. 新增 `PlayerAction`：
   - 第一轮只定义外层动作，不急于替换所有 `PlayerActions`。
   - `TabSelected(index: Int)`
   - `MinimizeRequested`
   - `CloseRequested`
   - `BookmarkPanelRequested`
   - `SubtitlePanelRequested`
   - `RelatedPanelRequested`
   - `DeleteCurrentBookRequested`
5. 第二轮迁移播放器子组件：
   - `PlaybackProgress` 改为接收进度 state 和 seek action。
   - `PlaybackControls` 改为接收控制 state 和 playback action。
   - `ChapterDisplay` 改为接收当前章节 state。
   - `ChapterList` 改为接收章节列表、当前进度和 chapter action。
   - `SubtitlesView` 改为接收字幕列表、当前进度和字幕 action。
   - `BookmarkList` 改为接收书签对话框 state、播放进度 state 和 bookmark action，不再直接接收 `PlayerViewModel`。
   - 第二轮结束时执行 `rg -n "viewModel: PlayerViewModel|PlayerViewModel\\(|collectAsStateWithLifecycle\\(" app/src/main/java/com/viel/aplayer/ui/player app/src/main/java/com/viel/aplayer/ui/miniplayer app/src/main/java/com/viel/aplayer/ui/bookmarks -S --glob "*.kt"`，逐项确认只剩 Route、MiniPlayerRoute 或明确标注的高频 StateHolder；不得剩下通用桥接层。
6. 高频状态处理规则：
   - 播放进度继续在最小子组件范围内收集，本阶段不把播放进度上提到 `PlayerScreen` 或 `PlayerUiState`。
   - mini player progress 不并入 `PlayerUiState` 大对象。
   - 字幕滚动和拖拽状态保留局部 private state。
7. `MiniPlayerOverlay` 最后处理：
   - 先保持当前低频状态外层监听、高频状态内层监听的结构。
   - 播放器主流程回归通过后，新增 `MiniPlayerRoute` 作为本阶段最后一步。
   - `MiniPlayerRoute` 持有 mini player 外层可见性收集；旧 `MiniPlayerOverlay` 不再作为收集 ViewModel 的入口保留。
   - `MiniPlayerRoute` 可以局部收集 `playbackState`、`metadataState`、`miniPlayerProgress` 和 `currentBookAvailability(...)`，但只向 `MiniPlayerOverlay` 或具体渲染组件传入渲染状态与 `MiniPlayerActions`，不把 `PlayerViewModel` 继续透传。
   - 不破坏 `hasActiveTrack`、`isFullPlayerVisible`、`isMiniPlayerHidden` 的低频订阅优化。

回归验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 冷启动恢复最近播放预览正常。
- 播放、暂停、快进、快退正常。
- 拖动进度条正常。
- Undo seek 正常。
- 倍速切换正常。
- 睡眠定时正常。
- 音量调整正常。
- 上一章、下一章正常。
- 章节列表打开、关闭、点击跳转正常。
- 字幕页显示和自动滚动正常。
- 相关书籍页加载和点击播放正常。
- 书签新增、编辑、删除正常。
- 全屏播放器展开、最小化、关闭正常。
- 预测返回手势正常。
- mini player 显示、隐藏、点击展开正常。
- 分轨不可用弹窗显示、取消、确认跳过正常。
- 竖屏、横屏、平板布局分别手工检查。
- MiuixBlur 下播放器、底部 sheet、书签弹窗没有背景穿帮。
- 检查 `PlayerViewModel` 的公开方法映射表已更新，没有 Screen 直连后遗留的无人调用方法。
- 检查播放进度变化不会导致整个 `PlayerScreen` 大范围重组。

阶段自检：

- 迁移目标与边界：符合。播放器最后迁移，只改播放器 UI/ViewModel 边界，不改 PlaybackManager、Media3、VFS 播放数据源。
- 当前代码现状：符合。当前 `PlayerOverlay` 是全屏播放器可见性外壳，`PlayerScreen` 和多个子组件仍直接拿 `PlayerViewModel`；本阶段按外壳、主屏、子组件、mini player 顺序处理。
- 自洽性：符合。高频进度不并入 `PlayerUiState`，避免为了 stateless 化扩大重组范围。
- 任务清晰度：符合。第一轮只允许按高频职责拆分的小型 StateHolder 作为临时兼容点，第二轮必须删除这些兼容点并清理列出的具体子组件。

<!-- 注释：最终收口阶段只删除迁移期兼容代码和重复命名，不再引入新架构概念，避免在迁移末尾制造新的不稳定因素。 -->
## 11. 阶段 8：最终收口

目标：删除迁移期兼容层，统一命名和边界。

具体任务：

1. 检查命名：
   - 所有页面入口使用 `XxxRoute`。
   - 所有纯渲染页面使用 `XxxScreen`。
   - 保留 `SearchContent`、播放器布局内部 `PlayerContentShell` 等确有内部职责的 Content 命名，并在注释中说明它不是页面入口；只删除页面入口级、迁移期遗留且已经没有独立职责的 `XxxContent` 命名。
2. 检查 ViewModel 泄漏：
   - `rg -n "viewModel: .*ViewModel|[A-Za-z0-9_]+ViewModel\\(|collectAsStateWithLifecycle\\(" app/src/main/java/com/viel/aplayer/ui -S --glob "*.kt"`
   - 确认 `ViewModel` 参数只保留在 Route 或明确标注的高频 StateHolder 中；普通 Overlay 外壳迁移完成后不得继续接收 ViewModel。
   - 确认 collect 只保留在 Route 或明确的高频隔离 StateHolder 中。
   - 对每个例外加注释说明保留原因，例如播放器进度高频隔离；Preview 中直接构造 `PlayerViewModel()` 也必须清理为 mock state，不能作为例外保留。
3. 检查 ViewModel 边界：
   - `rg -n "androidx\\.compose|NavController|Toast\\.makeText|startActivity|LayerBackdrop|LazyListState|LazyGridState|Modifier" app/src/main/java/com/viel/aplayer/ui -S --glob "*ViewModel.kt"`
   - 确认 ViewModel 没有新增 Compose UI、导航、Activity 启动、Toast 渲染和 blur 采样依赖。
   - 确认每个 ViewModel 的公开方法都有 Route、宿主或系统入口调用依据。
   - 确认每个 ViewModel 的一次性反馈通过 event 暴露，不直接渲染 UI。
4. 检查 Action 重复：
   - 删除已被 sealed `XxxAction` 替代的重复 lambda 聚合。
   - 播放器的 `PlayerActions` 保留为性能隔离层，并在注释中说明它用于降低高频重组和参数噪音。
   - 不创建跨页面的总 action/event 聚合；跨页面编排继续由对应 Route 或 `APlayerApp` 按页面级职责完成。
5. 检查 Event 边界：
   - 页面内一次性事件不要全部塞进全局 `UiEvent`。
   - 跨页面通用 Toast 和分轨不可用这类事件继续复用 `UiEvent`。
6. 检查 Route 膨胀：
  - `HomeRoute` 不承担列表渲染。
  - `SettingsRoute` 不承担设置项布局。
  - `SearchRoute` 不承担搜索结果 item 绘制。
  - `DetailRoute` 不承担详情视觉排版。
  - `PlayerRoute` 不承担播放器手势和布局细节。
  - `MiniPlayerRoute` 不承担迷你播放器视觉布局，只做可见性、低频/高频状态收集和 action 接线。

阶段自检：

- 迁移目标与边界：符合。最终阶段只删除兼容层和修正命名，不引入新架构概念。
- 当前代码现状：符合。允许保留确有职责的内部 `Content` 组件和高频隔离 collect，不做机械清理。
- 自洽性：符合。Route 不变成渲染层，ViewModel 不变成 UI 副作用层。
- 任务清晰度：符合。每个检查都有命令、例外条件和处理动作。

最终验证：

- 执行 `.\gradlew.bat compileDebugKotlin`。
- 执行 `.\gradlew.bat assembleDebug`。
- 完整手工主流程：
  - 添加媒体库。
  - 浏览首页。
  - 打开详情。
  - 编辑书籍信息。
  - 搜索书籍。
  - 播放书籍。
  - 切换播放器章节、字幕、书签、相关内容。
  - 修改设置并返回首页。
  - 删除书籍。
- 检查 `git diff`，确认没有数据层、VFS、导入扫描和播放内核的非必要改动。

<!-- 注释：本节列出迁移过程中的停止条件，出现这些现象时必须先修复当前阶段，而不是继续推进下一页面。 -->
## 12. 阶段停止条件

任一阶段出现以下情况，停止进入下一阶段：

- `compileDebugKotlin` 失败。
- 当前页面入口打不开。
- 当前页面主操作流程出现崩溃。
- `Screen` 层重新引入 ViewModel 依赖。
- `Route` 层开始承载大段渲染布局。
- ViewModel 新增 Compose UI、NavController、Activity、Toast 渲染、LayerBackdrop 或滚动状态依赖。
- ViewModel 为了适配 Action 变成转发上帝类，公开方法数量明显膨胀且没有页面动作来源。
- 播放器阶段之前改动了播放器高频进度状态。
- MiuixBlur 模式出现黑底、穿帮、闪烁且无法解释。
- 为了统一模板新增了无实际职责的抽象类或上帝式 action/event 聚合。
