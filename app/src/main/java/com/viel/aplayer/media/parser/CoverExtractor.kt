package com.viel.aplayer.media.parser

import android.content.Context
import java.io.InputStream

/**
 * 封面处理兼容外壳。
 *
 * 真正的封面图片落地、缩略图生成和主色提取实现
 * 已经全部迁移到 `ImageProcessor`。
 *
 * 这里保留原类名和 `CoverResult` 类型，只做转发，避免波及大量调用点。
 */
class CoverExtractor(private val context: Context) {
    data class CoverResult(
        val originalPath: String?,
        val thumbnailPath: String?,
        val backgroundColor: Int? = null,
    )

    /**
     * 保存用户手动设置的自定义封面。
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String): CoverResult =
        ImageProcessor.saveCustomCover(context, bookId, tempCoverPath)

    /**
     * 保存 parser 提供的内嵌封面字节。
     */
    suspend fun saveEmbeddedImage(sourceId: String, artBytes: ByteArray): CoverResult =
        ImageProcessor.saveEmbeddedImage(context, sourceId, artBytes)

    /**
     * 处理外部 sidecar 图片流。
     */
    suspend fun processExternalImage(sourceId: String, openStream: suspend () -> InputStream?): CoverResult =
        ImageProcessor.processExternalImage(context, sourceId, openStream)
}
