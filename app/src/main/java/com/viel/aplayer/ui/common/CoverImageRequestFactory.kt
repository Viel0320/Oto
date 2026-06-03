package com.viel.aplayer.ui.common

import android.content.Context
import android.os.SystemClock
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.viel.aplayer.logger.CoverImageCacheLogger
import java.io.File

/**
 * 封面展示规格。
 *
 * 每个规格都有明确的缓存 key 分段和目标解码尺寸，防止同一封面在不同页面中因为手写 key
 * 或临近尺寸差异产生重复 Bitmap，也避免小图场景误持有主封面大图。
 */
enum class CoverImageVariant(
    val keySegment: String,
    val targetWidth: Int,
    val targetHeight: Int
) {
    ThumbnailSmall("small180", 180, 180),
    ThumbnailMedium("medium360", 360, 360),
    Backdrop("backdrop128", 128, 128),
    Main1200("main1200", 1200, 1200)
}

/**
 * 统一生成 Coil 封面请求。
 *
 * 该对象只负责请求规格、缓存 key 和基础日志，不持有 UI 状态，也不访问数据库，避免把图片加载
 * 收口工具膨胀成跨层级的集中管理器。
 */
object CoverImageRequestFactory {
    fun build(
        context: Context,
        sourcePath: String,
        lastUpdated: Long,
        variant: CoverImageVariant,
        scene: String,
        allowHardware: Boolean = true
    ): ImageRequest {
        val cacheKey = cacheKey(sourcePath, lastUpdated, variant)
        CoverImageCacheLogger.logRequest(
            scene = scene,
            variant = variant.keySegment,
            source = sourcePath,
            cacheKey = cacheKey,
            targetWidth = variant.targetWidth,
            targetHeight = variant.targetHeight
        )
        // 详尽的中文注释：这里把“文档要求的指标日志”直接绑到 request 级 listener 上，
        // 避免指标是否出现还要额外依赖 Compose 默认是否命中了某个全局 singleton ImageLoader。
        // 这样只要这份 ImageRequest 被真实执行，就一定会输出终态指标；全局 EventListener 则退回到
        // 提供 fetcher 线索的辅助角色，用来尽量区分 disk cache 命中与本地文件重新 decode。
        val pipelineListener = object : ImageRequest.Listener {
            private var startedAtElapsedMs = SystemClock.elapsedRealtime()

            override fun onStart(request: ImageRequest) {
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            }

            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                val decodeSource = CoverImageCacheLogger.normalizeDecodeSource(
                    dataSource = result.dataSource.name,
                    fetcherClassName = CoverImageCacheLogger.takeFetcherClass(request)
                )
                val metricsSnapshot = CoverImageCacheLogger.recordSuccessMetric(
                    scene = scene,
                    variant = variant.keySegment,
                    decodeSource = decodeSource
                )
                CoverImageCacheLogger.logPipelineSuccess(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeSource = decodeSource,
                    decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs,
                    width = result.drawable.intrinsicWidth,
                    height = result.drawable.intrinsicHeight,
                    bitmapByteCount = CoverImageCacheLogger.bitmapByteCount(result.drawable),
                    metricsSnapshot = metricsSnapshot
                )
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                CoverImageCacheLogger.clearFetcherClass(request)
                CoverImageCacheLogger.logPipelineError(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs,
                    error = result.throwable
                )
            }

            override fun onCancel(request: ImageRequest) {
                CoverImageCacheLogger.clearFetcherClass(request)
                CoverImageCacheLogger.logPipelineCancel(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
                )
            }
        }
        val request = ImageRequest.Builder(context)
            .data(File(sourcePath))
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .allowHardware(allowHardware)
            // 详尽注释：主封面 1200px 位图单张约 5.8MB native heap，Backdrop 又会立刻进入模糊链路。
            // 这两类图不做 crossfade，避免切换期间额外同时持有旧图和新图；小图保留过渡动画即可。
            .crossfade(variant != CoverImageVariant.Main1200 && variant != CoverImageVariant.Backdrop)
            .size(variant.targetWidth, variant.targetHeight)
            .listener(pipelineListener)
            .build()
        // 详尽的中文注释：把 scene / variant / source / cacheKey 这组指标上下文绑定到最终生成的 request 对象。
        // 这样全局 ImageLoader 的 EventListener 在请求真正落到 loader 层后，仍然能反查出它属于哪个 UI 场景，
        // 从而输出文档要求的 `scene + variant + sourceKeyHash + decodeCostMs + decodeSource + cacheHitRatio` 指标。
        CoverImageCacheLogger.registerRequest(
            request = request,
            scene = scene,
            variant = variant.keySegment,
            source = sourcePath,
            cacheKey = cacheKey
        )
        return request
    }

    fun cacheKey(sourcePath: String, lastUpdated: Long, variant: CoverImageVariant): String =
        "cover:${variant.keySegment}:${CoverImageCacheLogger.hashSource(sourcePath)}:$lastUpdated"
}

/**
 * 封面路径选择规则。
 *
 * 展示层只声明自己是小图、主封面或背景场景，具体优先使用缩略图还是原图在这里集中表达，
 * 既能提升缓存命中，也能避免列表类组件反复从原图解码。
 */
object CoverImageSourceSelector {
    fun small(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath

    fun medium(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath

    fun main(coverPath: String?, thumbnailPath: String?): String? = coverPath ?: thumbnailPath

    fun backdrop(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath
}
