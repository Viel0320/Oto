package com.viel.aplayer.logger

import coil.EventListener
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult

/**
 * Coil Event Listener Bridge (Global Coil event listener bridge for cover image requests)
 *
 * This class translates ImageLoader success, error, cancellation, and start events into unified logs.
 * It does not participate in cache key generation, path selection, or UI scene choices. It complements cache-related
 * metrics (e.g. memory hit, disk hit, or file decode) without modifying existing request factories.
 */
class CoverImageCoilEventListener private constructor(
    private val requestContext: CoverImageCacheLogger.RequestContext
) : EventListener {
    // Fetcher Type Extraction (The fetcher type is a critical clue to differentiate between disk cache hits and raw file decodes)
    // We record the fetcher class name during fetchStart, which is later normalized along with the DataSource inside the success callback.

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        CoverImageCacheLogger.rememberFetcherClass(
            request = request,
            fetcherClassName = fetcher::class.java.simpleName
        )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        // Keep Fetcher Class Hint (Do not clear the fetcher class name in EventListener success callbacks)
        // The final success metrics are emitted by the ImageRequest.Listener using takeFetcherClass() to distinguish disk hits from file decodes.
        // Cleaning it up here prematurely would prevent the request listener from identifying the decode source.
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    override fun onCancel(request: ImageRequest) {
        CoverImageCacheLogger.clearFetcherClass(request)
    }

    /**
     * Listener Factory (Unified global factory entry point)
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
