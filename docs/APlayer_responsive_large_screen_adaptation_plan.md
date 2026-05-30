# APlayer 响应式布局与大屏设备适配方案

> 文档状态：实施方案，不直接修改现有代码。
>
> 变更注释：本文件作为独立方案文档新增，避免覆盖现有架构、导入流程或播放器文档；后续代码落地时，每一处代码改动都需要在对应文件内继续保留清晰中文注释。

## 1. 目标

变更注释：本节定义适配边界，避免把“大屏适配”扩大成一次不受控的 UI 重写。

APlayer 当前是一个以有声书库、详情悬浮层、全屏播放器、搜索悬浮层和迷你播放器为核心的 Compose 应用。响应式改造的目标不是为“平板”另做一套页面，而是让同一套 UI 根据当前窗口宽度、高度、折叠状态和多窗口大小自动切换布局。

核心目标：

1. 手机竖屏继续保留当前体验：主页列表、详情全屏悬浮层、播放器全屏悬浮层、底部迷你播放器。
2. 中等宽度窗口优先减少留白：主页可升级为双列或自适应网格，详情和搜索仍可作为沉浸式 overlay。
3. 展开宽度窗口显示更多有效内容：书库保持独立浏览，详情和播放器通过铺满 overlay 内部两栏承载更多内容，搜索结果利用网格。
4. 大窗口和桌面窗口的 overlay 外壳必须铺满整个应用窗口；内部正文、设置项、播放控件、封面和搜索栏可以通过 pane、列宽和最大内容宽度控制可读性。
5. 分栏只改变功能和信息的空间组织，不删除现有入口、不隐藏关键元信息、不让某个 pane 成为“简化版页面”。
6. 保留现有 Haze/Material 双模式、预测返回、迷你播放器、同 Activity overlay 层级和当前 ViewModel 单向数据流。

非目标：

1. 不在第一版引入 Navigation 3 或全新路由体系。
2. 不把设置页强行并入主 Activity。
3. 不重写播放、扫描、导入、书库数据模型。
4. 不用手机单手操作逻辑约束大屏体验；单手触达只作为 Compact 布局的约束，大屏优先保证信息密度、功能完整和沉浸铺满。

## 2. 官方适配依据

变更注释：本节记录方案依据，后续实现时优先跟随 Android 官方自适应模型，而不是依赖 isTablet 之类的设备类型判断。

Android 官方建议使用窗口大小类做高层布局决策。窗口大小类按应用当前可用窗口分类，而不是按物理设备分类；同一设备在分屏、ChromeOS 窗口、折叠屏展开/折叠或横竖屏切换时，窗口大小类都可能改变。

宽度断点按官方文档采用：

| 宽度类 | 范围 | APlayer 解释 |
| --- | --- | --- |
| Compact | `< 600dp` | 手机竖屏，保持当前单列和全屏 overlay |
| Medium | `600dp <= width < 840dp` | 竖向平板、折叠屏内屏竖向、分屏窗口，允许双列书库但谨慎并排详情 |
| Expanded | `840dp <= width < 1200dp` | 横向平板、展开折叠屏，启用书库高密度网格、详情铺满两栏、播放器铺满两栏 |
| Large | `1200dp <= width < 1600dp` | 大平板、桌面窗口，启用更稳定的 overlay 内部分栏与侧边辅助区 |
| Extra Large | `>= 1600dp` | 桌面级窗口，overlay 仍铺满窗口，内部通过多 pane、最大列宽和留白分配避免 UI 散开 |

高度断点也必须参与判断：当高度 `< 480dp` 时，即使宽度达到 Medium，也不应强行显示复杂横向分栏；手机横屏、悬浮小窗、折叠屏半屏都应退回紧凑布局。

参考资料：

1. Android 官方窗口大小类：<https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes>
2. Android 官方 Canonical layouts：<https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts>
3. Android 官方自适应导航：<https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation>
4. Compose Material3 Adaptive 版本说明：<https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive>

## 3. APlayer 当前 UI 结构判断

