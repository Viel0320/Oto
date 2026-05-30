# 播放进度与通知栏分层实现

## 目标

本实现把播放器 UI、播放状态、章节/全局进度显示和媒体通知拆成清晰的两层：

- UI 和业务状态始终读取真实播放器状态。
- 通知栏可以按“全书进度”或“当前章节进度”显示，但只能在通知专用层包装。
- 章节跳转、进度保存、书签保存都使用全书全局位置，不保存通知栏的章节相对位置。

这样可以避免通知栏为了显示章节长度而改写 `MediaSession` 暴露的 `duration/currentPosition`，进而导致 UI、章节列表、字幕或跳转逻辑拿到虚拟状态。

## 核心分层

### 真实播放层

真实播放层由 `ExoPlayer` 和 UI 连接的 `MediaSession` 组成。

路径：

- `app/src/main/java/com/viel/aplayer/service/PlaybackService.kt`
- `app/src/main/java/com/viel/aplayer/playback/PlaybackManager.kt`

`PlaybackService` 中创建真实 session：

- `mediaSession = MediaSession.Builder(this, player)`
- session id 为 `ui`
- `onGetSession()` 只返回这个真实 session

UI 通过 `PlaybackManager` 连接 `PlaybackService`，拿到的是原始 `MediaController` 状态：

- `currentMediaItemIndex` 是真实播放队列索引
- `currentPosition` 是当前物理音频文件内的位置
- `seekTo(fileIndex, positionInFile)` 是真实文件级跳转

UI 需要展示全书进度时，只在 `PlaybackManager.updateGlobalPositionAndDuration()` 中把真实文件位置映射成全书位置：

```kotlin
val fileIndex = player.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
val positionInFile = player.currentPosition.coerceAtLeast(0L)
val globalPosition = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
```

### 通知显示层

通知显示层使用独立 session，不再影响 UI controller。

路径：

- `app/src/main/java/com/viel/aplayer/service/PlaybackService.kt`
- `app/src/main/java/com/viel/aplayer/playback/NotificationProgressPlayer.kt`

`PlaybackService` 中创建通知 session：

- `notificationPlayer = NotificationProgressPlayer(player)`
- `notificationSession = MediaSession.Builder(this, notificationPlayer)`
- session id 为 `notification`
- `onUpdateNotification()` 只允许 `notificationSession` 生成系统通知

`NotificationProgressPlayer` 是通知专用的 `ForwardingPlayer`。它包装同一个底层 `ExoPlayer`，但只改写通知栏读取到的显示窗口：

- 全书模式：`duration = bookTotalDuration`，`currentPosition = bookGlobalPosition`
- 章节模式：`duration = currentChapterDuration`，`currentPosition = positionInCurrentChapter`
- 通知栏 seek：章节模式下把章节相对位置映射回全书位置，再映射到真实文件位置
- `contentPosition/contentDuration` 保留真实值，避免底层播放状态和 Media3 内部计时被污染

单文件多章节不会触发 `MediaItemTransition`。因此 `NotificationProgressPlayer` 记录 `lastDisplayWindow`，当同一个文件内跨章节导致通知显示窗口的起点或长度变化时，会主动通知 MediaSession 刷新。

## 位置模型

### 全书全局位置

全书位置由 `PositionMapper` 统一换算。

路径：

- `app/src/main/java/com/viel/aplayer/playback/PositionMapper.kt`

规则：

- `fileToGlobalPosition(fileIndex, positionInFileMs, files)`：物理文件位置转全书位置
- `globalToFilePosition(globalPositionMs, files)`：全书位置转物理文件索引和文件内位置

`BookFileEntity.durationMs` 是聚合的基础。多文件聚合时，全书位置等于前序文件时长之和加当前文件内位置。

### 章节边界

章节边界由 `ChapterTimeline` 统一计算。

路径：

- `app/src/main/java/com/viel/aplayer/playback/ChapterTimeline.kt`

规则：

- 章节按 `startPositionMs` 排序
- 当前章节使用最后一个 `startPositionMs <= currentGlobalPosition` 的章节
- 当前章节结束优先使用下一章 `startPositionMs`
- 最后一章使用全书总时长作为结束边界
- `durationMs` 只作为总时长不可用时的回退

这个规则同时服务：

- 播放器进度条
- 章节列表高亮
- Player overlay 的章节进度文本
- 通知栏章节模式
- 睡眠定时按章节结束判断

