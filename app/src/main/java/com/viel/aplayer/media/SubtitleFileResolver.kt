package com.viel.aplayer.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.parse.SubtitleParser
import com.viel.aplayer.ui.player.components.SubtitleLine

/**
 * 详尽的中文注释：专门负责外部挂载字幕（如 .srt, .ass, .ssa, .vtt, .lrc 等）的遍历、同名定位与解析的辅助组件。
 * 本组件从原 LibraryRepository 彻底解耦出来，使得字幕搜索的复杂 I/O 操作与有声书的核心数据流存取进行物理隔离。
 */
@UnstableApi
class SubtitleFileResolver(
    private val context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao
) {
    // 详尽的中文注释：字幕文件的受支持后缀后缀集合。
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "lrc")

    /**
     * 详尽的中文注释：加载并解析指定媒体 URI 所对应的字幕文件。
     * 优先通过 URI 查找数据库中的物理音频记录以获得其相对路径进行高级 SAF 同目录查找；若无记录则退化到单文件父级定位。
     */
    suspend fun loadSubtitlesForUri(mediaUri: Uri): List<SubtitleLine> {
        val scannedFile = bookDao.getBookFileByUri(mediaUri.toString())
        val attachment = scannedFile?.let { loadSubtitleAttachment(it) } ?: loadSubtitleAttachment(mediaUri)
        return attachment?.lines ?: emptyList()
    }

    /**
     * 详尽的中文注释：根据已导入的书籍文件实体，查找其所在同级目录下的同名/同 base 名的字幕文件并解析。
     * 优先使用 SAF 授权的 rootId 和 relativePath 进行两级定位以确保 SAF 权限有效性。
     */
    private suspend fun loadSubtitleAttachment(file: BookFileEntity): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(file) ?: return null
        return parseSubtitle(subtitle.uri, subtitle.extension, subtitle.displayName)
    }

    /**
     * 详尽的中文注释：在媒体 URI 缺少数据库关联时的兜底字幕加载。直接从其 parentFile 中遍历查找。
     */
    private fun loadSubtitleAttachment(mediaUri: Uri): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(mediaUri) ?: return null
        return parseSubtitle(subtitle.uri, subtitle.extension, subtitle.displayName)
    }

    /**
     * 详尽的中文注释：核心字幕解析逻辑。兼容处理 SAF 内容协议 ("content") 以及传统的本地绝对路径 ("file")，
     * 使用 SubtitleParser 流式解析字幕字节。
     */
    private fun parseSubtitle(uri: Uri, extension: String, displayName: String): PlaybackSubtitle? =
        try {
            val lines = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)?.use {
                    SubtitleParser.parse(it, extension)
                }.orEmpty()
            } else {
                val file = File(uri.path ?: uri.toString())
                if (file.exists()) {
                    file.inputStream().use { SubtitleParser.parse(it, extension) }
                } else {
                    emptyList()
                }
            }
            PlaybackSubtitle(
                uri = uri,
                mimeType = subtitleMimeType(extension),
                label = displayName.substringBeforeLast('.'),
                lines = lines
            )
        } catch (e: Exception) {
            Log.e("SubtitleFileResolver", "解析字幕文件失败: $uri", e)
            null
        }

    /**
     * 详尽的中文注释：基于书籍物理文件定位外部字幕文件的物理查找算法。
     * 首先利用 SAF Tree 的相对定位定位到文件所在同级目录，再对该目录下文件做 BaseName 相似度匹配。
     */
    private suspend fun findSubtitleFile(file: BookFileEntity): SubtitleFileRef? {
        val root = libraryRootDao.getRootById(file.rootId)
        val rootDoc = root?.treeUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
        val parentPath = file.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val audioName = file.relativePath.substringAfterLast('/').ifBlank { file.displayName }

        // SAF 单文件 URI 找父目录不稳定，优先用导入时保存的 rootId/relativePath 回到同目录。
        val rootBased = rootDoc
            ?.findRelativeDirectory(parentPath)
            ?.findSameBaseSubtitle(audioName)
        if (rootBased != null) return rootBased

        return findSubtitleFile(file.uri.toUri())
    }

    /**
     * 详尽的中文注释：基于单媒体文件 URI 的外部字幕查找算法。
     * 兼容本地文件路径（物理 file 检索）和 SAF 外部目录。
     */
    private fun findSubtitleFile(mediaUri: Uri, providedParent: DocumentFile? = null, providedName: String? = null): SubtitleFileRef? {
        return try {
            when (mediaUri.scheme) {
                "content" -> {
                    // 如果在同步/导入中，可直接传入父级 DocumentFile 避免二次查询
                    val parentDir = providedParent ?: run {
                        val mediaFile = DocumentFile.fromSingleUri(context, mediaUri)
                        mediaFile?.parentFile
                    } ?: return null

                    val mediaName = (providedName ?: DocumentFile.fromSingleUri(context, mediaUri)?.name)
                        ?.substringBeforeLast(".") ?: return null

                    Log.d("SubtitleFileResolver", "正在同级目录 ${parentDir.uri} 中检索同名音频字幕: $mediaName")
                    val found = parentDir.findSameBaseSubtitle(mediaName)
                    Log.d("SubtitleFileResolver", "检索字幕完毕，结果: ${found?.uri}")
                    found
                }
                "file" -> {
                    val file = File(mediaUri.path ?: "")
                    val parentDir = file.parentFile ?: return null
                    val baseName = file.nameWithoutExtension

                    parentDir.listFiles { _, name ->
                        val ext = name.substringAfterLast(".").lowercase(Locale.ROOT)
                        name.substringBeforeLast(".") == baseName && SUBTITLE_EXTENSIONS.contains(ext)
                    }?.firstOrNull()?.let { subtitle ->
                        SubtitleFileRef(
                            uri = Uri.fromFile(subtitle),
                            extension = subtitle.extension.lowercase(Locale.ROOT),
                            displayName = subtitle.name
                        )
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("SubtitleFileResolver", "遍历检索同级目录字幕发生异常: ", e)
            null
        }
    }

    /**
     * 详尽的中文注释：逐级递归展开定位 SAF Tree 下的子目录文档。
     */
    private fun DocumentFile.findRelativeDirectory(relativeParentPath: String): DocumentFile? {
        if (relativeParentPath.isBlank()) return this
        return relativeParentPath.split('/').fold(this as DocumentFile?) { current, segment ->
            current?.findFile(segment)?.takeIf { it.isDirectory }
        }
    }

    /**
     * 详尽的中文注释：对同级目录下的文件进行同基准名（Base Name）的后缀匹配（如 media.mp3 -> media.srt）。
     */
    private fun DocumentFile.findSameBaseSubtitle(audioName: String): SubtitleFileRef? {
        val baseName = audioName.substringBeforeLast('.', missingDelimiterValue = audioName)
        return listFiles().firstNotNullOfOrNull { candidate ->
            val name = candidate.name ?: return@firstNotNullOfOrNull null
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            val sameBaseName = name.substringBeforeLast('.', missingDelimiterValue = name).equals(baseName, ignoreCase = true)
            if (candidate.isFile && sameBaseName && SUBTITLE_EXTENSIONS.contains(extension)) {
                SubtitleFileRef(candidate.uri, extension, name)
            } else {
                null
            }
        }
    }

    /**
     * 详尽的中文注释：根据后缀将支持的字幕后缀名翻译为 ExoPlayer/Media3 认识的标准 Subtitle MimeTypes。
     */
    private fun subtitleMimeType(extension: String): String? =
        when (extension.lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }

    // 详尽的中文注释：内部使用的字幕实体临时载体。
    private data class SubtitleFileRef(
        val uri: Uri,
        val extension: String,
        val displayName: String
    )
}