变更注释：本节对齐当前代码事实，后续改造应围绕这些边界渐进拆分，不应直接推倒重写。

当前主结构：

1. `APlayerApp.kt` 是顶层容器，负责统一创建 `LibraryViewModel`、`PlayerViewModel`、`DetailViewModel`、`EditBookViewModel`、`SearchViewModel`。
2. `APlayerNavHost.kt` 目前只有主 route `home`，设置页通过独立 `SettingsActivity` 打开，搜索、详情和播放器主要走同 Activity 内 overlay。
3. `HomeScreen.kt` 是当前书库页，包含顶部栏、过滤 chip、最近书籍横向列表、作者分组列表、FAB、长按操作弹窗和迷你播放器避让。
4. `DetailOverlay.kt` 与 `DetailScreen.kt` 以全屏悬浮层方式显示书籍详情，内部已经有 Haze 采样源、预测返回和下拉关闭。
5. `PlayerOverlay` 挂载 `NewPlayerScreen`，当前播放器是全屏模式，内部通过 `PlayerScreenMode` 在播放器、书签、字幕、推荐之间切换。
6. `SearchOverlay.kt` 是同 Activity 内全屏搜索层，已处理 IME padding 和 Haze 背景。
7. `BottomNavTabs.kt` 是播放器内容 tab，不是应用全局导航，不应直接替换成大屏 app navigation rail。

结论：

第一版响应式改造应该保留“主页底层 + overlay 兄弟层”的根结构。大屏 overlay 不缩成窄弹窗，而是继续铺满应用窗口；宽屏收益主要通过详情 overlay 内部两栏、播放器 overlay 内部两栏和主页书库高密度布局实现。主页不做“书库-详情双 pane”，点击书籍仍打开铺满式详情 overlay。

## 4. 总体架构

变更注释：本节给出第一版落地架构，重点是新增一个小型 adaptive 上下文，再逐步把它传入各页面。

建议新增一个轻量 UI 模型：

```kotlin
package com.viel.aplayer.ui.adaptive

// 改动注释：用项目自己的宽度枚举包住官方窗口大小类，避免业务 UI 到处直接依赖第三方 API 形状。
enum class APlayerWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge
}

// 改动注释：用高度紧凑标记保护手机横屏和小窗场景，防止宽度足够但高度不够时强行显示复杂横向分栏。
data class APlayerAdaptiveInfo(
    val widthClass: APlayerWidthClass,
    val isHeightCompact: Boolean,
    val windowWidthDp: Int,
    val windowHeightDp: Int
) {
    // 改动注释：详情和播放器的两栏只在宽度展开且高度不紧凑时开启，确保小窗与横屏手机不会出现拥挤分栏。
    val canUseOverlayTwoPane: Boolean
        get() = widthClass >= APlayerWidthClass.Expanded && !isHeightCompact

    // 改动注释：Medium 宽度适合把书库从单列升级为自适应网格，但仍不默认打开详情侧栏。
    val canUseLibraryGrid: Boolean
        get() = widthClass >= APlayerWidthClass.Medium
}
```

推荐第一版使用 `androidx.compose.material3.adaptive` 获取窗口信息。APlayer 当前 Compose BOM 已经较新，但 adaptive 相关 artifact 仍建议显式加入版本，避免依赖解析漂移。

```toml
# 改动注释：新增官方 Material3 Adaptive 稳定版版本号，第一版优先选择稳定线，降低 beta API 抖动风险。
material3Adaptive = "1.2.0"

# 改动注释：adaptive 提供 currentWindowAdaptiveInfo/currentWindowSize 等窗口感知能力。
androidx-material3-adaptive = { group = "androidx.compose.material3.adaptive", name = "adaptive", version.ref = "material3Adaptive" }

# 改动注释：adaptive-layout 作为后续可选能力保留；第一版不使用 ListDetailPaneScaffold 做书库-详情双 pane。
androidx-material3-adaptive-layout = { group = "androidx.compose.material3.adaptive", name = "adaptive-layout", version.ref = "material3Adaptive" }

# 改动注释：adaptive-navigation 只在未来需要 pane 内导航状态时引入；当前详情与播放器优先使用自定义两栏 overlay。
androidx-material3-adaptive-navigation = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation", version.ref = "material3Adaptive" }

# 改动注释：NavigationSuiteScaffold 适合未来有多个顶级 destination 时使用；当前 APlayer 只有 home route，第一版不强制启用。
androidx-material3-adaptive-navigation-suite = { group = "androidx.compose.material3", name = "material3-adaptive-navigation-suite" }
```