## UI 对接

UI 层继续使用全书全局位置作为稳定输入。

主要路径：

- `app/src/main/java/com/viel/aplayer/ui/components/PlaybackProgress.kt`
- `app/src/main/java/com/viel/aplayer/ui/components/ChapterList.kt`
- `app/src/main/java/com/viel/aplayer/ui/components/PlayerOverlay.kt`
- `app/src/main/java/com/viel/aplayer/ui/screens/NewPlayerScreen.kt`
- `app/src/main/java/com/viel/aplayer/ui/state/PlayerUiState.kt`
- `app/src/main/java/com/viel/aplayer/ui/viewmodel/MediaPlaybackDelegate.kt`
- `app/src/main/java/com/viel/aplayer/ui/viewmodel/PlayerSettingsManager.kt`

UI 注意点：

- `playback.currentPosition` 表示全书位置，不是章节相对位置。
- 章节模式只影响进度条显示长度和显示文本，不改变 `PlaybackState.currentPosition` 的含义。
- 章节列表点击传入章节 `startPositionMs`，由 `PlaybackManager.seekTo(globalPositionMs)` 映射到真实文件位置。
- 上一章/下一章跳转只基于章节全书起点，不读取通知栏进度。
- 字幕仍按当前真实 `MediaItem` 挂载和切换，不走通知栏显示窗口。

## 设置同步

章节/全局进度模式来自设置。

路径：

- `app/src/main/java/com/viel/aplayer/data/AppSettingsRepository.kt`
- `app/src/main/java/com/viel/aplayer/service/PlaybackService.kt`
- `app/src/main/java/com/viel/aplayer/ui/viewmodel/PlayerSettingsManager.kt`

`PlaybackService.observeNotificationProgressMode()` 订阅 `settingsFlow`，把 `isChapterProgressMode` 同步给通知专用 `NotificationProgressPlayer`。

UI 自己也读取同一份设置，但 UI 不需要改写播放器状态，只根据模式决定进度条展示窗口。

## 书签与进度保存

路径：

- `app/src/main/java/com/viel/aplayer/playback/PlaybackManager.kt`
- `app/src/main/java/com/viel/aplayer/service/PlaybackService.kt`
- `app/src/main/java/com/viel/aplayer/data/LibraryRepository.kt`

保存规则：

- 播放进度保存真实 `currentMediaItemIndex` 和真实 `currentPosition`
- 同时使用 `PositionMapper.fileToGlobalPosition()` 保存全书位置
- 通知栏添加书签时，如果命令来自 `NotificationProgressPlayer`，先取 `currentGlobalPosition()`
- 如果命令来自真实 session，则用真实文件位置映射成全书位置

不要保存通知栏的 `currentPosition`，因为章节模式下它是章节相对位置。

## 重要注意点

1. 不要把 `NotificationProgressPlayer` 传给 UI 连接的 session。

   正确结构是：

   - UI session 使用真实 `player`
   - notification session 使用 `NotificationProgressPlayer(player)`

2. 不要用 custom command 让 UI 去问服务端“真实全局进度”。

   UI 已经连接真实 session，直接读取真实 `currentMediaItemIndex/currentPosition` 后本地映射即可。

3. 不要在 `PlaybackState.currentPosition` 中存章节相对位置。

   这个字段是全书位置，章节模式只是显示层状态。

4. 单文件多章节必须考虑章节边界刷新。

   单文件内跨章节不会发生 `MediaItemTransition`，通知栏章节模式需要用 `lastDisplayWindow` 检测窗口变化。

5. 多文件聚合必须以 `BookFileEntity.index` 和 `durationMs` 为准。

   章节起点是全书坐标，不能直接拿当前文件内位置和章节起点比较。

6. 通知栏 seek 只能在通知层映射。

   章节模式下，通知 seek 的输入是章节相对位置；全书模式下，通知 seek 的输入是全书位置。两者都必须最终转换为真实 `seekTo(fileIndex, positionInFile)`。

7. 真实 `contentPosition/contentDuration` 不应被通知显示窗口覆盖。

   `NotificationProgressPlayer` 保留它们的真实值，避免 Media3 内部计时和诊断信息混乱。

## 验证记录

当前实现已通过：

```text
.\gradlew.bat compileDebugKotlin
.\gradlew.bat assembleDebug
git diff --check
```

`git diff --check` 只有 Windows 换行提示，没有空白错误。
