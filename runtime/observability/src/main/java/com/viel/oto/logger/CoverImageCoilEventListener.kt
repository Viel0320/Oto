package com.viel.oto.logger

import coil.EventListener
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult

/**
 * Global Coil event listener bridge for cover image requests.
 *
 * This class translates ImageLoader success, error, cancellation, and start events into unified logs.
 * It does not participate in cache key generation, path selection, or UI scene choices. It complements cache-related
 * metrics (e.g. memory hit, disk hit, or file decode) without modifying existing request factories.
 */
class CoverImageCoilEventListener private constructor(
    private val requestContext: CoverImageCacheLogger.RequestContext
) : EventListener {

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        CoverImageCacheLogger.rememberFetcherClass(
            request = request,
            fetcherClassName = fetcher::class.java.simpleName
        )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    override fun onCancel(request: ImageRequest) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    /**
     * Unified global factory entry point.
     * ImageLoader only needs to bind to this Factory to automatically attach listener logs to all cover requests,
     * without affecting non-cover image requests as they are filtered out by the "cover:" cache key check.
     */
    class Factory : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener {
            val requestContext = CoverImageCacheLogger.resolveRequestContext(request)
            return CoverImageCoilEventListener(requestContext = requestContext)
        }
    }
}