## 5. 断点行为总览

变更注释：本节把每个窗口范围的用户体验固定下来，后续实现和验收都按这张表执行。

| 模块 | Compact | Medium | Expanded | Large / Extra Large |
| --- | --- | --- | --- | --- |
| 主容器 | 现有单 Activity + overlay | 同左，overlay 外壳仍铺满窗口 | 同 Activity，overlay 铺满；不做书库-详情双 pane | overlay 铺满窗口，内部用两栏和列宽控制 |
| 主页书库 | `LazyColumn` 分组列表 | `LazyVerticalGrid(GridCells.Adaptive)` 或双列列表 | 高密度书库网格，点击书籍打开铺满详情 overlay | 高密度书库网格，可保留最近/作者/过滤/导入/长按菜单全部入口 |
| 最近书籍 | 横向 `LazyRow` | 2 行/自适应横向 shelf | 保留在书库 pane 顶部 | 可变为右侧推荐/继续收听区 |
| 详情页 | 全屏 overlay | 全屏 overlay，内部内容可居中限宽 | 铺满式详情 overlay；左栏封面+元数据，右栏 description | 铺满式详情 overlay；左栏封面+元数据，右栏 description，原功能入口全部保留 |
| 播放器 | 全屏 overlay + 底部 tabs | 全屏 overlay，内部内容可居中限宽 | 铺满式播放器 overlay；左栏封面/书签/字幕/相关内容窗格，右栏控制面板 | 铺满式播放器 overlay；左栏内容窗格，右栏控制面板，章节/书签/字幕/相关入口不丢失 |
| 搜索 | 全屏 overlay | 全屏 overlay，内部搜索栏可限宽 | 铺满式搜索 overlay + 网格结果 | 搜索历史、命令建议、结果和详情预览并排显示 |
| 设置 | 独立 Activity 单列 | 单列最大宽度 720dp | 两列设置分组或最大宽度 960dp | 最大宽度 1120dp，分组双列 |
| 迷你播放器 | 底部避让 | 底部居中，最大宽度 720dp | 底部 dock，避免遮挡书库网格和铺满 overlay 入口 | 可升级为常驻 Now Playing 区，但仍保留迷你播放器所有控制 |

## 6. 主页适配方案

变更注释：本节只拆主页展示形态，不改变 `LibraryViewModel` 的数据聚合职责。

### 6.1 Compact：保持现状

手机竖屏继续使用当前 `HomeScreen` 结构：

1. 顶部 `CenterAlignedTopAppBar`。
2. 横向 FilterChip。
3. 最近书籍 `LazyRow`。
4. 按作者分组的 `LazyColumn`。
5. FAB 位于右下角，并继续根据迷你播放器可见性增加底部 padding。

### 6.2 Medium：书库 feed 化

Medium 宽度不默认启用详情 pane，先把列表升级为更高信息密度：

1. 最近书籍继续横向 shelf，但卡片宽度可从固定手机尺寸提升到 180dp-220dp。
2. 作者分组下面的书籍可以用 `LazyVerticalGrid(GridCells.Adaptive(minSize = 320.dp))`。
3. 分组标题使用 `GridItemSpan(maxLineSpan)` 横跨全宽。
4. `AudiobookListItem` 需要支持 grid 宽度，不要默认认为自己横向占满屏幕。

示意代码：

