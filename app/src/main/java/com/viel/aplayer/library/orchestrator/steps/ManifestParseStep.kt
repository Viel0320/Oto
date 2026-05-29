package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.media.manifest.ManifestResolver
import com.viel.aplayer.media.manifest.ManifestSidecarSupport
import java.util.Locale

/**
 * 清单解析工位。本类已被重构，去除了原有的泛型接口 ImportStep<I, O> 和 StepResult 密封类包装。
 * 现在 execute 方法直接返回具体的 ManifestParsedResult 结果，当遇到异常时将自然向上抛出，
 * 简化了调用链路的错误处理并消除了过度设计。
 */
internal class ManifestParseStep(private val context: Context) {

    /**
     * 执行清单解析逻辑。直接接收 FileInventory 输入并返回 ManifestParsedResult，不再包装在 StepResult 中。
     */
    suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): ManifestParsedResult {
        // 复用从 ImportContext 传入的会话级唯一 VFS 读取门面，规避冗余自构开销
        val fileReader = context.scopeFileReader ?: return ManifestParsedResult(emptyList(), emptyList())
        val cueDrafts = mutableListOf<ParsedCueDraft>()
        val m3u8Drafts = mutableListOf<ParsedM3u8Draft>()
        // 清单解析阶段复用扫描阶段已经收集到的音频列表，按“VFS 父目录键 + 文件名”查找，避免每个 CUE/M3U8 条目都触发目录枚举。
        val audioLookup = ManifestAudioLookup(input.audioFiles)

        // 1. 扫描并独立解析所有的 CUE 清单
        input.cueFiles.forEach { cue ->
            CueManifestParser.parse(
                displayName = cue.displayName,
                openStream = { fileReader.open(cue) },
                manifestFile = cue,
                // 当前 scope 已经带上同目录图片和 txt 侧车，
                // ManifestParseStep 只负责把这些目录快照下发给 parser，由 parser 内部统一裁决。
                directoryContext = directoryContextFor(input, cue.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )?.let { result ->
                // CUE 只解析同目录音频文件名，直接命中扫描快照里的 FileRef，避免同一个清单反复枚举父目录。
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
            // M3u8ManifestParser.parse 当前返回非空结果，直接接收可以避免无意义的安全调用并保持编译输出干净。
            val result = M3u8ManifestParser.parse(
                displayName = m3u8.displayName,
                openStream = { fileReader.open(m3u8) },
                manifestFile = m3u8,
                directoryContext = directoryContextFor(input, m3u8.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )
            val items = result.items
            val distinctItems = items.distinctBy { it.uri }
            // M3U8 仍跳过远程 URL，本地条目则只在扫描快照里按同目录文件名匹配，不再进入 SAF 递归解析。
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

        return ManifestParsedResult(cueDrafts, m3u8Drafts)
    }

    private fun isAudioName(value: String): Boolean {
        // 清单引用的 mp4 同样按音频资产处理，避免 CUE/M3U8 中的 mp4 轨道被误计为缺失。
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
    }

    private fun directoryContextFor(input: FileInventory, parentKey: String): ManifestSidecarSupport.DirectoryContext =
        ManifestSidecarSupport.DirectoryContext(
            imageFiles = input.imageFilesByParent[parentKey].orEmpty(),
            // txt 侧车和图片侧车一样从当前 scope inventory 直接取，
            // 避免 manifest parser 再通过 VFS 去枚举目录。
            textFiles = input.textFilesByParent[parentKey].orEmpty()
        )

    // 清单解析专用的轻量索引只保存当前 scope 内音频，保证同目录清单不会越界引用到其他目录的同名文件。
    private class ManifestAudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        // 路径规整委托给 ManifestResolver.sameDirectoryFileName，确保清单闭包构建和正式解析使用完全相同的同级目录规则。
        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        // 索引键使用 Locale.ROOT 做大小写折叠，避免设备语言环境影响大小写不敏感匹配结果。
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
    // 清单条目解析结果映射到 VFS 文件键，不再映射到 provider URI。
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)

internal data class ParsedM3u8Draft(
    val sourceFile: FileRef,
    val result: M3u8ManifestParser.M3u8Result,
    // M3U8 条目同样只输出 VFS 文件键，供后续 claim、章节和封面步骤复用。
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)
