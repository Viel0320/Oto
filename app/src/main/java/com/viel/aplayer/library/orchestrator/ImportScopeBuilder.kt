package com.viel.aplayer.library.orchestrator

import android.content.Context
import com.viel.aplayer.library.DirectoryInventory
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.sortedByStableFileKey
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.media.manifest.ManifestResolver
import java.util.Locale

// ImportScope holds the atomic files for metadata resolution (Pipeline Data Model)
// Grouping files into scopes prevents split contexts for manifest resolution and heuristic grouping.
internal data class ImportScope(
    val id: String,
    val kind: ImportScopeKind,
    val inventory: FileInventory
)

// Differentiate Scoped Processing Types (Pipeline Configuration)
// Categorization permits prioritizing CUE and M3U8 layouts over loose directory files.
internal enum class ImportScopeKind {
    CUE_MANIFEST,
    M3U8_MANIFEST,
    DIRECTORY_AUDIO
}

// Compile Incremental Scopes (Incremental Scanning Rules)
// Compiles claim-safe import scopes as folders complete scanning.
// Manifest references are restricted to local folder levels to exclude declared audio tracks from downstream directories.
internal class ImportScopeBuilder(
    private val context: Context,
    // Inject Shared VFS Interface (Dependency Decoupling)
    // Avoids allocating redundant file reader instances across scope compilation runs.
    private val fileReader: VfsFileInterface
) {
    suspend fun onDirectoryClosed(directory: DirectoryInventory): List<ImportScope> {
        // Directory Close Triggers Scoped Emits (Lifecycle Coordinator)
        // Folder closing emits the manifest and loose file scopes for immediately isolated parsing.
        return buildScopes(directory.toFileInventory())
    }

    fun finish(): List<ImportScope> {
        // Scan Stream Completion Hook (Lifecycle Coordinator)
        // Kept as an extension hook to process multi-directory schemas after individual folder iterations complete.
        return emptyList()
    }

    suspend fun buildScopes(inventory: FileInventory): List<ImportScope> {
        val audioLookup = AudioLookup(inventory.audioFiles)
        val manifestClaimedAudioIdentities = mutableSetOf<FileIdentity>()
        val scopes = mutableListOf<ImportScope>()

        // Prioritize CUE Layouts (Pipeline Routing Rules)
        // Process CUE configurations first so that downstream M3U8 or directory sweeps respect earlier file reservations.
        inventory.cueFiles.forEach { cue ->
            val audioRefs = resolveCueAudioRefs(fileReader, cue, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    // Stable Path Scoping IDs (Storage Decoupling)
                    // Utilizes stable VFS coordinates to label scope runs, preventing leakage of protocol-specific URIs.
                    id = "cue:${cue.rootId}:${cue.sourcePath}",
                    kind = ImportScopeKind.CUE_MANIFEST,
                    inventory = inventory.scopeInventory(
                        cueFiles = listOf(cue),
                        m3u8Files = emptyList(),
                        audioFiles = audioRefs
                    )
                )
            )
        }

        // Prioritize M3U8 Layouts (Pipeline Routing Rules)
        // Group playlist items next, ensuring structured lists override heuristic folder aggregation attempts.
        inventory.m3u8Files.forEach { m3u8 ->
            val audioRefs = resolveM3u8AudioRefs(fileReader, m3u8, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    // Stable Playlist Scoping IDs (Storage Decoupling)
                    // Apply identical VFS path coordinates to identify M3U8 scope targets.
                    id = "m3u8:${m3u8.rootId}:${m3u8.sourcePath}",
                    kind = ImportScopeKind.M3U8_MANIFEST,
                    inventory = inventory.scopeInventory(
                        cueFiles = emptyList(),
                        m3u8Files = listOf(m3u8),
                        audioFiles = audioRefs
                    )
                )
            )
        }

        // Group Residual Tracks (Pipeline Routing Rules)
        // Allocates files to folder scopes only if they are not claimed by any local manifests, avoiding ownership conflicts.
        val looseAudioByParent = inventory.audioFiles
            .filterNot { it.identity in manifestClaimedAudioIdentities }
            .groupBy { it.parentSourceKey }

        looseAudioByParent.forEach { (parentKey, audioRefs) ->
            scopes.add(
                ImportScope(
                    id = "directory:$parentKey",
                    kind = ImportScopeKind.DIRECTORY_AUDIO,
                    inventory = inventory.scopeInventory(
                        cueFiles = emptyList(),
                        m3u8Files = emptyList(),
                        audioFiles = audioRefs.sortedByStableFileKey()
                    )
                )
            )
        }

        return scopes
    }

    // Out-of-Band CUE Scoping (File Discovery)
    // Runs dry CUE parsing to establish file scopes; metadata extraction and chapters run downstream.
    private suspend fun resolveCueAudioRefs(fileReader: VfsFileInterface, cue: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            CueManifestParser.parse(displayName = cue.displayName, openStream = { fileReader.open(cue) })
                ?.referencedFiles
                .orEmpty()
                .mapNotNull { entry -> resolveAudioRef(cue.parentSourceKey, entry, audioLookup) }
                .distinctBy { it.identity }
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

    // Out-of-Band M3U8 Scoping (File Discovery)
    // Skips remote URLs in playlist entries; resolves files present within current VFS boundary.
    private suspend fun resolveM3u8AudioRefs(fileReader: VfsFileInterface, m3u8: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            M3u8ManifestParser.parse(displayName = m3u8.displayName, openStream = { fileReader.open(m3u8) })
                .items
                .asSequence()
                .map { it.uri }
                .filterNot { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                .mapNotNull { entry -> resolveAudioRef(m3u8.parentSourceKey, entry, audioLookup) }
                .distinctBy { it.identity }
                .toList()
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

    // Fast Track Name Matching (Performance Optimization)
    // Matches references against local file indexes to avoid heavy disk-based lookups.
    private fun resolveAudioRef(parentKey: String, entry: String, audioLookup: AudioLookup): FileRef? =
        audioLookup.findSameDirectory(parentKey, entry)

    // Map Closed Directory to Inventory (Context Propagation)
    // Aggregates files within the closed directory, retaining sidecar images for localized cover resolving.
    private fun DirectoryInventory.toFileInventory(): FileInventory =
        FileInventory(
            roots = listOf(root),
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = if (imageFiles.isNotEmpty()) {
                mapOf("${root.id}:$sourcePath" to imageFiles.sortedByStableFileKey())
            } else {
                emptyMap()
            },
            // Scoped Text Sidecar Pack (Asset Association)
            // Groups text metadata assets into the local inventory so that ManifestParseStep reads summaries without VFS re-scanning.
            textFilesByParent = if (textFiles.isNotEmpty()) {
                mapOf("${root.id}:$sourcePath" to textFiles.sortedByStableFileKey())
            } else {
                emptyMap()
            }
        )

    // Compile Scoped Inventory (Context Propagation)
    // Prepares the FileInventory containing files and sidecar assets localized to the scope's directory structure.
    private fun FileInventory.scopeInventory(
        cueFiles: List<FileRef>,
        m3u8Files: List<FileRef>,
        audioFiles: List<FileRef>
    ): FileInventory {
        val parentKeys = buildSet {
            cueFiles.forEach { add(it.parentSourceKey) }
            m3u8Files.forEach { add(it.parentSourceKey) }
            audioFiles.forEach { add(it.parentSourceKey) }
        }
        val imagesByScopeParent = imageFilesByParent
            .filterKeys { it in parentKeys }
            .mapValues { (_, images) -> images.sortedByStableFileKey() }
        // Forward Description Context (Asset Association)
        // Both manifest and folder scopes must contain description text context to prevent parser pipeline failures.
        val textsByScopeParent = textFilesByParent
            .filterKeys { it in parentKeys }
            .mapValues { (_, texts) -> texts.sortedByStableFileKey() }
        val rootIds = buildSet {
            cueFiles.forEach { add(it.rootId) }
            m3u8Files.forEach { add(it.rootId) }
            audioFiles.forEach { add(it.rootId) }
            imagesByScopeParent.values.flatten().forEach { add(it.rootId) }
            textsByScopeParent.values.flatten().forEach { add(it.rootId) }
        }

        return FileInventory(
            roots = roots.filter { it.id in rootIds }.ifEmpty { roots },
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imagesByScopeParent,
            textFilesByParent = textsByScopeParent
        )
    }

    // Pre-indexed Audio Lookup (Performance Optimization)
    // Builds an O(1) index using parent directories and display names for fast track-matching.
    private class AudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        // Enforce Local Filename Resolution (Security & Correctness)
        // Limits queries to single-level filenames to prevent claims from expanding into child paths.
        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        // Locale-Independent Name Matching (Internationalization Safety)
        // Performs case folding using Locale.ROOT to retain case-insensitive matching regardless of system locale.
        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}
