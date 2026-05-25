package com.viel.aplayer.media.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.vfs.VfsFileReader
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.media.PlaybackSubtitle
import com.viel.aplayer.media.VfsPlaybackUri
import com.viel.aplayer.media.subtitle.SubtitleParser
import com.viel.aplayer.ui.player.components.SubtitleLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

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
    private val fileReader = VfsFileReader(context.applicationContext, libraryRootDao)

    /**
     * 详尽的中文注释：加载并解析指定入库音频文件所对应的字幕文件。
     * 为每一次改动添加详尽的中文注释：调用方传入 BookFileEntity.id 后，本组件只通过数据库文件行与 VFS 同目录枚举查找字幕。
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> =
        withContext(Dispatchers.IO) {
            // 为每一次改动添加详尽的中文注释：播放器 ViewModel 默认从 Main 协程触发字幕回退，外部字幕的数据库查询、WebDAV 枚举与流解析必须整体切到 IO。
            // 为每一次改动添加详尽的中文注释：字幕入口改为 BookFileEntity.id，避免播放器切换到 VFS 虚拟 URI 后无法再通过原始 URI 反查文件。
            val scannedFile = bookDao.getBookFileById(bookFileId) ?: return@withContext emptyList()
            val attachment = loadSubtitleAttachment(scannedFile)
            attachment?.lines ?: emptyList()
        }

    /**
     * 详尽的中文注释：根据已导入的书籍文件实体，查找其所在同级目录下的同名/同 base 名的字幕文件并解析。
     * 为每一次改动添加详尽的中文注释：优先使用 rootId/sourcePath 通过 VFS 定位同级字幕。
     */
    private suspend fun loadSubtitleAttachment(file: BookFileEntity): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(file) ?: return null
        return parseSubtitleSuspend(subtitle.sourceId, subtitle.extension, subtitle.displayName) {
            subtitle.node?.let { fileReader.open(it) }
        }
    }

    /**
     * 详尽的中文注释：核心字幕解析逻辑。
     * 为每一次改动添加详尽的中文注释：字幕字节流由 VFS 打开，解析器不再直接访问 content/file URI。
     */
    private suspend fun parseSubtitleSuspend(
        sourceId: String,
        extension: String,
        displayName: String,
        openStream: suspend () -> InputStream?
    ): PlaybackSubtitle? =
        try {
            // 为每一次改动添加详尽的中文注释：VFS 字幕读取是 suspend 流工厂，避免在同步 lambda 内调用挂起函数。
            val lines = openStream()?.use { SubtitleParser.parse(it, extension) }.orEmpty()
            PlaybackSubtitle(
                // 为每一次改动添加详尽的中文注释：字幕附件对 Media3 暴露应用内部 VFS 标识，不再把 provider URI 作为字幕身份。
                uri = Uri.Builder()
                    .scheme(VfsPlaybackUri.SCHEME)
                    .authority("subtitle")
                    .appendPath(sourceId)
                    .build(),
                mimeType = subtitleMimeType(extension),
                label = displayName.substringBeforeLast('.'),
                lines = lines
            )
        } catch (e: Exception) {
            Log.e("SubtitleFileResolver", "解析 VFS 字幕文件失败: $sourceId", e)
            null
        }

    /**
     * 详尽的中文注释：基于书籍物理文件定位外部字幕文件的物理查找算法。
     * 为每一次改动添加详尽的中文注释：已入库文件只通过 VFS sourcePath 定位同级目录，再按同 base 名匹配字幕。
     */
    private suspend fun findSubtitleFile(file: BookFileEntity): SubtitleFileRef? {
        val parentPath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val audioName = file.sourcePath.substringAfterLast('/').ifBlank { file.displayName }
        val baseName = audioName.substringBeforeLast('.', missingDelimiterValue = audioName)
        return fileReader.listChildren(file.rootId, parentPath).firstNotNullOfOrNull { node ->
            if (node.metadata.isDirectory) return@firstNotNullOfOrNull null
            val name = node.metadata.displayName
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(
                Locale.ROOT)
            val sameBaseName = name.substringBeforeLast('.', missingDelimiterValue = name).equals(baseName, ignoreCase = true)
            if (sameBaseName && SUBTITLE_EXTENSIONS.contains(extension)) {
                SubtitleFileRef(
                    sourceId = "${node.root.id}:${node.metadata.sourcePath}",
                    extension = extension,
                    displayName = name,
                    node = node
                )
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
        val sourceId: String,
        val extension: String,
        val displayName: String,
        val node: VfsNode?
    )
}