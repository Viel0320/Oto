package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.net.Uri
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.manifest.CueManifestParser
import com.viel.aplayer.library.manifest.M3u8ManifestParser
import com.viel.aplayer.library.manifest.ManifestResolver
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 清单解析工位
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本类专门独立出来解析 CUE 和 M3U8 清单物理文件。
 * 它调用现有的 CueManifestParser 和 M3u8ManifestParser 算法解析出轨道和引用的音频文件名，
 * 并在本地磁盘文件系统中通过 ManifestResolver.resolveRelativePath 验证引用的音频是否真的物理存在。
 */
internal class ManifestParseStep(private val context: Context) : ImportStep<FileInventory, ManifestParsedResult> {

    override val stepName: String = "ManifestParseStep"

    override suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): StepResult<ManifestParsedResult> = runCatching {
        val cueDrafts = mutableListOf<ParsedCueDraft>()
        val m3u8Drafts = mutableListOf<ParsedM3u8Draft>()

        // 1. 扫描并独立解析所有的 CUE 清单
        input.cueFiles.forEach { cue ->
            CueManifestParser.parse(this.context, cue.documentFile)?.let { result ->
                // 解析清单中声明的关联音轨路径，并验证它们是否在本地存在
                val resolved = result.referencedFiles.distinct().mapNotNull { entry ->
                    ManifestResolver.resolveRelativePath(cue.parentDocumentFile, entry)?.let { file ->
                        entry to file.uri.toString()
                    }
                }
                // 统计丢失的文件数
                val missingCount = result.referencedFiles.distinct().count { entry ->
                    resolved.none { it.first == entry } && isAudioName(entry)
                }
                
                cueDrafts.add(ParsedCueDraft(
                    sourceFile = cue,
                    result = result,
                    resolvedAudioUris = resolved.toMap(),
                    missingCount = missingCount
                ))
            }
        }

        // 2. 扫描并独立解析所有的 M3U8 播放列表清单
        input.m3u8Files.forEach { m3u8 ->
            M3u8ManifestParser.parse(this.context, m3u8.documentFile)?.let { result ->
                val items = result.items
                // 过滤解析相对路径
                val resolved = items.distinctBy { it.uri }.mapNotNull { item ->
                    if (item.uri.startsWith("http://", true) || item.uri.startsWith("https://", true)) return@mapNotNull null
                    ManifestResolver.resolveRelativePath(m3u8.parentDocumentFile, item.uri)?.let { file ->
                        item to file.uri.toString()
                    }
                }
                val missingCount = items.distinctBy { it.uri }.count { item ->
                    resolved.none { it.first.uri == item.uri } && isAudioName(item.uri)
                }

                m3u8Drafts.add(ParsedM3u8Draft(
                    sourceFile = m3u8,
                    result = result,
                    resolvedAudioUris = resolved.map { it.first.uri to it.second }.toMap(),
                    missingCount = missingCount
                ))
            }
        }

        StepResult.Success(ManifestParsedResult(cueDrafts, m3u8Drafts))
    }.getOrElse { e ->
        StepResult.Failure(e, "清单文件深度物理解析异常，详情: ${e.localizedMessage}")
    }

    private fun isAudioName(value: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
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
    val resolvedAudioUris: Map<String, String>,
    val missingCount: Int
)

internal data class ParsedM3u8Draft(
    val sourceFile: FileRef,
    val result: M3u8ManifestParser.M3u8Result,
    val resolvedAudioUris: Map<String, String>,
    val missingCount: Int
)