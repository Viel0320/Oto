package com.viel.aplayer.library

import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.LibraryRootEntity

// Scanner output: a stable file inventory, not import decisions.
data class FileInventory(
    val roots: List<LibraryRootEntity>,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByParent: Map<String, List<FileRef>>
) {
    // Cold-start light scans must only feed previously unseen files into import parsing.
    fun onlyUnclaimed(existingClaimIndex: ExistingClaimIndex): FileInventory =
        FileInventory(
            roots = roots,
            cueFiles = cueFiles.filterNot { existingClaimIndex.has(it.identity) },
            m3u8Files = m3u8Files.filterNot { existingClaimIndex.has(it.identity) },
            audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) },
            imageFilesByParent = imageFilesByParent
        )
}

// Runtime file reference used during a single scan/import run.
data class FileRef(
    val uri: String,
    val rootId: String,
    val documentId: String,
    val relativePath: String,
    val parentDocumentId: String,
    val parentUri: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long,
    // Keep DocumentFile out of Room models; it is only used by parsers/resolvers during this run.
    val documentFile: DocumentFile,
    // Manifest parsers need the direct parent folder to resolve relative entries.
    val parentDocumentFile: DocumentFile
) {
    val identity: FileIdentity = FileIdentity(uri = uri, documentId = documentId)
}

// File ownership identity: uri is first-version primary, documentId is a stable helper when present.
data class FileIdentity(
    val uri: String,
    val documentId: String
) {
    fun keys(): Set<String> = buildSet {
        add("uri:$uri")
        if (documentId.isNotBlank()) add("doc:$documentId")
    }
}

// Stable scanner ordering keeps the same import result for the same file tree.
fun Iterable<FileRef>.sortedByStableFileKey(): List<FileRef> =
    sortedWith(compareBy<FileRef> { it.relativePath.lowercase() }.thenBy { it.documentId }.thenBy { it.uri })
