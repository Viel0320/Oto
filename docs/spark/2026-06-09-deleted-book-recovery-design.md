# 删除找回设计

日期：2026-06-09

状态：已通过方案讨论，等待文档评审

## 背景

APlayer 当前已经使用 `Book.status = DELETED` 表示书籍软删除。普通首页、搜索、最近添加等列表查询会过滤 `DELETED`，但 `BookFile` 占用、书籍元数据、封面路径和播放进度仍保留在本地数据库中。

“删除找回”用于恢复用户在 APlayer 内误删的本地软删除书籍。它不重新导入文件，不重建进度，不主动同步远端进度，只在恢复前确认对应媒体库根和音频文件仍可用。

## 已确认决策

- 入口放在设置页“媒体库管理”区域，“添加媒体库”下方。
- 点击入口进入独立“删除找回”界面。
- 恢复界面是纯列表，视觉上复用现有书籍列表行。
- 恢复只处理 `Book.status = DELETED` 的书籍。
- 成功恢复后用 toast 提示。
- 恢复失败用 dialog 展示失败原因，用户确认后关闭。
- 部分音频文件不可用时用确认 dialog。用户确认后恢复为 `PARTIAL`，用户取消不写库。
- ABS 远端 `REMOTE_DELETED` 不在此功能中手动复活。
- 不读取、不覆盖、不推送 ABS 远端进度。
- 新增恢复用例层单测。

## 非目标

- 不实现物理文件恢复。
- 不从 ABS 服务端重新创建、重新拉取或重新上传书籍。
- 不把远端 `REMOTE_DELETED` 的条目强行改回本地 `READY`。
- 不改播放进度、书签、阅读状态、最近播放时间。
- 不在打开“删除找回”入口时触发媒体库扫描或 ABS 同步。

## 架构

采用独立“删除找回”场景，不把恢复逻辑塞进 `LibraryViewModel`、`SettingsRootCommands` 或 `LibraryFacade`。

### UI 层

`LibraryDirectoriesSection` 在“添加媒体库”下方新增“删除找回”行。点击后进入 `DeletedBookRecoveryScreen`。

`DeletedBookRecoveryScreen` 只渲染恢复场景的展示模型，不直接依赖 Room entity。列表项复用现有 `ui.home.components.ListItem` 的主体样式，但恢复页需要恢复动作语义。实现时给列表行增加可替换尾部操作参数，使首页继续使用播放按钮，恢复页使用恢复按钮。

### Presentation 层

新增 `DeletedBookRecoveryViewModel`。它负责：

- 订阅已删除书籍列表。
- 处理单本书恢复点击。
- 管理行级 loading，避免重复点击。
- 接收恢复用例结果。
- 成功时发送 toast。
- 失败时公开失败 dialog 状态。
- 部分可用时公开确认 dialog 状态，并在用户确认后调用确认恢复命令。

### Application 层

新增恢复场景接口：

- `DeletedBookRecoveryReadModel`：观察可找回书籍展示列表。
- `DeletedBookRecoveryCommands`：执行恢复预检、确认部分恢复。

核心用例命名为 `DeletedBookRecoveryUseCase`。它负责查询书籍、根目录、文件、ABS mirror 状态，并调用 `AvailabilityChecker` 进行可用性检查。用例返回明确的 result 类型，不把异常文本直接交给 UI 判断。

### Data 层

`BookDao` 新增 `status = DELETED` 的查询能力，优先返回恢复页需要的投影。恢复写入使用事务，保证书籍状态和文件状态一起落库。

ABS 相关判断通过现有 mirror 表或 ABS catalog store 查询本地 mirror state。只读本地 mirror，不发起远端进度同步。

### Infrastructure 层

复用 `AvailabilityChecker` 判断 root 与 `AUDIO` 文件可用性。恢复用例不直接判断 SAF 权限、WebDAV HTTP 状态或 ABS track 可读性，避免协议细节扩散到 application 层。

## 数据模型

恢复列表展示模型：

```kotlin
data class DeletedBookRecoveryItem(
    val bookId: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int,
    val sourceLabel: String
)
```

恢复结果模型：

```kotlin
sealed interface DeletedBookRecoveryResult {
    data object RestoredReady : DeletedBookRecoveryResult
    data object RestoredPartial : DeletedBookRecoveryResult
    data object MissingBook : DeletedBookRecoveryResult
    data object MissingRoot : DeletedBookRecoveryResult
    data class RootUnavailable(val reason: String) : DeletedBookRecoveryResult
    data object AbsRemoteDeleted : DeletedBookRecoveryResult
    data object NoAudioFiles : DeletedBookRecoveryResult
    data class AllFilesUnavailable(val reason: String) : DeletedBookRecoveryResult
    data class PartialFilesUnavailable(
        val availableFileIds: List<String>,
        val missingFileIds: List<String>
    ) : DeletedBookRecoveryResult
}
```

展示字段名称可以映射到现有 application 模型命名，但上面的语义字段不得删除。result 必须保持可穷尽、可测试。

## 恢复规则

### 基础前置条件

恢复操作只接受当前仍是 `Book.status = DELETED` 的书。书不存在、已经恢复、已经变成其他状态时统一返回 `MissingBook`，不写数据库。

恢复只检查 `BookFile.fileRole = AUDIO` 的音频行。manifest、封面、字幕等辅助文件不参与恢复成败。

### Root 可用性

