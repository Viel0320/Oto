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

internal data class ImportScope(
    val id: String,
    val kind: ImportScopeKind,
    val inventory: FileInventory
)

internal enum class ImportScopeKind {
    CUE_MANIFEST,
    M3U8_MANIFEST,
    DIRECTORY_AUDIO
}

internal class ImportScopeBuilder(
    private val context: Context,
    private val fileReader: VfsFileInterface
) {
    suspend fun onDirectoryClosed(directory: DirectoryInventory): List<ImportScope> {
        return buildScopes(directory.toFileInventory())
    }

    fun finish(): List<ImportScope> {
        return emptyList()
    }

    suspend fun buildScopes(inventory: FileInventory): List<ImportScope> {
        val audioLookup = AudioLookup(inventory.audioFiles)
        val manifestClaimedAudioIdentities = mutableSetOf<FileIdentity>()
        val scopes = mutableListOf<ImportScope>()

        inventory.cueFiles.forEach { cue ->
            val audioRefs = resolveCueAudioRefs(fileReader, cue, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
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

        inventory.m3u8Files.forEach { m3u8 ->
            val audioRefs = resolveM3u8AudioRefs(fileReader, m3u8, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
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

    private suspend fun resolveCueAudioRefs(fileReader: VfsFileInterface, cue: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            CueManifestParser.parse(displayName = cue.displayName, openStream = { fileReader.open(cue) })
                ?.referencedFiles
                .orEmpty()
                .mapNotNull { entry -> resolveAudioRef(cue.parentSourceKey, entry, audioLookup) }
                .distinctBy { it.identity }
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

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

    private fun resolveAudioRef(parentKey: String, entry: String, audioLookup: AudioLookup): FileRef? =
        audioLookup.findSameDirectory(parentKey, entry)

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
            textFilesByParent = if (textFiles.isNotEmpty()) {
                mapOf("${root.id}:$sourcePath" to textFiles.sortedByStableFileKey())
            } else {
                emptyMap()
            }
        )

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

    private class AudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}
