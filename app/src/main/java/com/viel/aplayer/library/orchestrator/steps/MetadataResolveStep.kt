package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.AudioMetadataRef
import com.viel.aplayer.library.mapWithBoundedConcurrency
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.media.parse.MetadataExtractor

/**
 * 媒体元数据 ID3 并发解析步骤物理类
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本工位从上一工位解析的清单数据入手，过滤掉已被 CUE/M3U8 认领的物理音频，
 * 剩余的散落音频文件则通过 MetadataExtractor 提取内嵌的 ID3 标签。
 * 这里使用有界并发读取元数据，但返回结果保持原文件顺序，避免启发式聚合顺序漂移。
 */
// 详尽的中文注释：声明步骤类可见性为 internal，收紧本模块内部可见，彻底防止由于对外暴露 internal 音频实体类型而报的类型泄漏错误
internal class MetadataResolveStep(
    private val context: Context,
    private val metadataExtractor: MetadataExtractor = MetadataExtractor(context)
) : ImportStep<ManifestParsedResult, ResolvedMetadataDrafts> {

    override val stepName: String = "MetadataResolveStep"

    override suspend fun execute(
        input: ManifestParsedResult,
        context: ImportContext
    ): StepResult<ResolvedMetadataDrafts> = runCatching {
        // 1. 将 CUE 声明的关联音轨 identity 全数录入 reserved 预占用账本
        // 为每一次改动添加详尽的中文注释：清单预占用按 VFS 文件键回查扫描快照，不再用 provider URI 做关联。
        val audioByVfsKey = context.sharedInventory?.audioFiles.orEmpty().associateBy { it.vfsKey }
        input.cueDrafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        // 2. 将 M3U8 声明的关联音轨 identity 全数录入 reserved 预占用账本
        input.m3u8Drafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        // 3. 过滤出没有被清单文件占用，且在当前数据库中还未被认领的物理音频文件
        val allAudios = context.sharedInventory?.audioFiles.orEmpty()
        val looseAudios = allAudios.filter { audio ->
            !context.reservedAudioIdentities.contains(audio.identity) &&
                    !context.existingClaimIndex.has(audio.identity)
        }

        // 4. 对每一个散落音频并发提取 ID3 元数据；mapWithBoundedConcurrency 会保持输入顺序，claim 和聚合仍由后续步骤串行裁决。
        val resolvedList = looseAudios.mapWithBoundedConcurrency { audio ->
            // 为每一次改动添加详尽的中文注释：散落音频元数据提取通过 VFS 文件引用打开，不再把扫描期文件转换成 URI 入口。
            AudioMetadataRef(audio, metadataExtractor.extract(audio))
        }

        StepResult.Success(ResolvedMetadataDrafts(
            manifestParsedResult = input,
            looseAudioMetadataRefs = resolvedList
        ))
    }.getOrElse { e ->
        StepResult.Failure(e, "散落音频 ID3 元数据读取发生故障，详情: ${e.localizedMessage}")
    }
}

/**
 * 承载元数据提取输出的实体类
 *
 * 详尽的中文注释：使用 internal 关键字收紧可见性，防止由于对外暴露 internal 实参类而引起的编译报错
 */
internal data class ResolvedMetadataDrafts(
    // 往下透传清单解析数据，为最终 Draft 汇总保留上下文
    val manifestParsedResult: ManifestParsedResult,
    
    // 包含元数据信息的散落音频列表
    val looseAudioMetadataRefs: List<AudioMetadataRef>
)
