package com.viel.aplayer.data

import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway

/**
 * 新的媒体库高层业务门面（LibraryFacade）。
 *
 * 核心设计目标：
 * 1. 精细化高层网关：通过持有分域网关接口，代替原先具有一千多行、承载各种不相干具体业务逻辑的旧上帝类。
 * 2. 极致的类委托：利用 Kotlin 委托机制（by），将所有分域 Gateway 接口的方法自动路由给对应的实现服务。
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val coverGateway: CoverGateway,
    private val searchHistoryGateway: SearchHistoryGateway
) : BookQueryGateway by bookQueryGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler,
    LibraryRootGateway by libraryRootGateway,
    CoverGateway by coverGateway,
    SearchHistoryGateway by searchHistoryGateway
