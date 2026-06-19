package com.viel.aplayer.application.library.detail

import com.viel.aplayer.application.library.LibraryBookSourceType
import com.viel.aplayer.data.db.AudiobookSchema
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Detail Source Location Formatter (Maps storage projections into a safe detail breadcrumb)
 * Renders registered root names plus relative file paths while avoiding raw SAF tree URIs, WebDAV URLs, and ABS playback API paths.
 */
class DetailSourceLocationFormatter {
    fun format(
        snapshot: DetailSnapshot,
        files: List<DetailSourceFile>,
        root: DetailSourceRoot?
    ): String {
        val displayFile = selectDisplayFile(snapshot.sourceType, files)
        val playableFileCount = files.count { file -> file.fileRole == AudiobookSchema.FileRole.AUDIO }
        val rootLabel = root?.displayName?.takeIf { label -> label.isNotBlank() } ?: DEFAULT_ROOT_LABEL
        val sourceScheme = resolveSourceDisplayScheme(root, snapshot.sourceType)
        val segments = buildList {
            // Detail Source Scheme Prefix (Pair the protocol with the registered root label)
            // The label comes from the library root projection, so the formatter never needs raw provider locations.
            add("$sourceScheme://${rootLabel.toSourceSchemeLabel()}")
            if (sourceScheme == AudiobookSchema.LibrarySourceType.ABS) {
                // ABS Source Privacy (Hide remote playback endpoint paths)
                // ABS track URLs are transport details, so details show the registered library and selected title instead.
                add(snapshot.item.title.ifBlank { displayFile?.displayName.orEmpty() })
            } else {
                val pathSegments = displayFile
                    ?.sourcePath
                    .orEmpty()
                    .toDisplayPathSegments(displayFile?.displayName)
                // Root Label De-Duplication (Avoid repeated root names in legacy relative paths)
                // Older imports may store "Library/Book/file.mp3" even though the root label already renders "Library".
                addAll(pathSegments.dropLeadingDuplicate(rootLabel))
            }
        }.filter { segment -> segment.isNotBlank() }
        val baseLocation = segments.joinToString("/")
        return if (playableFileCount > 1) {
            "$baseLocation / $playableFileCount tracks"
        } else {
            baseLocation
        }
    }

    /**
     * Detail Source File Selector (Chooses the most representative detail breadcrumb file)
     * Manifest-backed books prefer their manifest sidecar while regular and generated playlists prefer the first playable audio row.
     */
    private fun selectDisplayFile(
        sourceType: LibraryBookSourceType,
        files: List<DetailSourceFile>
    ): DetailSourceFile? {
        val playableFiles = files
            .filter { file -> file.fileRole == AudiobookSchema.FileRole.AUDIO }
            .sortedBy { file -> file.index }
        return when (sourceType) {
            LibraryBookSourceType.CUE,
            LibraryBookSourceType.M3U8 -> {
                files.firstOrNull { file -> file.fileRole == AudiobookSchema.FileRole.SOURCE_MANIFEST }
                    ?: playableFiles.firstOrNull()
                    ?: files.minByOrNull { file -> file.index }
            }

            LibraryBookSourceType.GENERATED_M3U8 -> playableFiles.firstOrNull()
                ?: files.minByOrNull { file -> file.index }
            else -> playableFiles.firstOrNull() ?: files.minByOrNull { file -> file.index }
        }
    }

    /**
     * Detail Source Scheme Resolver (Derives the visible protocol from the registered root)
     * Book source type is only a fallback for transitional selections where the root row is unavailable.
     */
    private fun resolveSourceDisplayScheme(
        root: DetailSourceRoot?,
        bookSourceType: LibraryBookSourceType
    ): AudiobookSchema.LibrarySourceType {
        return root?.sourceType ?: when (bookSourceType) {
            LibraryBookSourceType.ABS_REMOTE -> AudiobookSchema.LibrarySourceType.ABS
            else -> AudiobookSchema.LibrarySourceType.SAF
        }
    }

    /**
     * VFS Path Segment Formatter (Converts VFS-relative paths into display breadcrumb parts)
     * Decodes percent-escaped names with JVM-safe APIs so formatter tests can run as local unit tests.
     */
    private fun String.toDisplayPathSegments(fallbackDisplayName: String?): List<String> {
        val normalizedPath = replace('\\', '/')
            .hideProviderAuthority()
            .trim()
            .trim('/')
        val pathSegments = normalizedPath
            .split('/')
            .map { segment -> segment.trim().decodePathSegment() }
            .filter { segment -> segment.isNotBlank() }
        return pathSegments.ifEmpty {
            fallbackDisplayName
                ?.takeIf { displayName -> displayName.isNotBlank() }
                ?.let(::listOf)
                ?: emptyList()
        }
    }

    /**
     * Duplicate Root Segment Filter (Keeps breadcrumbs compact)
     * Removes a leading path segment that already matches the registered root display label.
     */
    private fun List<String>.dropLeadingDuplicate(rootLabel: String): List<String> {
        return if (firstOrNull()?.equals(rootLabel, ignoreCase = true) == true) {
            drop(1)
        } else {
            this
        }
    }

    /**
     * Source Scheme Label Normalizer (Prepares root labels for protocol-prefixed display)
     * Trims accidental slashes so values like "/audiobooks" render as "WEBDAV://audiobooks".
     */
    private fun String.toSourceSchemeLabel(): String {
        return trim().trim('/').ifBlank { DEFAULT_ROOT_LABEL }
    }

    /**
     * Provider Authority Filter (Remove accidental absolute remote endpoints)
     * WebDAV imports should already store relative source paths, but this guard strips scheme and host when legacy data contains a full URL.
     */
    private fun String.hideProviderAuthority(): String {
        val trimmedPath = trim()
        if (!trimmedPath.startsWith("http://", ignoreCase = true) &&
            !trimmedPath.startsWith("https://", ignoreCase = true)
        ) {
            return this
        }
        return runCatching {
            URI(trimmedPath).rawPath.orEmpty().ifBlank { trimmedPath }
        }.getOrElse { this }
    }

    /**
     * Percent Encoding Decoder (Decode path labels without treating plus signs as spaces)
     * URLDecoder is JVM-friendly but form-oriented, so literal plus signs are protected before decoding.
     */
    private fun String.decodePathSegment(): String {
        return runCatching {
            URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrElse { this }
    }

    private companion object {
        private const val DEFAULT_ROOT_LABEL = "Library"
    }
}
