package com.viel.aplayer.logger

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import coil.request.ImageRequest
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一记录封面图片加载、缓存命中和处理结果。
 *
 * 这里不承载任何图片加载或业务判断，只把各页面、Coil 回调和 ABS 封面处理链路产生的事实
 * 收拢到 logger 目录，避免 UI 组件继续分散打印封面路径、缓存 key 和解码结果。
 */
object CoverImageCacheLogger {
    private const val TAG = "CoverImageCache"
    // 详尽的中文注释：封面请求上下文注册表只在调试期服务于“全局 EventListener 反查 scene/variant”，
    // 不参与任何业务语义，也不需要长期持有；因此这里使用轻量内存 map，并配合 TTL/上限定期清理，
    // 避免把已经完成的 ImageRequest 一直留在进程内。
    private val requestContexts = ConcurrentHashMap<Int, RegisteredRequestContext>()
    // 详尽的中文注释：fetcher 类型由全局 Coil EventListener 的 fetchStart 捕获，再由 request 级 listener
    // 在终态日志里读取，用来尽量区分 “disk cache 命中” 与 “本地文件重新 decode”。
    private val requestFetcherClasses = ConcurrentHashMap<Int, String>()
    // 详尽的中文注释：命中率统计只做轻量调试聚合，不落数据库，也不从 UI 线程同步读磁盘。
    // 用一个锁保护小型内存表即可满足一致性要求，无需额外引入更重的统计组件。
    private val statsLock = Any()
    private val sceneVariantStats = linkedMapOf<String, SceneVariantStats>()

    /**
     * 详尽的中文注释：统一约束封面请求 cache key 的命名空间判断。
     * 全局 Coil EventListener 只能依赖 request 自带的 key 信息识别“这是不是封面请求”，
     * 因此这里集中维护前缀规则，避免 Application、logger 与请求工厂各自硬编码字符串。
     */
    fun isCoverCacheKey(cacheKey: String?): Boolean =
        cacheKey?.startsWith("cover:") == true

    /**
     * 详尽的中文注释：从统一格式的 cache key 中提取 variant 分段。
     * 这样全局日志桥接即使拿不到具体 UI 场景名，也能稳定判断当前命中的是 small、medium、
     * backdrop 还是 main1200，便于后续按规格维度观察缓存命中与 Bitmap 开销。
     */
    fun variantFromCacheKey(cacheKey: String?): String =
        cacheKey
            ?.takeIf(::isCoverCacheKey)
            ?.split(':')
            ?.getOrNull(1)
            ?: "unknown"