```kotlin
// 改动注释：将主页内容从 HomeScreen 主函数中抽出，便于按窗口大小切换 LazyColumn 或 LazyVerticalGrid。
@Composable
private fun HomeLibraryContent(
    adaptiveInfo: APlayerAdaptiveInfo,
    groupedByAuthor: Map<String, List<BookWithProgress>>,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    if (adaptiveInfo.canUseLibraryGrid) {
        // 改动注释：Medium 及以上用自适应网格消化横向空间，minSize 以列表项可读性为底线。
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 320.dp)) {
            groupedByAuthor.forEach { (author, books) ->
                // 改动注释：作者分组标题横跨整行，维持当前按作者组织的阅读模型。
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AuthorSectionHeader(author = author)
                }
                items(books, key = { it.book.id }) { book ->
                    // 改动注释：复用现有有声书条目，先完成布局适配，再决定是否单独做大屏卡片。
                    AudiobookListItem(
                        title = book.book.title,
                        author = book.book.author,
                        narrator = book.book.narrator,
                        duration = book.book.totalDurationMs,
                        coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                        coverLastUpdated = book.book.lastScannedAt,
                        progressPercent = book.progressPercent,
                        onClick = { onNavigateToDetail(book.book.id) }
                    ) {
                        onLoadBook(book.book.id)
                        onNavigateToPlayer()
                    }
                }
            }
        }
    } else {
        // 改动注释：Compact 保留现有 LazyColumn，确保手机体验不被网格改造影响。
        HomeLibraryColumn(
            groupedByAuthor = groupedByAuthor,
            onNavigateToDetail = onNavigateToDetail,
            onLoadBook = onLoadBook,
            onNavigateToPlayer = onNavigateToPlayer
        )
    }
}
```

### 6.3 Expanded：书库保持独立浏览

Expanded 及以上不做“书库-详情双 pane”。主页仍然只承载书库浏览、过滤、导入、最近书籍、作者分组和长按操作；点击书籍后打开铺满式详情 overlay。

建议行为：

1. 主页书库使用自适应网格或高密度双列列表，充分利用横向空间。
2. 最近书籍、作者分组、过滤 chip、搜索、设置、导入 FAB、长按菜单都必须保留。
3. 点击书籍仍调用现有 `detailViewModel.selectBook(book)`，由 `DetailOverlay` 展示详情。
4. 不新增右侧详情 pane，不在主页长期占用空间显示详情空态。
5. 播放入口仍由书籍条目或详情 overlay 内触发 `PlayerViewModel.loadBook(id)`。

关键拆分：

1. `HomeScreen.kt` 只负责列表/网格适配，不嵌入详情内容。
2. `DetailOverlay.kt` 负责 Compact 到 Large 的详情展示；大屏时在 overlay 内部改成两栏。
3. `DetailViewModel` 不需要为“主页右侧详情 pane”新增状态，只保留当前选中书籍和 overlay 可见性。

## 7. 播放器适配方案

变更注释：播放器是最高风险区域，第一版只做布局拆分，不改变播放状态、进度流和 Media3 控制逻辑。

当前 `NewPlayerScreen` 已经包含：

1. 封面背景和动态色。
2. `PlayerAppBar`。
3. 播放页、书签页、字幕页、推荐页之间的 `PlayerScreenMode`。
4. `BottomNavTabs`。
5. 章节列表 sheet、书签 dialog、Snackbar。
6. 多个 Stateful 隔离组件，用来降低高频进度重组影响。

### 7.1 Compact：保持全屏沉浸

手机上继续保持现状：

1. 封面和控制区上下排列。
2. 书签/字幕/推荐通过底部 tabs 切换。
3. 章节列表继续使用 bottom sheet。
4. 下滑、预测返回、最小化逻辑不变。

### 7.2 Medium：铺满 overlay，内部限制内容宽度

Medium 宽度常见于折叠屏内屏竖向或小平板竖屏，不建议直接左右并排。

建议：

1. 播放器 overlay 的 Surface 仍然 `fillMaxSize()` 铺满窗口，不做窄弹窗。
2. 封面最大边长 360dp。
3. 控制区最大宽度 560dp。
4. 底部 tabs 仍保留，但水平 padding 不随窗口无限增大。

### 7.3 Expanded：铺满式播放器两栏

