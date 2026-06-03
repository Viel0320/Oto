package com.viel.aplayer.logger

import android.os.SystemClock
import coil.EventListener
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult

/**
 * 全局封面请求的 Coil 事件桥接器。
 *
 * 这个类只负责把 ImageLoader 层面的开始、成功、失败、取消事实转成统一日志，
 * 不参与缓存 key 生成、路径选择或 UI 场景判断。这样可以在不打破现有请求工厂职责边界的前提下，
 * 补齐“同一个全局缓存池里，封面请求最终是 memory hit、disk hit 还是重新 decode”的观测信息。
 */
class CoverImageCoilEventListener private constructor(
    private val requestContext: CoverImageCacheLogger.RequestContext
) : EventListener {
    // 详尽的中文注释：fetcher 类型是区分“disk cache 命中”与“本地文件重新 decode”的关键线索之一。
    // 因此在 fetchStart 时把实现类名记录下来，稍后在成功回调里和 DataSource 一起归一化为文档里的 decodeSource 口径。

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        CoverImageCacheLogger.rememberFetcherClass(
            request = request,
            fetcherClassName = fetcher::class.java.simpleName
        )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        // 详尽的中文注释：成功路径不在 EventListener 里清理 fetcher 线索。
        // 终态指标由 ImageRequest.Listener 输出，它会通过 takeFetcherClass() 读取并移除这条线索；
        // 如果这里提前删除，request listener 就无法再尽量区分 disk hit 与 file decode。
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    override fun onCancel(request: ImageRequest) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    /**
     * 详尽的中文注释：统一的全局工厂入口。
     * ImageLoader 只需要绑定这一个 Factory，就能让所有封面请求自动拥有同一套 loader 层日志桥接，
     * 同时不会影响非封面资源请求，因为真正写日志前还会再按 `cover:` key 前缀做一次过滤。
     */
    class Factory : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener {
            val requestContext = CoverImageCacheLogger.resolveRequestContext(request)
            return CoverImageCoilEventListener(requestContext = requestContext)
        }
    }
}
