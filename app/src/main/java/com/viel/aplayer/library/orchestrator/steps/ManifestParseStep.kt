package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.media.manifest.ManifestResolver
import com.viel.aplayer.media.manifest.ManifestSidecarSupport
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.vfs.VfsFileReader
import java.util.Locale

/**
 * 清单解析工位
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本类专门独立出来解析 CUE 和 M3U8 清单物理文件。
 * 它调用现有的 CueManifestParser 和 M3u8ManifestParser 算法解析出轨道和引用的音频文件名，
 * 并通过本轮扫描得到的同目录音频索引验证引用的音频是否真的物理存在，避免清单解析阶段重复访问 SAF。
 */
internal class ManifestParseStep(private val context: Context) : ImportStep<FileInventory, ManifestParsedResult> {

    override val stepName: String = "ManifestParseStep"

    override suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): StepResult<ManifestParsedResult> = runCatching {
        // 为每一次改动添加详尽的中文注释：清单解析使用当前 scope 的 roots 映射打开 VFS 流，避免无 DAO 的 reader 解析不到库根。
        val fileReader = VfsFileReader(this.context.applicationContext, rootsById = input.roots.associateBy { it.id })
        val cueDrafts = mutableListOf<ParsedCueDraft>()
        val m3u8Drafts = mutableListOf<ParsedM3u8Draft>()
        // 详尽的中文注释：清单解析阶段复用扫描阶段已经收集到的音频列表，按“VFS 父目录键 + 文件名”查找，避免每个 CUE/M3U8 条目都触发目录枚举。
        val audioLookup = ManifestAudioLookup(input.audioFiles)

        // 1. 扫描并独立解析所有的 CUE 清单
        input.cueFiles.forEach { cue ->
            CueManifestParser.parse(
                displayName = cue.displayName,
                openStream = { fileReader.open(cue) },
                manifestFile = cue,
                // 为每一次改动添加详尽的中文注释：当前 scope 已经带上同目录图片和 txt 侧车，
                // ManifestParseStep 只负责把这些目录快照下发给 parser，由 parser 内部统一裁决。
                directoryContext = directoryContextFor(input, cue.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )?.let { result ->
                // 详尽的中文注释：CUE 只解析同目录音频文件名，直接命中扫描快照里的 FileRef，避免同一个清单反复枚举父目录。
                val referencedFiles = result.referencedFiles.distinct()
                val resolved = referencedFiles.mapNotNull { entry ->
                    audioLookup.findSameDirectory(cue.parentSourceKey, entry)?.let { file ->
                        entry to file.vfsKey
                    }
                }
                // 统计丢失的文件数
                val missingCount = referencedFiles.count { entry ->
                    resolved.none { it.first == entry } && isAudioName(entry)
                }
                
                cueDrafts.add(ParsedCueDraft(
                    sourceFile = cue,
                    result = result,
                    resolvedAudioKeys = resolved.toMap(),
                    missingCount = missingCount
                ))
            }
        }

        // 2. 扫描并独立解析所有的 M3U8 播放列表清单
        input.m3u8Files.forEach { m3u8 ->
            // 详尽的中文注释：M3u8ManifestParser.parse 当前返回非空结果，直接接收可以避免无意义的安全调用并保持编译输出干净。
            val result = M3u8ManifestParser.parse(
                displayName = m3u8.displayName,
                openStream = { fileReader.open(m3u8) },
                manifestFile = m3u8,
                directoryContext = directoryContextFor(input, m3u8.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )
            val items = result.items
            val distinctItems = items.distinctBy { it.uri }
            // 详尽的中文注释：M3U8 仍跳过远程 URL，本地条目则只在扫描快照里按同目录文件名匹配，不再进入 SAF 递归解析。
            val resolved = distinctItems.mapNotNull { item ->
                if (item.uri.startsWith("http://", true) || item.uri.startsWith("https://", true)) return@mapNotNull null
                audioLookup.findSameDirectory(m3u8.parentSourceKey, item.uri)?.let { file ->
                    item to file.vfsKey
                }
            }
            val missingCount = distinctItems.count { item ->
                resolved.none { it.first.uri == item.uri } && isAudioName(item.uri)
            }

            m3u8Drafts.add(ParsedM3u8Draft(
                sourceFile = m3u8,
                result = result,
                resolvedAudioKeys = resolved.associate { it.first.uri to it.second },
                missingCount = missingCount
            ))
        }

        StepResult.Success(ManifestParsedResult(cueDrafts, m3u8Drafts))
    }.getOrElse { e ->
        StepResult.Failure(e, "清单文件深度物理解析异常，详情: ${e.localizedMessage}")
    }

    private fun isAudioName(value: String): Boolean {
        // 中文注释：清单引用的 mp4 同样按音频资产处理，避免 CUE/M3U8 中的 mp4 轨道被误计为缺失。
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
    }

    private fun directoryContextFor(input: FileInventory, parentKey: String): ManifestSidecarSupport.DirectoryContext =
        ManifestSidecarSupport.DirectoryContext(
            imageFiles = input.imageFilesByParent[parentKey].orEmpty(),
            // 为每一次改动添加详尽的中文注释：txt 侧车和图片侧车一样从当前 scope inventory 直接取，
            // 避免 manifest parser 再通过 VFS 去枚举目录。
            textFiles = input.textFilesByParent[parentKey].orEmpty()
        )

    // 详尽的中文注释：清单解析专用的轻量索引只保存当前 scope 内音频，保证同目录清单不会越界引用到其他目录的同名文件。
    private class ManifestAudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        // 详尽的中文注释：路径规整委托给 ManifestResolver.sameDirectoryFileName，确保清单闭包构建和正式解析使用完全相同的同级目录规则。
        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        // 详尽的中文注释：索引键使用 Locale.ROOT 做大小写折叠，避免设备语言环境影响大小写不敏感匹配结果。
        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}

/**
 * 承载清单解析输出的实体类
 */
internal data class ManifestParsedResult(
    val cueDrafts: List<ParsedCueDraft>,
    val m3u8Drafts: List<ParsedM3u8Draft>
)

internal data class ParsedCueDraft(
    val sourceFile: FileRef,
    val result: CueManifestParser.CueResult,
    // 为每一次改动添加详尽的中文注释：清单条目解析结果映射到 VFS 文件键，不再映射到 provider URI。
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)

internal data class ParsedM3u8Draft(
    val sourceFile: FileRef,
    val result: M3u8ManifestParser.M3u8Result,
    // 为每一次改动添加详尽的中文注释：M3U8 条目同样只输出 VFS 文件键，供后续 claim、章节和封面步骤复用。
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)
