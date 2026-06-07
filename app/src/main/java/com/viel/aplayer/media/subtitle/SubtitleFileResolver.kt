package com.viel.aplayer.media.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.media.PlaybackSubtitle
import com.viel.aplayer.media.VfsPlaybackUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

/**
 * External Subtitle Resolver (Handles discovery, matching, and parsing of external subtitle files such as .srt, .ass, etc.)
 * Decouples subtitle discovery from the core library repository, isolating complex I/O from core database access.
 */
@UnstableApi
class SubtitleFileResolver(
    private val context: Context,
    private val bookDao: BookDao,
    private val fileReader: VfsFileInterface
) {
    // Supported Subtitle Formats (The set of file extensions recognized by this resolver)
    private val subtitleExtension = setOf("srt", "ass", "ssa", "vtt", "lrc")

    /**
     * Subtitle Loading Entrypoint (Queries, locates, and parses subtitles for a specific database book file)
     * Performs DB lookup using the BookFileEntity.id and searches the parent folder via Virtual File System (VFS).
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> =
        withContext(Dispatchers.IO) {
            // Thread-Safe Subtitle Query (Switches context to Dispatchers.IO to prevent blocking the UI thread on db and network requests)
            // Utilizes BookFileEntity.id to avoid reference breaks when the playback engine switches to virtual VFS URIs.
            val scannedFile = bookDao.getBookFileById(bookFileId) ?: return@withContext emptyList()
            val attachment = loadSubtitleAttachment(scannedFile)
            attachment?.lines ?: emptyList()
        }

    /**
     * Sibling Directory Scanner (Locates matching subtitle files in the same directory as the target audio file)
     * Queries the sibling VFS tree by resolving parent paths and locating subtitle candidates with identical base names.
     */
    private suspend fun loadSubtitleAttachment(file: BookFileEntity): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(file) ?: return null
        return parseSubtitleSuspend(subtitle.sourceId, subtitle.extension, subtitle.displayName) {
            subtitle.node?.let { fileReader.open(it) }
        }
    }

    /**
     * Stream-Based Parse Subroutine (Opens streams via VFS and delegates token extraction to the subtitle format parser)
     * Shielding parsing algorithms from content/file providers by routing all accesses through abstract stream factory.
     */
    private suspend fun parseSubtitleSuspend(
        sourceId: String,
        extension: String,
        displayName: String,
        openStream: suspend () -> InputStream?
    ): PlaybackSubtitle? =
        try {
            // Suspending Stream Factory Integration (Executes the parser under safe scope boundaries without blocking callbacks)
            // Maps external subtitles to application-internal VFS Uri tokens rather than leaky content provider URIs.
            val lines = openStream()?.use { SubtitleParser.parse(it, extension) }.orEmpty()
            PlaybackSubtitle(
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
            Log.e("SubtitleFileResolver", "Failed to parse VFS subtitle file: $sourceId", e)
            null
        }

    /**
     * Base Name Matching Algorithm (Queries sibling VFS nodes and filters files by filename case-insensitively)
     * Reconciles difference in extensions while matching names exactly to pair subtitles with audio files.
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
            if (sameBaseName && subtitleExtension.contains(extension)) {
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
     * MimeType Standardization Mapping (Maps recognized file extensions to standard ExoPlayer/Media3 Subtitle MimeTypes)
     */
    private fun subtitleMimeType(extension: String): String? =
        when (extension.lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }

    // Internal subtitle reference (Temporary entity used to buffer scanned subtitle file details before full stream parsing)
    private data class SubtitleFileRef(
        val sourceId: String,
        val extension: String,
        val displayName: String,
        val node: VfsNode?
    )
}
