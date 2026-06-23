# APlayer 项目长期记忆

## DI 策略
- 项目使用 Koin 4.2.2 进行依赖注入（2026-06-23 从手动 DI 迁移）
- Koin module 位于 `di/koin/`，按功能分拆为 ~19 个小模块
- `APlayerKoinApplication` 启动全局 Koin 上下文
- `GraphClosePolicy` 保留有序关闭：media → download → abs → library → uiEvents
- 窄依赖视图接口位于 `di/dependencies/`，通过 `DependencyViewModule` 绑定到 Koin
- ViewModel 用 `org.koin.core.module.dsl.viewModel` 注册，UI 用 `koinViewModel()` 获取
- `DataStore<Preferences>` 用 `named` qualifier 区分（appSettings/searchHistory/absCredentials）

## 架构约束
- UI 层不能 import `com.viel.aplayer.data.` 包
- `DuplicateLibraryRootException.sourceType` 用 application 层的 `DuplicateLibraryRootSource` 枚举（非 data 层的 `AudiobookSchema.LibrarySourceType`）
- 6 个 singleton 类的 `getInstance` 已删除，改为 `internal constructor`，由 Koin `single` 管理

## 测试
- Robolectric 测试中 `APlayerApplication.onCreate` 会启动完整 Koin 上下文
- 测试中用 `loadKoinModules` 覆盖 fake 依赖，不要 `stopKoin` + `startKoin`
- startup warmup 协程已加 `runCatching` 防止 Koin scope 关闭后抛未捕获异常
