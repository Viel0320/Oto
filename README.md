# Oto

Oto 是一款基于 Jetpack Compose 与 Media3 的 Android 有声书播放器，支持本地导入与远程书源，专注于稳定的播放体验与进度管理。

## 功能特性

- **本地书库**：通过 SAF 导入本地有声书，支持 CUE、M3U8、多文件合集与单文件音频等多种书籍结构。
- **远程书源**：内置 AudiobookShelf 与 WebDAV 远程书源基础设施，含目录同步、进度同步与播放会话同步。
- **播放体验**：基于 Media3 的播放，支持进度持久化、自动回退、书签、字幕、缓存与前台通知。
- **现代 UI**：Jetpack Compose + Material 3 界面，使用 Navigation 3 与 MaterialKolor 种子色驱动的配色方案，支持硬件级模糊效果。
- **桌面小组件**：基于 Glance 的播放小组件。
- **多语言**：内置 en、zh-rCN、zh-rHK、zh-rTW、ja、fr、de、ru、es、pt 等语言资源。

## 技术栈

- Kotlin 2.4，Java 21 工具链
- Jetpack Compose（Material 3）、Navigation 3
- Media3 播放、Glance 小组件
- Room、DataStore、WorkManager
- OkHttp、Moshi、Coil
- Koin 依赖注入、KSP 注解处理

## 项目结构

Oto 采用分层多模块架构，业务边界由架构测试守护：

| 模块 | 职责 |
| --- | --- |
| `:app` | Android 应用壳与组合根（`MainActivity`、`OtoApplication`、Koin 启动） |
| `:runtime:lifecycle` | 生命周期策略 |
| `:runtime:observability` | Android 后端日志与诊断 |
| `:data:store` | Room、DataStore、gateway 契约与持久化服务 |
| `:library:vfs` | 书源 provider、VFS 文件访问与远程范围缓存 |
| `:library:import` | 扫描、导入与书库根生命周期 |
| `:media:metadata` | 音频元数据、清单、封面与字幕解析 |
| `:media:playback` | 播放计划、控制器运行时、VFS 播放数据源与恢复策略 |
| `:media:service` | Media3 服务、前台通知与音频焦点运行时 |
| `:abs` | AudiobookShelf 防腐层、同步与书源适配 |
| `:work:policy` | 可复用的 WorkManager 队列策略 |
| `:application` | 用例、读模型、命令与下载编排 |
| `:event` | 应用级反馈与事件投递契约 |
| `:widget` | Glance 小组件渲染、状态与接收器 |
| `:shared` | 跨层共享模型、纯策略与用户可见资源目录 |
| `:ui` | Compose 路由、屏幕、覆盖层、ViewModel、主题与本地化 UI |

分层方向：UI → Application → Data/Library/Media/ABS → Android 或网络基础设施。

共享的 Android 库配置（compile SDK、min SDK、Java 与 Kotlin JVM target）集中在 `build-logic/` 的 `oto.android.library` 约定插件中。

## 构建要求

- JDK 21
- Android SDK：compileSdk 37、targetSdk 36、minSdk 33
- Gradle Wrapper（已固定 Gradle 9.6.1，无需单独安装）

## 快速开始

克隆仓库后，使用 Gradle Wrapper 构建：

```bash
# 编译 Debug 变体
./gradlew compileDebugKotlin

# 运行单元测试
./gradlew testDebugUnitTest

# 打包 Debug APK
./gradlew assembleDebug
```

Windows 下使用 `gradlew.bat`：

```powershell
.\gradlew.bat assembleDebug
```

### 签名配置（可选）

Release 构建会读取根目录的 `keystore.properties`，或对应的环境变量：

```properties
KEYSTORE_FILE=your.keystore
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

未配置时将回退到默认的 debug 签名。

## 致谢

Oto 站在以下优秀开源项目之上：

- [AndroidX Media3](https://github.com/androidx/media) — 播放引擎
- [Jetpack Compose](https://developer.android.com/jetpack/compose) 与 [Material 3](https://m3.material.io/) — UI 框架与设计系统
- [Room](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore](https://developer.android.com/topic/libraries/architecture/datastore)、[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) — 持久化与后台任务
- [OkHttp](https://github.com/square/okhttp) 与 [Moshi](https://github.com/square/moshi) — 网络与 JSON 解析
- [Coil](https://github.com/coil-kt/coil) — 图片加载
- [Koin](https://github.com/InsertKoinIO/koin) — 依赖注入
- [Glance](https://developer.android.com/jetpack/androidx/releases/glance) — 桌面小组件
- [MaterialKolor](https://github.com/jordond/MaterialKolor) — 种子色驱动的动态配色
- [Haze](https://github.com/chrisbanes/haze) — Compose 背景模糊
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — 开源许可证元数据

完整依赖与许可证清单可在应用内「关于」页面查看。

## 许可证

本项目基于 [Apache License 2.0](LICENSE.txt) 发布。
