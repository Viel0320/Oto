package com.viel.aplayer.library.orchestrator

import android.content.Context
import com.viel.aplayer.library.DirectoryInventory
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.sortedByStableFileKey
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.media.manifest.ManifestResolver
import com.viel.aplayer.library.vfs.VfsFileInterface
import java.util.Locale

// ImportScope 是“可以安全裁决并即时入库”的最小导入单元，避免直接按 VFS 父目录键切片导致 claim 与启发式聚合上下文不完整。
internal data class ImportScope(
    val id: String,
    val kind: ImportScopeKind,
    val inventory: FileInventory
)

// 显式区分清单 scope 与散落音频 scope，让 ScopeOrchestrator 可以按 CUE、M3U8、启发式剩余音频的优先级稳定处理。
internal enum class ImportScopeKind {
    CUE_MANIFEST,
    M3U8_MANIFEST,
    DIRECTORY_AUDIO
}

// 从目录关闭事件增量构建 claim-safe scope；清单只认同级音频，已被清单引用的同级音频会从后续启发式目录 scope 中排除。
internal class ImportScopeBuilder(
    private val context: Context,
    // 注入统一会话级 VFS 读取门面，消除了原本方法体内每次执行 buildScopes 时的自构开销
    private val fileReader: VfsFileInterface
) {
    suspend fun onDirectoryClosed(directory: DirectoryInventory): List<ImportScope> {
        // manifest 已收窄为同级目录语义，因此目录一关闭即可安全释放该目录的清单 scope 与剩余音频启发式 scope。
        return buildScopes(directory.toFileInventory())
    }

    fun finish(): List<ImportScope> {
        // 目录关闭事件已经逐目录释放 scope，finish 保留为后续跨目录策略扩展点。
        return emptyList()
    }

    suspend fun buildScopes(inventory: FileInventory): List<ImportScope> {
        val audioLookup = AudioLookup(inventory.audioFiles)
        val manifestClaimedAudioIdentities = mutableSetOf<FileIdentity>()
        val scopes = mutableListOf<ImportScope>()

        // CUE 具有最高确定性优先级，先形成 scope，后续 M3U8 或启发式遇到同一音频会被本轮 claim ledger 拦住。
        inventory.cueFiles.forEach { cue ->
            val audioRefs = resolveCueAudioRefs(fileReader, cue, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    // scope id 使用 VFS 路径标识，避免导入调度继续暴露 provider URI。
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

        // M3U8 scope 排在 CUE 之后但仍早于启发式，保证播放列表声明的所有权优先于散落音频聚合。
        inventory.m3u8Files.forEach { m3u8 ->
            val audioRefs = resolveM3u8AudioRefs(fileReader, m3u8, audioLookup)
            manifestClaimedAudioIdentities.addAll(audioRefs.map { it.identity })
            scopes.add(
                ImportScope(
                    // M3U8 scope 同样使用 rootId/sourcePath 作为调度标识。
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

        // 只有未被任何清单引用的散落音频才进入目录音频 scope，避免 manifest 与 heuristic 重复争抢同一批音频。
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

    // CUE 解析只用于 scope 闭包构建；真正的元数据与章节解析仍由 ManifestParseStep 在导入流水线中完成。
    private suspend fun resolveCueAudioRefs(fileReader: VfsFileInterface, cue: FileRef, audioLookup: AudioLookup): List<FileRef> =
        runCatching {
            CueManifestParser.parse(displayName = cue.displayName, openStream = { fileReader.open(cue) })
                ?.referencedFiles
                .orEmpty()
                .mapNotNull { entry -> resolveAudioRef(cue.parentSourceKey, entry, audioLookup) }
                .distinctBy { it.identity }
                .sortedByStableFileKey()
        }.getOrDefault(emptyList())

    // M3U8 scope 闭包忽略远程 URL，只把能在当前 SAF 授权树中解析到的本地音频并入清单 scope。
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

    // 清单 scope 闭包只用扫描阶段已经拿到的同目录文件名索引，不再为了每个条目重新走 SAF findFile/listFiles。
    private fun resolveAudioRef(parentKey: String, entry: String, audioLookup: AudioLookup): FileRef? =
        audioLookup.findSameDirectory(parentKey, entry)

    // 将单个已关闭目录转换成局部 FileInventory；同级图片只服务当前目录的 sidecar 封面匹配。
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
            // 目录关闭事件里收集到的 txt 侧车同样按父目录打包进入局部 FileInventory，
            // 让 ManifestParseStep 能在不重新枚举目录的前提下，把简介解析收进 parser。
            textFilesByParent = if (textFiles.isNotEmpty()) {
                mapOf("${root.id}:$sourcePath" to textFiles.sortedByStableFileKey())
            } else {
                emptyMap()
            }
        )

    // 为 scope 构造局部 FileInventory，同时带上相关父目录的 sidecar 图片，保留封面匹配所需的目录上下文。
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
        // manifest scope 与目录音频 scope 都需要保留同父目录的 txt 侧车上下文，
        // 否则 parser 虽然被下沉了，也拿不到匹配简介所需的目录快照。
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

    // AudioLookup 预先按“VFS 父目录键 + 文件名”建立同目录索引，清单闭包可以 O(1) 命中本轮扫描已有 FileRef。
    private class AudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        // 这里复用 ManifestResolver.sameDirectoryFileName 的路径规整，只接受同级文件名，避免清单引用子目录时错误扩大 claim 范围。
        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        // 文件名索引用 Locale.ROOT 做大小写折叠，保留 Windows 用户常见的大小写不敏感体验，同时不受设备区域设置影响。
        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}
