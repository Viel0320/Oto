package com.viel.aplayer.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书库根目录管理与维护应用服务（实现了 LibraryRootGateway 网关）。
 *
 * 核心设计目标：
 * 1. 彻底解耦消灭上帝类：在 M6d 阶段，去掉对 BookLibraryRepository 的所有直接引用，直接通过注入 Context, LibraryRootDao, BookDao 与 ScanScheduler 支撑。
 * 2. 完美平移热缓存与清理：精心保留 SAF 持久化授权 takePersistableUriPermission、CachedRoots 内存热缓存更新以及级联删除时的物理封面图清理机制。
 */
class LibraryRootService(
    context: Context,
    private val libraryRootDao: LibraryRootDao,
    private val bookDao: BookDao,
    private val scanScheduler: ScanScheduler
) : LibraryRootGateway {

    // 详尽的中文注释：采用全局 applicationContext 阻断潜在的生命周期泄露
    private val appContext = context.applicationContext

    // 详尽的中文注释：物理目录与 WebDAV 凭据底层管理组件
    private val rootStore = LibraryRootStore(appContext)
    private val webDavCredentialStore = WebDavCredentialStore(appContext)

    // 详尽的中文注释：后台热缓存更新专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("LibraryRootService", "协程在 LibraryRootService 运行中捕获到未处理异常", exception)
    }

    // 详尽的中文注释：服务专属异步写与缓存操作协程上下文
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 详尽的中文注释：零延迟冷启动极速渲染缓存，消解 Room 异步流读取导致的首帧闪烁
    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
        // 详尽的中文注释：在服务初始化时即刻订阅书库根的变动 Flow，热同步内存缓存
        scope.launch {
            observeLibraryRoots().collect {
                cachedRoots = it
            }
        }
    }

    override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return libraryRootDao.getAllRoots()
    }

    override fun getCachedLibraryRoots(): List<LibraryRootEntity> {
        return cachedRoots
    }

    override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.addRoot(uri, "My Library")
    }

    override suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.addWebDavRoot(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String) {
        scope.launch {
            runCatching {
                // 详尽的中文注释：向系统内容解析器确保持久化申请 SAF 本地目录树级别读取授权，防止关机重启后吊销
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                setLibraryRoot(uri)
                // 详尽的中文注释：通过注入的扫描网关，非阻塞触发本次新入库物理路径的文件深度同步
                scanScheduler.syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("LibraryRootService", "添加本地 SAF 根目录并调度扫描时发生异常", error)
            }
        }
    }

    override fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String
    ) {
        scope.launch {
            runCatching {
                addWebDavLibraryRoot(url, username, password, displayName, basePath)
                // 详尽的中文注释：通过注入的扫描网关，非阻塞触发本次新入库网络路径的文件深度同步
                scanScheduler.syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("LibraryRootService", "添加 WebDAV 远端根目录并调度扫描时发生异常", error)
            }
        }
    }

    override suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        rootStore.refreshPermissionStatuses()
    }

    override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit = withContext(Dispatchers.IO) {
        // 1. 在数据库级联删除触发前，递归清除该根旗下所有书籍关联的物理封面与缩略图文件，规避存储泄露
        try {
            val books = bookDao.getBooksByRootId(root.id)
            books.forEach { book ->
                book.coverPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        com.viel.aplayer.logger.LibraryLogger.logCoverDeleted(book.id, path, deleted)
                    }
                }
                book.thumbnailPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        com.viel.aplayer.logger.LibraryLogger.logThumbnailDeleted(book.id, path, deleted)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LibraryRootService", "注销根目录清理封面物理缓存时发生异常", e)
        }

        // 2. 根据数据源类型，撤销系统级 SAF 持久访问权限或安全抹除网络 WebDAV 凭据
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> {
                try {
                    val uri = root.sourceUri.toUri()
                    appContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.e("LibraryRootService", "注销 SAF 目录的持久权限失败", e)
                }
            }
            LibrarySourceKind.WEBDAV -> {
                webDavCredentialStore.delete(root.credentialId)
            }
        }

        // 3. 在 Room 库执行根注销（外键配置了 ON DELETE CASCADE 级联删除，书籍与音轨子记录会在同个事务里被全量自动擦除）
        libraryRootDao.deleteRoot(root)
    }
}