    fun hashSource(source: String?): String {
        if (source.isNullOrBlank()) return "none"
        val normalized = source.trim().replace('\\', '/')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 详尽的中文注释：在请求工厂 build 出最终的 ImageRequest 后，把该请求所属的 scene/variant/source
     * 注册到 logger 内部表中。这样全局 ImageLoader 的 EventListener 在只拿到 request 对象时，
     * 仍能准确反查“这是首页小图、最近播放中图还是播放器主封面”，从而按文档要求输出 scene 级指标。
     */
    fun registerRequest(
        request: ImageRequest,
        scene: String,
        variant: String,
        source: String?,
        cacheKey: String
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        requestContexts[requestIdentity(request)] = RegisteredRequestContext(
            scene = scene,
            variant = variant,
            source = source,
            cacheKey = cacheKey,
            lastTouchedElapsedMs = nowElapsedMs
        )
        pruneExpiredRequestContexts(nowElapsedMs)
    }

    /**
     * 详尽的中文注释：记录本次请求最终实际走到的 fetcher 实现类。
     * 这类信息只有 EventListener.fetchStart 能拿到，因此先暂存在 logger 里，等 request listener 收到 success/error/cancel
     * 再取出来参与 decodeSource 归一化。
     */
    fun rememberFetcherClass(request: ImageRequest, fetcherClassName: String?) {
        if (fetcherClassName.isNullOrBlank()) return
        requestFetcherClasses[requestIdentity(request)] = fetcherClassName
    }

    /**
     * 详尽的中文注释：在请求结束时取回并顺手移除 fetcher 类名，避免这类一次性调试信息长期残留在内存表里。
     */
    fun takeFetcherClass(request: ImageRequest): String? =
        requestFetcherClasses.remove(requestIdentity(request))

    /**
     * 详尽的中文注释：取消或失败路径也需要清理 fetcher 暂存，防止请求中途终止后留下无效条目。
     */
    fun clearFetcherClass(request: ImageRequest) {
        requestFetcherClasses.remove(requestIdentity(request))
    }

    /**
     * 详尽的中文注释：全局 EventListener 用 request 对象反查先前在请求工厂里注册的上下文。
     * 如果这次请求对象因为复用或时序问题没有命中注册表，则回退到一个“unknown scene + cacheKey 推导 variant”
     * 的兜底上下文，保证日志链路不断。
     */
    fun resolveRequestContext(request: ImageRequest): RequestContext {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val requestId = requestIdentity(request)
        val registered = requestContexts[requestId]
        if (registered != null) {
            requestContexts[requestId] = registered.copy(lastTouchedElapsedMs = nowElapsedMs)
            return RequestContext(
                scene = registered.scene,
                variant = registered.variant,
                source = registered.source,
                cacheKey = registered.cacheKey
            )
        }
        val fallbackCacheKey = request.memoryCacheKey?.toString() ?: request.diskCacheKey
        return RequestContext(
            scene = "unknown",
            variant = variantFromCacheKey(fallbackCacheKey),
            source = request.data.toString(),
            cacheKey = fallbackCacheKey
        )
    }

    fun logRequest(
        scene: String,
        variant: String,
        source: String?,
        cacheKey: String,
        targetWidth: Int,
        targetHeight: Int
    ) {
        Log.d(
            TAG,
            "request: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=$cacheKey, size=${targetWidth}x$targetHeight"
        )
    }

    /**
     * 详尽的中文注释：记录来自全局 Coil EventListener 的成功事件。
     * 这一层日志专门补齐“统一缓存池最终是否命中、耗时多少、Bitmap 大致占多大”的 loader 事实，
     * 与各 UI 组件里的场景日志互补，便于判断跨页面是否真的复用了同一份缓存结果。
     */
    fun logPipelineSuccess(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeSource: String,
        decodeCostMs: Long,
        width: Int? = null,
        height: Int? = null,
        bitmapByteCount: Long? = null,
        metricsSnapshot: SceneVariantMetricsSnapshot
    ) {
        Log.d(
            TAG,
            "pipeline success: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeSource=$decodeSource, decodeCostMs=$decodeCostMs, drawable=${width ?: -1}x${height ?: -1}, bitmapByteCount=${bitmapByteCount ?: -1L}, cacheHitRatio=memory:${metricsSnapshot.memoryHitRatioPercent}%,disk:${metricsSnapshot.diskHitRatioPercent}%,file:${metricsSnapshot.fileDecodeRatioPercent}%,network:${metricsSnapshot.networkRatioPercent}%,other:${metricsSnapshot.otherRatioPercent}%, total=${metricsSnapshot.totalSuccesses}"
        )
    }

    /**
     * 详尽的中文注释：记录来自全局 Coil EventListener 的失败事件。
     * 这里仍然附带统一 cache key、variant 和耗时，方便排查“请求工厂 key 正常但底层 fetch/decode 失败”的问题，
     * 而不需要回到具体页面逐个复现实验。
     */
    fun logPipelineError(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeCostMs: Long,
        error: Throwable
    ) {
        Log.e(
            TAG,
            "pipeline error: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeCostMs=$decodeCostMs, error=${error::class.java.simpleName}: ${error.message}",
            error
        )
    }

    /**
     * 详尽的中文注释：记录来自全局 Coil EventListener 的取消事件。
     * 列表快速滚动时取消请求本身是正常现象，但只有把取消和命中、失败分开记录，
     * 才能避免把“用户滚动造成的取消”误判成“缓存没有命中”或“图片加载器异常”。
     */
    fun logPipelineCancel(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeCostMs: Long
    ) {
        Log.d(
            TAG,
            "pipeline cancel: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeCostMs=$decodeCostMs"
        )
    }

    /**
     * 详尽的中文注释：从 drawable 中尽量提取实际 Bitmap 占用字节数。
     * 对 BitmapDrawable 直接读取 allocationByteCount；若不是 BitmapDrawable，则回退为
     * “宽 x 高 x 4” 的近似估算，用于持续观察不同规格请求的大致内存体量。
     */
    fun bitmapByteCount(drawable: Drawable?): Long? {
        val bitmapDrawable = drawable as? BitmapDrawable
        if (bitmapDrawable != null) {
            return bitmapDrawable.bitmap.allocationByteCount.toLong()
        }
        val width = drawable?.intrinsicWidth ?: return null
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        return width.toLong() * height.toLong() * 4L
    }

    fun logAbsCoverStreamStart(rootId: String, remoteItemId: String, contentLength: Long?) {
        Log.d(
            TAG,
            "abs stream start: root=${hashSource(rootId)}, item=${hashSource(remoteItemId)}, contentLength=${contentLength ?: -1L}"
        )
    }

    fun logAbsCoverStreamReady(rootId: String, remoteItemId: String, fileSize: Long?) {
        Log.d(
            TAG,
            "abs stream ready: root=${hashSource(rootId)}, item=${hashSource(remoteItemId)}, fileSize=${fileSize ?: -1L}"
        )
    }

    /**
     * 详尽的中文注释：把底层 Coil DataSource 与 fetcher 类型归一化成文档里的观测口径。
     * 这里优先识别 memory cache；其次用 fetcher 是否为 DiskCache 相关实现区分真正的 disk hit；
     * 剩余的本地文件路径读取统一收口为 file decode，确保日志里至少能稳定区分 memory / disk / file 三种主路径。
     */
    fun normalizeDecodeSource(dataSource: String?, fetcherClassName: String?): String =
        when {
            dataSource.equals("MEMORY_CACHE", ignoreCase = true) || dataSource.equals("MEMORY", ignoreCase = true) -> "memory_hit"
            dataSource.equals("NETWORK", ignoreCase = true) -> "network"
            fetcherClassName?.contains("DiskCache", ignoreCase = true) == true -> "disk_hit"
            dataSource.equals("DISK", ignoreCase = true) -> "file_decode"
            else -> "other"
        }

    /**
     * 详尽的中文注释：按 `scene + variant` 聚合一次成功请求的来源分类计数，并回传当前快照。
     * 该快照只做调试日志展示，帮助直接从 logcat 判断当前场景是否主要命中 memory cache，
     * 还是仍然频繁回落到 disk/file decode。
     */
    fun recordSuccessMetric(
        scene: String,
        variant: String,
        decodeSource: String
    ): SceneVariantMetricsSnapshot = synchronized(statsLock) {
        val statsKey = "$scene|$variant"
        val stats = sceneVariantStats.getOrPut(statsKey) { SceneVariantStats() }
        stats.totalSuccesses += 1
        when (decodeSource) {
            "memory_hit" -> stats.memoryHits += 1
            "disk_hit" -> stats.diskHits += 1
            "file_decode" -> stats.fileDecodes += 1
            "network" -> stats.networkLoads += 1
            else -> stats.otherLoads += 1
        }
        SceneVariantMetricsSnapshot(
            totalSuccesses = stats.totalSuccesses,
            memoryHits = stats.memoryHits,
            diskHits = stats.diskHits,
            fileDecodes = stats.fileDecodes,
            networkLoads = stats.networkLoads,
            otherLoads = stats.otherLoads
        )
    }

    /**
     * 详尽的中文注释：ConcurrentHashMap 中的请求上下文不需要长期保存。
     * 当数量超过上限后，只清理长时间未被访问的旧请求，既避免无界增长，也不会干扰当前还在使用的 request 对象。
     */
    private fun pruneExpiredRequestContexts(nowElapsedMs: Long) {
        if (requestContexts.size <= MAX_REQUEST_CONTEXTS) return
        requestContexts.entries.removeIf { entry ->
            nowElapsedMs - entry.value.lastTouchedElapsedMs > REQUEST_CONTEXT_TTL_MS
        }
    }

    private fun requestIdentity(request: ImageRequest): Int = System.identityHashCode(request)

    /**
     * 详尽的中文注释：RequestContext 是全局 EventListener 真正需要消费的精简视图。
     * 它只保留 scene、variant、source 和 cacheKey 四项与观测直接相关的字段，不把 request 对象本身继续往外传。
     */
    data class RequestContext(
        val scene: String,
        val variant: String,
        val source: String?,
        val cacheKey: String?
    )

    /**
     * 详尽的中文注释：内部注册表额外保存最后一次触碰时间，用于做 TTL 清理。
     */
    private data class RegisteredRequestContext(
        val scene: String,
        val variant: String,
        val source: String?,
        val cacheKey: String,
        val lastTouchedElapsedMs: Long
    )

    /**
     * 详尽的中文注释：每个 `scene + variant` 聚合一份轻量命中统计。
     * 这里只统计成功请求，因为 cacheHitRatio 的目标是观察成功加载里有多少命中了 memory/disk/file 路径。
     */
    private data class SceneVariantStats(
        var totalSuccesses: Int = 0,
        var memoryHits: Int = 0,
        var diskHits: Int = 0,
        var fileDecodes: Int = 0,
        var networkLoads: Int = 0,
        var otherLoads: Int = 0
    )

    /**
     * 详尽的中文注释：输出到日志的统计快照。
     * 百分比使用一位小数，直接按总成功数折算，方便在 logcat 中肉眼对比不同场景的命中稳定性。
     */
    data class SceneVariantMetricsSnapshot(
        val totalSuccesses: Int,
        val memoryHits: Int,
        val diskHits: Int,
        val fileDecodes: Int,
        val networkLoads: Int,
        val otherLoads: Int
    ) {
        val memoryHitRatioPercent: String
            get() = ratioPercent(memoryHits)
        val diskHitRatioPercent: String
            get() = ratioPercent(diskHits)
        val fileDecodeRatioPercent: String
            get() = ratioPercent(fileDecodes)
        val networkRatioPercent: String
            get() = ratioPercent(networkLoads)
        val otherRatioPercent: String
            get() = ratioPercent(otherLoads)

        private fun ratioPercent(value: Int): String {
            if (totalSuccesses <= 0) return "0.0"
            return String.format("%.1f", (value.toDouble() * 100.0) / totalSuccesses.toDouble())
        }
    }

    private const val REQUEST_CONTEXT_TTL_MS = 10 * 60 * 1000L
    private const val MAX_REQUEST_CONTEXTS = 1024
}
