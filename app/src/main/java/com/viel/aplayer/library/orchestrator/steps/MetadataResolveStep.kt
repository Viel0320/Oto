package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.library.orchestrator.mapWithBoundedConcurrency
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.media.parser.MetadataResolver

/**
 * 媒体元数据 ID3 并发解析步骤物理类。
 * 
 * 本类已被重构，去除了原有的泛型接口 ImportStep<I, O> 和 StepResult 密封类包装。
 * 现在 execute 方法直接返回具体的 ResolvedMetadataDrafts 结果，遇到异常自然向上抛出。
 */
// 声明步骤类可见性为 internal，收紧本模块内部可见，彻底防止由于对外暴露 internal 音频实体类型而报的类型泄漏错误

@UnstableApi
internal class MetadataResolveStep(
    private val context: Context,
    // 强制从外部注入由 VfsFileInterface 初始化的 MetadataResolver 实例
    private val metadataResolver: MetadataResolver
) {

    /**
     * 执行元数据解析。并发对每个散落音频提取元数据，直接返回 ResolvedMetadataDrafts 结果，不再包装在 StepResult 中。
     */
    suspend fun execute(
        input: ManifestParsedResult,
        context: ImportContext
    ): ResolvedMetadataDrafts {
        // 1. 将 CUE 声明的关联音轨 identity 全数录入 reserved 预占用账本
        // 清单预占用按 VFS 文件键回查扫描快照，不再用 provider URI 做关联。
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
            // 散落音频元数据提取通过 VFS 文件引用打开，不再把扫描期文件转换成 URI 入口。
            // 散落音频导入阶段显式请求“元数据+内嵌封面”，让 MP4 covr 随 AudioMetadataRef 传到后续封面缓存写入点。
            val extracted = metadataResolver.extractWithEmbeddedCover(audio)
            AudioMetadataRef(audio, extracted.metadata, extracted.embeddedCover)
        }

        return ResolvedMetadataDrafts(
            manifestParsedResult = input,
            looseAudioMetadataRefs = resolvedList
        )
    }
}

/**
 * 承载元数据提取输出的实体类
 *
 * 使用 internal 关键字收紧可见性，防止由于对外暴露 internal 实参类而引起的编译报错
 */
internal data class ResolvedMetadataDrafts(
    // 往下透传清单解析数据，为最终 Draft 汇总保留上下文
    val manifestParsedResult: ManifestParsedResult,
    
    // 包含元数据信息的散落音频列表
    val looseAudioMetadataRefs: List<AudioMetadataRef>
)