Expanded 及以上使用“左内容窗格 + 右控制面板”的两栏结构：

1. 左栏内容窗格：封面、书签、字幕、相关内容在同一个内容区域内切换或分段展示。
2. 右栏控制面板：标题、作者/播音、章节标题、进度、播放/暂停、前进后退、进度模式、睡眠/跳静音等控制入口。
3. `BottomNavTabs` 在大屏上不再放在底部；它改为左栏内容窗格顶部或侧边的模式切换，用于在封面、书签、字幕、相关之间切换。
4. 字幕、书签和相关内容不占用右侧控制面板，确保播放控制始终稳定可见。
5. 章节列表可以作为左栏内容窗格里的一个模式或从右侧控制面板触发；Compact 仍保留 bottom sheet。
6. 全屏播放器 overlay 在 Expanded/Large 仍铺满窗口，只是内部由单列改为两栏，不能退化成居中窄面板。
7. 功能完整性：新增书签、编辑/删除书签、点击字幕跳转、点击章节跳转、推荐书籍直接播放、更多菜单、最小化/关闭都必须保留。

示意代码：

```kotlin
// 改动注释：播放器按窗口大小选择紧凑布局或铺满两栏布局，播放状态仍由同一个 PlayerViewModel 提供。
@Composable
private fun PlayerResponsiveContent(
    adaptiveInfo: APlayerAdaptiveInfo,
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    controls: PlaybackControlState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState
) {
    if (adaptiveInfo.canUseOverlayTwoPane) {
        // 改动注释：大屏播放器 overlay 外壳继续铺满窗口，内部左侧承载封面/书签/字幕/相关内容，右侧固定承载控制面板。
        Row(modifier = Modifier.fillMaxSize()) {
            PlayerContentPane(
                modifier = Modifier.weight(0.62f),
                viewModel = viewModel,
                metadata = metadata,
                settings = settings,
                actions = actions,
                glassEffectMode = glassEffectMode
            )
            PlayerControlPane(
                modifier = Modifier.weight(0.38f),
                metadata = metadata,
                controls = controls,
                settings = settings,
                actions = actions,
                glassEffectMode = glassEffectMode,
                hazeState = hazeState
            )
        }
    } else {
        // 改动注释：手机和高度紧凑窗口继续走当前全屏播放器，保证手势、sheet 和底部 tab 行为稳定。
        PlayerCompactPane(
            viewModel = viewModel,
            metadata = metadata,
            controls = controls,
            settings = settings,
            actions = actions,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
    }
}
```

## 8. 详情页适配方案

变更注释：详情页改造的核心是把“内容”和“全屏 overlay 外壳”拆开；大屏不做主页右侧详情 pane，而是在详情 overlay 内部改成左右两栏。

建议拆分：

1. `DetailScreen` 保留为兼容入口，但内部委托给 `DetailContent`。
2. `DetailOverlay` 继续负责 Compact/Medium 的全屏进入、退出、下拉关闭。
3. 新增 `DetailTwoColumnContent` 或等价内部布局，只在 `DetailOverlay` 铺满外壳里使用；不要新增主页右侧 `DetailPane`。
4. 右上角更多菜单、编辑 overlay、播放按钮仍通过原 lambda 向上冒泡。

布局策略：

1. Compact：封面在上，标题、元信息、按钮、路径、简介垂直排列。
2. Medium：overlay 铺满窗口，内容最大宽度 720dp，居中显示，避免详情文本拉满全屏。
3. Expanded：详情 overlay 铺满窗口，左栏显示封面、标题、作者/播音、元信息 chip、播放按钮、编辑/更多菜单、文件路径和可用性状态；右栏只显示 description，并保持独立滚动。
4. Large：继续使用左栏封面+元数据、右栏 description；可以调整左右栏比例，但不能把 description 混回左栏，也不能把元数据拆散到右栏。

## 9. 搜索适配方案

变更注释：搜索已经从独立 Activity 改成同 Activity overlay，大屏适配应继续复用同一个 `SearchViewModel`，不要恢复跨 Activity 搜索。

建议：

