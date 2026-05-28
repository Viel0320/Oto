package com.viel.aplayer.data.service

import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.gateway.CoverGateway

/**
 * 封面、元数据物理提取与主色调更新应用服务（实现了 CoverGateway 网关）。
 *
 * 核心设计目标：
 * 1. 增量重构过渡层：在迁移阶段，底层实际仍旧委托给已有的上帝仓库 [BookLibraryRepository]。
 * 2. 方便后续直连 DAO/Resolver：在未来 M6 阶段，可以直接在该类中去掉对 [BookLibraryRepository] 的引用，改为直接注入 DAO 实体与 CoverExtractor。
 */
@Suppress("DEPRECATION")
class CoverService(
    private val bookLibraryRepository: BookLibraryRepository,
    private val physicalFileResolver: com.viel.aplayer.data.PhysicalFileResolver
) : CoverGateway {

    override suspend fun saveCustomCover(bookId: String, tempCoverPath: String) {
        // 详尽的中文注释：委托旧仓库在后台保存并覆盖用户的自定义封面物理文件
        bookLibraryRepository.saveCustomCover(bookId, tempCoverPath)
    }

    override suspend fun forceRegenerateCoverAndMetadata(bookId: String) {
        // 详尽的中文注释：委托旧仓库重新发起对物理音频元数据与封面的深度重扫与提取重建
        bookLibraryRepository.forceRegenerateCoverAndMetadata(bookId)
    }

    override fun updateBackgroundColor(id: String, color: Int) {
        // 详尽的中文注释：委托旧仓库写回计算后的主题背景色 ARGB 缓存值
        bookLibraryRepository.updateBackgroundColor(id, color)
    }

    override suspend fun checkDetailAvailability(bookId: String): Boolean {
        // 详尽的中文注释：通过注入的物理文件解析器校验该书籍详情页整体的可达状态
        return physicalFileResolver.checkDetailAvailability(bookId)
    }

    override suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean {
        // 详尽的中文注释：先从底层持久化仓库获取书籍对应的物理文件分轨列表
        val primaryFile = bookLibraryRepository.getFilesForBookSync(bookId).firstOrNull() ?: return false
        // 详尽的中文注释：再通过物理文件解析器在物理文件系统或 VFS 级别上验证主音频文件是否存在
        return physicalFileResolver.checkPrimaryAudioFileExists(primaryFile)
    }
}
