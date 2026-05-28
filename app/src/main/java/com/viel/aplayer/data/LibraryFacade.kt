package com.viel.aplayer.data

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.flow.Flow

/**
 * 新的媒体库高层业务门面（LibraryFacade）。
 *
 * 核心设计目标：
 * 1. 精细化高层网关：通过持有分域网关接口（BookQueryGateway、ProgressGateway、ScanScheduler），
 *    代替原先具有一千多行、承载各种不相干具体业务逻辑的 [LibraryRepository]。
 * 2. 支持平滑过渡：供上游高频调用的 ViewModel 及后台服务迁移，并且内部没有任何具体的数据库或文件IO逻辑，全部代理给各分域网关。
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler
) : BookQueryGateway by bookQueryGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler {

    // 凭借 Kotlin 强大的类委托特性（Interface Delegation `by`），
    // LibraryFacade 会在编译器自动生成对应接口的所有代理方法。
    // 这极大地减少了冗余的样板委托代码（Boilerplate Code），并完美保证了方法的百分之百一致性。
}