用例先读取 `LibraryRootEntity`。root 缺失时返回 `MissingRoot`。root 存在但 `AvailabilityChecker.checkRoot(root)` 不可用时返回 `RootUnavailable`。

### ABS 规则

ABS 本地软删除和 ABS 远端删除分开处理：

- mirror state 为 `ACTIVE`：说明远端书仍存在，本地只是软删除。允许恢复。
- mirror state 为 `REMOTE_DELETED`：说明服务器 catalog 已删除该条目。不允许手动恢复，返回 `AbsRemoteDeleted`。
- 远端重新出现同一 `remoteItemId` 后，由 ABS 同步流程把 mirror 改回 `ACTIVE` 并恢复本地可见性。

恢复 ABS 本地软删除时仍然只写本地状态，不拉取或推送远端进度。

### 文件可用性

Root 通过后检查所有 `AUDIO` 文件：

- 全部可用：写入 `Book.status = READY`，可用音频行写入或保持 `BookFile.status = READY`。
- 全部不可用：返回 `AllFilesUnavailable`，不写数据库。
- 部分可用：返回 `PartialFilesUnavailable`，等待用户确认。确认后写入 `Book.status = PARTIAL`，可用音频行写入 `READY`，不可用音频行写入 `MISSING`。取消不写数据库。

### 进度规则

恢复不修改 `BookProgressEntity`。本地已有进度继续保留。ABS 书籍恢复时不触发远端进度读取、覆盖或同步。

## 交互

设置页“媒体库管理”区域顺序：

1. 已注册媒体库根列表。
2. 添加媒体库。
3. 删除找回。

删除找回页：

- 顶部显示返回按钮和标题。
- 内容是纯列表。
- 空列表显示简单空状态。
- 每行展示封面、标题、作者/朗读者、进度和时长。
- 每行尾部是恢复按钮。
- 点击恢复后该行进入 loading，重复点击被忽略。

反馈：

- `RestoredReady`：toast “书籍已找回”。
- `RestoredPartial`：toast “书籍已部分找回”。
- `PartialFilesUnavailable`：确认 dialog，说明部分音频文件不可用，确认后以部分可用状态找回。
- 其他失败：失败原因 dialog，只有“确定”。

成功后，列表流重新发射，该书从删除找回列表消失。

## 错误文案

失败 dialog 需要覆盖以下原因：

- 书籍记录不存在或已恢复。
- 媒体库根目录不存在。
- 媒体库根目录不可用，并展示可读原因。
- ABS 远端已删除，需先在 ABS 服务端恢复或重新添加后同步。
- 没有可恢复的音频文件记录。
- 所有音频文件不可用。

部分可用确认 dialog：

标题：部分音频文件不可用

内容：这本书只有部分音频文件仍可读取。确认找回后，APlayer 会把它标记为部分可用，缺失音频仍无法播放。

按钮：取消 / 确认找回

## 测试

新增用例层单测，使用 fake DAO、fake availability checker、fake ABS mirror lookup，不访问真实 SAF、WebDAV 或 ABS 网络。

覆盖场景：

- `DELETED + root 可用 + 全部文件可用`：恢复为 `READY`，进度不变。
- root 缺失：返回失败，不写数据库。
- root 不可用：返回失败，不写数据库。
- 全部文件不可用：返回失败，不写数据库。
- 部分文件不可用：首次返回需要确认；确认后写 `Book.status = PARTIAL`，可用文件 `READY`，不可用文件 `MISSING`。
- ABS mirror 为 `ACTIVE`：允许本地恢复，不触发远端进度同步。
- ABS mirror 为 `REMOTE_DELETED`：返回失败，不写数据库。
- book 已不是 `DELETED`：返回 `MissingBook`，不发生状态误写。

手动回归：

- 设置页“添加媒体库”入口行为保持不变。
- “删除找回”入口只进入恢复页，不触发扫描或同步。
- 恢复成功后首页重新显示该书。
- 恢复成功后播放进度保持原值。
- 部分恢复后详情页和播放页沿用现有 `PARTIAL/MISSING` 处理。

## 分阶段交付

阶段 1：application 和 data 恢复用例

- 增加 deleted books 查询投影。
- 增加恢复用例和 result 类型。
- 接入 root、文件和 ABS mirror 检查。
- 增加单测。

阶段 2：settings 入口和恢复页

- 在“添加媒体库”下方增加“删除找回”入口。
- 新增恢复页、ViewModel、列表行和 dialog 状态。
- 成功 toast，失败 dialog，部分可用确认 dialog。

阶段 3：回归验证

- 编译验证。
- 运行恢复用例单测。
- 手动验证 SAF、ABS ACTIVE、ABS REMOTE_DELETED、全部不可用、部分不可用。

每个阶段都可以独立回归。阶段 1 通过单测证明业务规则，阶段 2 通过 UI 行为验证入口和反馈，阶段 3 做端到端确认。

## 验收标准

- 用户能从设置页进入“删除找回”。
- 已软删除书籍能在恢复页出现。
- 全部文件可用时恢复为 `READY`。
- 部分文件可用时必须经用户确认，确认后恢复为 `PARTIAL`。
- 全部文件不可用时不能恢复。
- ABS `REMOTE_DELETED` 不能被手动恢复。
- 成功恢复使用 toast 提示。
- 失败原因使用 dialog 展示。
- 恢复不改变本地播放进度。
- 恢复不触发 ABS 远端进度同步。
- 用例层单测覆盖核心分支。
