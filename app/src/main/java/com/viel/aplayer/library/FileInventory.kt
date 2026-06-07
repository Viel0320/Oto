package com.viel.aplayer.library

import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.orchestrator.ExistingClaimIndex

// Scanner output: a stable file inventory, not import decisions.
data class FileInventory(
    val roots: List<LibraryRootEntity>,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByParent: Map<String, List<FileRef>>,
    // Include Directory Text Sidecars (Scan snapshot aggregation)
    // Includes sibling txt sidecars in the scan snapshot, enabling the manifest parser to match descriptions directly without re-evaluating VFS directories.
    val textFilesByParent: Map<String, List<FileRef>>
)

// Directory Inventory Representation (Physical folder snapshot)
// Represents a completed physical directory scan snapshot, serving as an ingestion event for scope-based streaming imports.
data class DirectoryInventory(
    val root: LibraryRootEntity,
    // VFS Path Identification (Legacy URI decoupling)
    // Stores directory locations via VFS sourcePath, removing persistent runtime dependencies on SAF URIs.
    val sourcePath: String,
    val sourceIdentity: String,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFiles: List<FileRef>,
    // Bundle Text Sidecars (Context propagation optimization)
    // Attaches sibling text files to the directory completion event, providing description candidates for subsequent manifest scopes without triggering listChildren.
    val textFiles: List<FileRef>,
    // Folder Last Modified Timestamp (Backward compatibility safety)
    // Introduces directory modification timestamps defaulting to 0L for reflection and database schema compatibility.
    val lastModified: Long = 0L
) {
    // Light Scan Manifest Context Preservation (Ownership reconciliation)
    // Keeps CUE/M3U8 neighborhoods intact so cold-start scans can compare manifests against lower-priority existing audio owners.
    fun forLightScanClaimReconciliation(existingClaimIndex: ExistingClaimIndex): DirectoryInventory {
        val hasManifestCandidates = cueFiles.isNotEmpty() || m3u8Files.isNotEmpty()
        return if (hasManifestCandidates) {
            this
        } else {
            copy(
                audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) }
                // Asset Retention Exclusion (Asset lifecycle management)
                // Sidecar files (text, images) bypass unclaimed filters, remaining in scope as directory metadata assets.
            )
        }
    }
}

// Runtime file reference used during a single scan/import run.
data class FileRef(
    val rootId: String,
    // VFS Path Resolution Location (Scan file targeting pointer)
    // Source path for scanning, utilized directly by the VFS facade for all subsequent content reads.
    val sourcePath: String,
    // Cross-Protocol Identity Key (Generic protocol mapping identifier)
    // Unique identity key across storage providers, removing SAF-specific storage references.
    val sourceIdentity: String,
    // Remote ETag Checksum (Incremental sync optimization)
    // Optional ETag value for remote sync verification; remains null for providers without etag support (e.g., SAF).
    val etag: String? = null,
    // Sibling Scoping Keys (Directory context mapping pointers)
    // Parent source indicators for directory grouping, claim constraints, and metadata sidecar resolution.
    val parentSourcePath: String,
    val parentSourceKey: String,
    val parentSourceIdentity: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long
) {
    // VFS Path Stable Key (Uniform asset identifier mapping)
    // Computes unique file key from the VFS path, preventing fallback to provider-specific URIs in manifest parser and logs.
    val vfsKey: String = vfsFileKey(rootId, sourcePath)

    // Resource Claim Identity (Identity mapping decoupling)
    // Computes ownership identity from rootId/sourcePath and sourceIdentity, replacing legacy URI-based structures.
    val identity: FileIdentity = FileIdentity(rootId = rootId, sourcePath = sourcePath, sourceIdentity = sourceIdentity)
}

// Decoupled File Identity (Normalized storage reference wrapper)
// Encapsulates VFS standard identity parameters, excluding provider-specific URIs from claim keys.
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

// Unique VFS Key Formatting (Collision avoidance utility)
// Combines rootId and sourcePath using an invisible delimiter to prevent string collisions on common directory symbols.
fun vfsFileKey(rootId: String, sourcePath: String): String = "$rootId\u001F$sourcePath"

// Stable scanner ordering keeps the same import result for the same file tree.
fun Iterable<FileRef>.sortedByStableFileKey(): List<FileRef> =
    sortedWith(compareBy<FileRef> { it.sourcePath.lowercase() }.thenBy { it.sourceIdentity }.thenBy { it.vfsKey })