1. Compact：保持当前全屏搜索 overlay 和 IME padding。
2. Medium：搜索 overlay 铺满窗口，内部搜索面板最大宽度 720dp，顶部居中，结果列表不超过 720dp。
3. Expanded：搜索面板宽度 840dp-960dp，结果使用 `LazyVerticalGrid(GridCells.Adaptive(minSize = 320.dp))`。
4. Large：可选左侧搜索条件/历史，右侧结果网格；搜索历史、命令建议、清空历史、删除单条历史、直接播放和打开详情都必须有落点。点击结果打开铺满式详情 overlay，不在搜索页内做右侧详情 pane。

键盘与焦点：

1. 保留滚动时清理焦点的行为。
2. ChromeOS/桌面窗口需要支持硬键盘 Enter 搜索。
3. 大屏搜索不应因为 IME padding 导致顶部搜索栏跳动，只调整结果列表 bottom padding。

## 10. 设置页适配方案

变更注释：设置页当前是独立 Activity，第一版只做内容宽度约束和分组排版，不改生命周期边界。

建议：

1. Compact：保持当前单列。
2. Medium：`LazyColumn` 外包 `Box` 居中，内容最大宽度 720dp。
3. Expanded：设置分组可以使用两列，每列展示完整 section，避免单行文本横跨整屏。
4. 删除媒体库根目录仍必须二次确认；任何物理删除文件操作仍需要单独用户同意。

示意：

```kotlin
// 改动注释：设置页在大屏上限制内容宽度，避免 Switch、Slider 和长说明文字被拉到难以阅读。
Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = if (adaptiveInfo.widthClass >= APlayerWidthClass.Expanded) 960.dp else 720.dp)
    ) {
        // 改动注释：第一版仍沿用现有设置条目，后续再把 section 拆成两列。
        settingsSections()
    }
}
```

## 11. 根容器与 overlay 层级

变更注释：APlayer 现在的 Haze 采样源依赖“底层 NavHost”和“上层 overlay”作为兄弟节点；大屏改造不能把 effect 放回 source 子树里。

保持当前层级原则：

1. `APlayerNavHost` 仍是底层内容 source。
2. `DetailOverlay`、`PlayerOverlay`、`EditBookOverlay`、`SearchOverlay` 仍作为同级兄弟挂载，并且 overlay 外层容器在所有窗口大小下都使用全屏铺满。
3. 大屏详情仍走铺满式 `DetailOverlay`，不嵌入主页右侧 pane。
4. 编辑 overlay 如果覆盖详情 overlay，仍使用专属 `detailHazeState`，避免 Haze 自采样死锁。
5. 迷你播放器在 Expanded 及以上不应该遮住书库网格、搜索结果或打开详情/播放器 overlay 的入口。
6. 大屏分栏只允许移动入口位置，不允许删除入口；每个 Compact 页面上的可见信息和可触发动作，都需要在 Expanded/Large 的某个 pane、tab、菜单或辅助区中存在。

建议新增：

```kotlin
// 改动注释：顶层只负责计算一次自适应信息，再显式传给各个屏幕，避免每个 Composable 各自读窗口导致状态分叉。
val adaptiveInfo = rememberAPlayerAdaptiveInfo()

// 改动注释：主页、详情、播放器、搜索都共享同一份 adaptiveInfo，窗口变化时由 Compose 统一重组。
APlayerNavHost(
    adaptiveInfo = adaptiveInfo,
    navController = navController,
    libraryViewModel = libraryViewModel,
    playerViewModel = playerViewModel,
    detailViewModel = detailViewModel,
    canStartNavigation = canStartNavigation,
    navigateBack = navigateBack,
    searchViewModel = searchViewModel
)
```

## 12. 文件级实施清单

变更注释：本节是后续代码落地顺序；每个条目都保持小步可编译，方便在出问题时快速定位。

第一阶段：基础能力

1. 新增 `app/src/main/java/com/viel/aplayer/ui/adaptive/APlayerAdaptiveInfo.kt`
   - 注释：封装窗口大小类、窗口 dp 尺寸和高度紧凑判断。
