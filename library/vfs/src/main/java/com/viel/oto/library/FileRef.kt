package com.viel.oto.library

/**
 * Describes a source-backed file using only stable VFS coordinates and lightweight metadata.
 *
 * Import, metadata, and playback code can pass this value across domain boundaries without carrying
 * provider handles or scan-session state from the library import pipeline.
 */
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

/**
 * Provides the stable identity keys used to reconcile imported files with existing database rows.
 */
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
