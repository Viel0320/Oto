package com.viel.aplayer.library

import android.content.Context
import com.viel.aplayer.library.manifest.CueManifestParser
import com.viel.aplayer.library.manifest.M3u8ManifestParser
import com.viel.aplayer.library.manifest.ManifestResolver
import java.util.Locale

// 详尽的中文注释：ImportScope 是“可以安全裁决并即时入库”的最小导入单元，避免直接按 parentUri 切片导致 claim 与启发式聚合上下文不完整。
internal data class ImportScope(
    val id: String,
    val kind: ImportScopeKind,
    val inventory: FileInventory
)

// 详尽的中文注释：显式区分清单 scope 与散落音频 scope，让 RescanCoordinator 可以按 CUE、M3U8、启发式剩余音频的优先级稳定处理。
internal enum class ImportScopeKind {
    CUE_MANIFEST,
    M3U8_MANIFEST,
    DIRECTORY_AUDIO
}

// 详尽的中文注释：从目录关闭事件增量构建 claim-safe scope；清单只认同级音频，已被清单引用的同级音频会从后续启发式目录 scope 中排除。
internal class ImportScopeBuilder(private val context: Context) {
    fun onDirectoryClosed(directory: DirectoryInventory): List<ImportScope> {
        // 详尽的中文注释：manifest 已收窄为同级目录语义，因此目录一关闭即可安全释放该目录的清单 scope 与剩余音频启发式 scope。
        return buildScopes(directory.toFileInventory())
    }

    fun finish(): List<ImportScope> {
        // 详尽的中文注释：目录关闭事件已经逐目录释放 scope，finish 保留为后续跨目录策略扩展点。
        return emptyList()
    }

    fun buildScopes(inventory: FileInventory): List<ImportScope> {
        val audioLookup = AudioLookup(inventory.audioFiles)
        val manifestClaimedAudioIdentities = mutableSetOf<FileIdentity>()
        val scopes = mutableListOf<ImportScope>()

        // 详尽的中文注释：CUE 具有最高确定性优先级，先形成 scope，后续 M3U8 或启发式遇到同一音频会被本轮 claim ledger 拦住。
        inventory.cueFiles.forEach { cue ->
            val audioRefs = resolveCueAudioRefs(cue, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    id = "cue:${cue.uri}",
                    kind = ImportScopeKind.CUE_MANIFEST,
                    inventory = inventory.scopeInventory(
                        cueFiles = listOf(cue),
                        m3u8Files = emptyList(),
                        audioFiles = audioRefs
                    )
                )
            )
        }

        // 详尽的中文注释：M3U8 scope 排在 CUE 之后但仍早于启发式，保证播放列表声明的所有权优先于散落音频聚合。
        inventory.m3u8Files.forEach { m3u8 ->
            val audioRefs = resolveM3u8AudioRefs(m3u8, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    id = "m3u8:${m3u8.uri}",
                    kind = ImportScopeKind.M3U8_MANIFEST,
                    inventory = inventory.scopeInventory(
                        cueFiles = emptyList(),
                        m3u8Files = listOf(m3u8),
                        audioFiles = audioRefs
                    )
                )
            )
        }

        // 详尽的中文注释：只有未被任何清单引用的散落音频才进入目录音频 scope，避免 manifest 与 heuristic 重复争抢同一批音频。
        val looseAudioByParent = inventory.audioFiles
            .filterNot { it.identity in manifestClaimedAudioIdentities }
            .groupBy { it.parentUri }

        looseAudioByParent.forEach { (parentUri, audioRefs) ->
            scopes.add(
                ImportScope(
                    id = "directory:$parentUri",
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

    // 详尽的中文注释：CUE 解析只用于 scope 闭包构建；真正的元数据与章节解析仍由 ManifestParseStep 在导入流水线中完成。
    private fun resolveCueAudioRefs(cue: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            CueManifestParser.parse(context, cue.documentFile)
                ?.referencedFiles
                .orEmpty()
                .mapNotNull { entry -> resolveAudioRef(cue.parentUri, entry, audioLookup) }
                .distinctBy { it.identity }
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

    // 详尽的中文注释：M3U8 scope 闭包忽略远程 URL，只把能在当前 SAF 授权树中解析到的本地音频并入清单 scope。
    private fun resolveM3u8AudioRefs(m3u8: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            M3u8ManifestParser.parse(context, m3u8.documentFile)
                .items
                .asSequence()
                .map { it.uri }
                .filterNot { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                .mapNotNull { entry -> resolveAudioRef(m3u8.parentUri, entry, audioLookup) }
                .distinctBy { it.identity }
                .toList()
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

    // 详尽的中文注释：清单 scope 闭包只用扫描阶段已经拿到的同目录文件名索引，不再为了每个条目重新走 SAF findFile/listFiles。
    private fun resolveAudioRef(parentUri: String, entry: String, audioLookup: AudioLookup): FileRef? =
        audioLookup.findSameDirectory(parentUri, entry)

    // 详尽的中文注释：将单个已关闭目录转换成局部 FileInventory；同级图片只服务当前目录的 sidecar 封面匹配。
    private fun DirectoryInventory.toFileInventory(): FileInventory =
        FileInventory(
            roots = listOf(root),
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = if (imageFiles.isNotEmpty()) {
                mapOf(directoryUri to imageFiles.sortedByStableFileKey())
            } else {
                emptyMap()
            }
        )

    // 详尽的中文注释：为 scope 构造局部 FileInventory，同时带上相关父目录的 sidecar 图片，保留封面匹配所需的目录上下文。
    private fun FileInventory.scopeInventory(
        cueFiles: List<FileRef>,
        m3u8Files: List<FileRef>,
        audioFiles: List<FileRef>
    ): FileInventory {
        val parentUris = buildSet {
            cueFiles.forEach { add(it.parentUri) }
            m3u8Files.forEach { add(it.parentUri) }
            audioFiles.forEach { add(it.parentUri) }
        }
        val imagesByScopeParent = imageFilesByParent
            .filterKeys { it in parentUris }
            .mapValues { (_, images) -> images.sortedByStableFileKey() }
        val rootIds = buildSet {
            cueFiles.forEach { add(it.rootId) }
            m3u8Files.forEach { add(it.rootId) }
            audioFiles.forEach { add(it.rootId) }
            imagesByScopeParent.values.flatten().forEach { add(it.rootId) }
        }

        return FileInventory(
            roots = roots.filter { it.id in rootIds }.ifEmpty { roots },
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imagesByScopeParent
        )
    }

    // 详尽的中文注释：AudioLookup 预先按“父目录 Uri + 文件名”建立同目录索引，清单闭包可以 O(1) 命中本轮扫描已有 FileRef。
    private class AudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentUri, ref.displayName) }

        // 详尽的中文注释：这里复用 ManifestResolver.sameDirectoryFileName 的路径规整，只接受同级文件名，避免清单引用子目录时错误扩大 claim 范围。
        fun findSameDirectory(parentUri: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentUri, fileName)]
        }

        // 详尽的中文注释：文件名索引用 Locale.ROOT 做大小写折叠，保留 Windows 用户常见的大小写不敏感体验，同时不受设备区域设置影响。
        private fun key(parentUri: String, fileName: String): String =
            "$parentUri\n${fileName.lowercase(Locale.ROOT)}"
    }
}