2. 修改 `gradle/libs.versions.toml`
   - 注释：声明 Material3 Adaptive 依赖版本和 artifact。
3. 修改 `app/build.gradle.kts`
   - 注释：引入 adaptive 依赖，不改变现有 Compose、Haze、Navigation 依赖。
4. 修改 `APlayerApp.kt`
   - 注释：顶层计算 adaptiveInfo 并传入 NavHost、DetailOverlay、PlayerOverlay、SearchOverlay。

第二阶段：主页

1. 修改 `HomeScreen.kt`
   - 注释：新增 `adaptiveInfo` 参数。
   - 注释：抽出 `HomeLibraryColumn`，保持手机原逻辑。
   - 注释：新增 `HomeLibraryGrid`，供 Medium 及以上使用。
   - 注释：Expanded 时接入 `HomeListDetailLayout`。
2. 修改 `AudiobookListItem.kt` / `RecentlyItem.kt`
   - 注释：支持 grid cell 内宽度，不依赖全屏宽度。

第三阶段：详情

1. 修改 `DetailScreen.kt`
   - 注释：拆出 `DetailContent`，保留 `DetailScreen` 作为全屏包装入口。
   - 注释：新增大屏两栏内容布局，左侧封面+元数据，右侧 description。
2. 修改 `DetailOverlay.kt`
   - 注释：Compact/Medium 仍全屏单列；Expanded/Large 仍全屏铺满，但内部切换为两栏。
3. 修改 `DetailViewModel.kt`
   - 注释：不为主页右侧详情 pane 新增状态，继续复用当前选中书籍和 overlay 可见性。

第四阶段：播放器

1. 修改 `PlayerScreen.kt`
   - 注释：抽出 `PlayerCompactPane`、`PlayerContentPane`、`PlayerControlPane`。
   - 注释：保留当前 Stateful 隔离组件，不把高频进度重新提升到父层。
2. 修改 `BottomNavTabs.kt`
   - 注释：保持 Compact 底部 tabs；新增大屏左侧内容窗格内的 tab/segmented 形态时不要破坏原预览。
3. 修改 `PlayerOverlay` 所在文件
   - 注释：overlay 外壳始终铺满；根据 adaptiveInfo 只切换内部单列/两栏布局。

第五阶段：搜索和设置

1. 修改 `SearchOverlay.kt`
   - 注释：Medium 以上限制搜索容器宽度，Expanded 以上结果改为自适应网格。
2. 修改 `SettingsActivity.kt`
   - 注释：设置页内容加最大宽度；Expanded 后再考虑双列 section。

## 13. 验收矩阵

变更注释：本节定义完成标准，避免只在单个手机预览里“看起来能跑”。

必须验证：

1. `.\gradlew.bat compileDebugKotlin`
2. Compose Preview：
   - `widthDp = 393, heightDp = 851` 手机竖屏
   - `widthDp = 851, heightDp = 393` 手机横屏，高度紧凑
   - `widthDp = 700, heightDp = 900` Medium 竖向平板
   - `widthDp = 1000, heightDp = 700` Expanded 横向平板
   - `widthDp = 1366, heightDp = 768` Large 桌面窗口
3. 交互验证：
   - 主页滚动、过滤、最近书籍、长按菜单。
   - 点击书籍后 Compact 打开详情 overlay，Expanded/Large 打开铺满式两栏详情 overlay。
   - 播放器打开、最小化、切换封面/书签/字幕/相关内容窗格。
   - 搜索输入时 IME 不遮挡最后一条结果。
   - Haze 和 Material 两种模式下 overlay 不闪烁、不自采样死锁。
   - 设置页删除媒体库根目录仍出现二次确认。
   - Expanded/Large 下详情、播放器、搜索 overlay 外壳都铺满窗口，而不是变成居中窄弹窗。
   - 分栏后逐项核对功能入口：详情播放/编辑/更多/搜索/路径/简介，播放器章节/书签/字幕/推荐/控制区，搜索历史/命令建议/结果/直接播放，均不得丢失。

