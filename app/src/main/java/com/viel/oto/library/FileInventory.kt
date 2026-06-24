package com.viel.oto.library

import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.orchestrator.ExistingClaimIndex

data class FileInventory(
    val roots: List<LibraryRootEntity>,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByParent: Map<String, List<FileRef>>,
    val textFilesByParent: Map<String, List<FileRef>>
)

data class DirectoryInventory(
    val root: LibraryRootEntity,
    val sourcePath: String,
    val sourceIdentity: String,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFiles: List<FileRef>,
    val textFiles: List<FileRef>,
    val lastModified: Long = 0L
) {
    fun forLightScanClaimReconciliation(existingClaimIndex: ExistingClaimIndex): DirectoryInventory {
        val hasManifestCandidates = cueFiles.isNotEmpty() || m3u8Files.isNotEmpty()
        return if (hasManifestCandidates) {
            this
        } else {
            copy(
                audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) }
            )
        }
    }
}

data class FileRef(
    val rootId: String,
    val sourcePath: String,
    val sourceIdentity: String,
    val etag: String? = null,
    val parentSourcePath: String,
    val parentSourceKey: String,
    val parentSourceIdentity: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long
) {
    val vfsKey: String = vfsFileKey(rootId, sourcePath)

    val identity: FileIdentity = FileIdentity(rootId = rootId, sourcePath = sourcePath, sourceIdentity = sourceIdentity)
}

data class FileIdentity(
    val rootId: String,
    val sourcePath: String,
    val sourceIdentity: String
) {
    fun keys(): Set<String> = buildSet {
        add("path:${vfsFileKey(rootId, sourcePath)}")
        if (sourceIdentity.isNotBlank()) add("src:$rootId:$sourceIdentity")
    }
}

fun vfsFileKey(rootId: String, sourcePath: String): String = "$rootId\u001F$sourcePath"

fun Iterable<FileRef>.sortedByStableFileKey(): List<FileRef> =
    sortedWith(compareBy<FileRef> { it.sourcePath.lowercase() }.thenBy { it.sourceIdentity }.thenBy { it.vfsKey })
