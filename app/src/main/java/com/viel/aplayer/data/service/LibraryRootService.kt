package com.viel.aplayer.data.service

import android.net.Uri
import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.LibraryRootGateway
import kotlinx.coroutines.flow.Flow

/**
 * 书库根目录管理与维护应用服务（实现了 LibraryRootGateway 网关）。
 *
 * 核心设计目标：
 * 1. 增量重构过渡层：在迁移阶段，底层实际仍旧委托给已有的上帝仓库 [BookLibraryRepository]。
 * 2. 方便后续直连 DAO/Store：在未来 M6 阶段，可以直接在该类中去掉对 [BookLibraryRepository] 的引用，改为直接注入 DAO 实体与 CredentialsStore 凭证管理器。
 */
@Suppress("DEPRECATION")
class LibraryRootService(
    private val bookLibraryRepository: BookLibraryRepository
) : LibraryRootGateway {

    override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return bookLibraryRepository.observeLibraryRoots()
    }

    override fun getCachedLibraryRoots(): List<LibraryRootEntity> {
        return bookLibraryRepository.getCachedLibraryRoots()
    }

    override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity {
        return bookLibraryRepository.setLibraryRoot(uri)
    }

    override suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity {
        // 详尽的中文注释：委托底层仓库在后台存储并持久化 WebDAV 远程网络书库的访问参数
        return bookLibraryRepository.addWebDavLibraryRoot(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String) {
        // 详尽的中文注释：委托底层仓库注册 SAF 授权本地书库目录并异步调度文件同步
        bookLibraryRepository.addLibraryRootAndScheduleSync(uri, trigger)
    }

    override fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String
    ) {
        // 详尽的中文注释：委托底层仓库注册 WebDAV 远程网络书库并立刻发起文件增量同步
        bookLibraryRepository.addWebDavLibraryRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath,
            trigger = trigger
        )
    }

    override suspend fun refreshLibraryRootStatuses() {
        // 详尽的中文注释：委托底层仓库异步校验当前全部已添加的 SAF 目录授权可达状态
        bookLibraryRepository.refreshLibraryRootStatuses()
    }
}
