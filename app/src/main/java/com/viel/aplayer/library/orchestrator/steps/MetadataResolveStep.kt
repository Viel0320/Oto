package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.core.net.toUri
import com.viel.aplayer.library.AudioMetadataRef
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
 * 这里采用对初学者友好的同步循环读取，如果需要性能提升，可以扩展为协程并发。
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
        input.cueDrafts.forEach { draft ->
            draft.resolvedAudioUris.values.forEach { uri ->
                val audioRef = context.sharedInventory?.audioFiles?.firstOrNull { it.uri == uri }
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        // 2. 将 M3U8 声明的关联音轨 identity 全数录入 reserved 预占用账本
        input.m3u8Drafts.forEach { draft ->
            draft.resolvedAudioUris.values.forEach { uri ->
                val audioRef = context.sharedInventory?.audioFiles?.firstOrNull { it.uri == uri }
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

        // 4. 对每一个散落音频，逐个提取 ID3 元数据
        val resolvedList = mutableListOf<AudioMetadataRef>()
        looseAudios.forEach { audio ->
            val metadata = metadataExtractor.extract(audio.uri.toUri())
            resolvedList.add(AudioMetadataRef(audio, metadata))
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