建议后续加入截图回归：

1. 手机竖屏主页。
2. Medium 主页网格。
3. Expanded 铺满式两栏详情 overlay。
4. Expanded 铺满式两栏播放器 overlay。
5. Large 搜索结果网格。

## 14. 风险与处理

变更注释：本节提前标出容易踩坑的位置，后续实现时应逐条对照。

1. Haze 层级风险
   - 风险：把 `hazeEffect` 放进自己的 `hazeSource` 子树，可能再次触发采样死锁或闪烁。
   - 处理：保持 source 和 effect 为兄弟层；大屏 pane 不复用全屏 overlay 的 Haze 外壳。

2. 重组性能风险
   - 风险：播放器进度高频变化导致大屏 pane 大面积重组。
   - 处理：保留 `PlaybackProgressStateful`、`ChapterDisplayStateful`、`SubtitlesViewStateful` 等局部隔离。

3. 状态丢失风险
   - 风险：窗口从 Expanded 缩到 Compact 时，当前选中详情丢失。
   - 处理：详情选中状态继续由 `DetailViewModel` 管理；布局只决定 overlay 内部使用单列还是两栏。

4. 高度紧凑风险
   - 风险：手机横屏被误判成 Medium 后显示拥挤两栏。
   - 处理：所有两栏条件都必须同时检查 `!isHeightCompact`。

5. 图像内存风险
   - 风险：大屏网格同时加载更多封面，增加 Coil 和 Bitmap 压力。
   - 处理：优先使用 `thumbnailPath`，保留稳定 key 和 `coverLastUpdated` 缓存戳。

6. 交互路径风险
   - 风险：大屏把详情改为两栏后，用户找不到原来的关闭/返回语义。
   - 处理：所有窗口大小下详情仍是 overlay，返回/下拉/关闭语义保持一致，只改变 overlay 内部排版。

7. 功能丢失风险
   - 风险：分栏时为了“干净”只搬主视觉和主按钮，遗漏编辑、搜索、路径、书签管理、字幕跳转、推荐直接播放等次级入口。
   - 处理：每次拆两栏都用 Compact 页面做功能清单，逐项标注在大屏落到左栏、右栏、tab、菜单或辅助区；没有落点的拆分不能合并。

8. 大屏 overlay 退化风险
   - 风险：为了控制可读宽度，把 overlay 做成居中窄弹窗，破坏用户对全屏播放器、全屏搜索和沉浸详情的预期。
   - 处理：overlay 外层始终 `fillMaxSize()`，只在内部内容区使用列宽、pane 宽和最大文本宽度约束。

## 15. 推荐落地顺序

变更注释：本节给出最小可交付版本，优先让大屏主页和播放器有明显收益，再处理更细的搜索和设置。

V1 必做：

1. 新增 adaptiveInfo。
2. 主页 Medium 自适应网格。
3. 主页 Expanded 高密度书库网格，不做书库-详情双 pane。
4. 详情 Expanded/Large 铺满式 overlay + 左封面元数据/右 description 两栏。
5. 播放器 Expanded/Large 铺满式 overlay + 左内容窗格/右控制面板两栏。
6. 搜索铺满式 overlay + Medium/Large 内部宽度或网格约束。
7. 设置增加最大宽度或双列 section 约束。
8. 为详情、播放器、搜索各补一张“大屏功能落点表”，确保分栏不丢功能和信息。

V1 暂缓：

1. 全局 NavigationSuiteScaffold。
2. 三栏桌面工作台。
3. Navigation 3 scene strategy。
4. 可拖拽 pane resize。
5. ChromeOS 菜单栏或快捷键体系。

推荐原因：

当前 APlayer 只有一个真实底层 route，详情、搜索、播放器都已经是 overlay。第一版如果先引入完整全局 adaptive navigation，收益不如主页和播放器布局改造直接，且容易和现有 Haze overlay 层级发生冲突。先做 `adaptiveInfo + 页面内部响应式`，再在未来新增多个顶级 destination 后迁移到 `NavigationSuiteScaffold`，风险最低。
