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
