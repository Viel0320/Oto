package com.viel.oto.media.parser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.cover.CoverImageResult
import com.viel.oto.data.cover.CoverImageWriter
import com.viel.oto.data.cover.CoverRecoveryArtworkSource
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.library.FileRef
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.library.vfs.VfsNode
import com.viel.oto.media.manifest.ManifestSidecarSupport

/**
 * Media parser implementation of cover recovery reads.
 *
 * This adapter owns VFS sibling traversal and audio embedded-cover parsing so the data cover self-heal engine can
 * stay focused on retry policy, deduplication, and Room path updates.
 */
@OptIn(UnstableApi::class)
class MediaCoverRecoveryArtworkSource(
    private val fileReader: VfsFileInterface,
    private val coverImageWriter: CoverImageWriter
) : CoverRecoveryArtworkSource {
    private val metadataResolver = MetadataResolver(fileReader)

    override suspend fun extractEmbeddedCover(bookId: String, file: BookFileEntity): CoverImageResult {
        val embeddedCover = metadataResolver.extractWithEmbeddedCover(file).embeddedCover
        return if (embeddedCover == null || embeddedCover.bytes.isEmpty()) {
            CoverImageResult.Empty
        } else {
            coverImageWriter.saveEmbeddedImage(
                sourceId = "$bookId:${file.rootId}:${file.sourcePath}:embedded",
                artBytes = embeddedCover.bytes
            )
        }
    }

    override suspend fun extractSidecarCover(primaryFile: BookFileEntity): CoverImageResult {
        val sidecar = findSidecarImage(primaryFile) ?: return CoverImageResult.Empty
        return coverImageWriter.processExternalImage("${sidecar.root.id}:${sidecar.metadata.sourcePath}") {
            fileReader.open(sidecar)
        }
    }

    /**
     * Selects the best directory image sidecar by reusing manifest sidecar ordering over VFS sibling nodes.
     */
    private suspend fun findSidecarImage(file: BookFileEntity): VfsNode? {
        val parentPath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val files = fileReader.listChildren(file.rootId, parentPath)
            .filter { node -> !node.metadata.isDirectory && isImage(node.metadata.displayName) }
        val selectedRef = ManifestSidecarSupport.findDirectoryCover(
            files.map { node ->
                FileRef(
                    rootId = node.root.id,
                    sourcePath = node.metadata.sourcePath,
                    sourceIdentity = node.metadata.identity,
                    etag = node.metadata.etag,
                    parentSourcePath = node.metadata.parentSourcePath,
                    parentSourceKey = "${node.root.id}:${node.metadata.parentSourcePath}",
                    parentSourceIdentity = node.metadata.parentIdentity,
                    displayName = node.metadata.displayName,
                    fileSize = node.metadata.fileSize,
                    lastModified = node.metadata.lastModified
                )
            }
        ) ?: return null
        return files.firstOrNull { node -> node.metadata.sourcePath == selectedRef.sourcePath }
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }
}
