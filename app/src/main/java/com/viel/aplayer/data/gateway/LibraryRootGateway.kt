package com.viel.aplayer.data.gateway

import android.net.Uri
import com.viel.aplayer.data.entity.LibraryRootEntity
import kotlinx.coroutines.flow.Flow

/**
 * 领域解耦的网关接口：专注于有声书库根目录（包括本地 SAF 根目录以及 WebDAV 根目录）的维护与管理。
 *
 * 核心设计目标：
 * 1. 消除上帝类依赖：为上游设置页、扫描器等暴露专门的只读与只写书库根逻辑。
 * 2. 促进依赖倒置：隔离文件目录授权/网络 DAV 实体在领域外的表现形式。
 */
interface LibraryRootGateway {

    /**
     * 响应式观察当前全部注册的书库根目录列表。
     */
    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>>

    /**
     * 同步获取当前已缓存的全部书库根目录列表快照。
     */
    fun getCachedLibraryRoots(): List<LibraryRootEntity>

    /**
     * 注册并持久化一个本地存储（SAF 授权）的书库根目录。
     *
     * @param uri 本地 SAF 返回的持久化授权目录 Uri
     * @return 新创建的书库根目录实体记录
     */
    suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity

    /**
     * 注册并持久化一个 WebDAV 远程网络书库根目录。
     *
     * @param url WebDAV 的网络服务器物理终点 URL
     * @param username 用户名
     * @param password 密码
     * @param displayName 显示名称
     * @param basePath 挂载的基础目录路径
     * @return 新创建的 WebDAV 书库根目录实体记录
     */
    suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity

    /**
     * 注册并持久化一个 ABS 远端书库根。
     *
     * @param credentialId ABS 凭据存储中的稳定引用 ID
     * @param libraryId Audiobookshelf book library 的远端 ID
     * @param displayName 设置页展示名称，通常使用 library 名称
     * @return 新创建或更新后的 ABS 书库根实体记录
     */
    suspend fun addAbsLibraryRoot(
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity

    /**
     * 注册本地 SAF 授权的书库根目录并即刻发起物理文件增量同步。
     */
    fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String = "USER")

    /**
     * 注册 WebDAV 远程网络书库根目录并即刻发起文件增量同步。
     *
     * @param url WebDAV 的网络服务器物理终点 URL
     * @param username 用户名
     * @param password 密码
     * @param displayName 显示名称
     * @param basePath 挂载的基础目录路径
     * @param trigger 同步触发源，默认为 USER
     */
    fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String = "USER"
    )

    /**
     * 异步刷新并校验所有书库根目录在底层的真实授权/连接状态。
     */
    suspend fun refreshLibraryRootStatuses()

    /**
     * 仅清理与书库根相关的底层数据（物理封面图清理、SAF授权释放、WebDAV凭证物理删除、Room级联记录删除）。
     * 此操作为纯数据层职责，不参与任何播放状态的控制，解耦反向依赖。
     */
    suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity)
}
