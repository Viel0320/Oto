package com.viel.aplayer.media.service

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.viel.aplayer.media.VfsPlaybackDataSource

/**
 * ExoPlayer 媒体播放内核生产构建工厂。
 * 专门负责管理 ExoPlayer 底层渲染器工厂、多媒体文件提取器工厂、全局加载控制策略（LoadControl）以及虚拟数据源的模块化装配与创建。
 * 将高耦合、超长段定制化参数配置代码从 PlaybackService 中彻底抽离，符合单一职责原则（SRP）。
 */
@UnstableApi
object ExoPlayerFactory {

    /**
     * 定义一个内部音频槽创建监听接口，用于解耦 AudioSink 创建后的处理器提取行为。
     */
    interface AudioSinkCreationListener {
        /**
         * 当底层 AudioSink 创建并初始化完成后触发。
         *
         * @param sink 生成的物理 DefaultAudioSink 实例
         * @param sonicProcessor 被捆绑挂载的倍速音频处理器
         */
        fun onAudioSinkCreated(sink: AudioSink, sonicProcessor: SonicAudioProcessor)
    }

    /**
     * 模块化配置并生成一个专用于有声书播放的高度优化的 ExoPlayer 内核实例。
     *
     * @param context 运行期上下文环境
     * @param listener 播放状态及异常发生监听器
     * @param isAutomaticAudioFocusAllowed 是否允许系统播放器内部自动处理音频焦点（关闭“通知避让”时为 true）
     * @param sinkListener 底层音频渲染器 AudioSink 创建 of 反射与连接侦听回调
     * @return 完美装配的 ExoPlayer 全能实例
     */
    @Suppress("DEPRECATION")
    fun createExoPlayer(
        context: Context,
        listener: Player.Listener,
        isAutomaticAudioFocusAllowed: Boolean,
        sinkListener: AudioSinkCreationListener
    ): ExoPlayer {
        
        // 1. 配置前台极致秒开与缓存防卡顿缓冲区控制参数 (DefaultLoadControl)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000, // 最小缓冲维持时长（30秒），确保网络不稳定时有足够的冗余缓存
                30000, // 最大缓冲上限（30秒），防止无限制读取大文件挤爆 JVM 内存
                1000,  // 起播所需最小缓冲时长（1秒），实现极致开播秒开
                2000   // 卡顿重缓冲恢复所需最小缓冲时长（2秒），快速自愈且防反复起止
            )
            .build()

        // 2. 定制多媒体物理渲染器工厂，实现无元数据零 I/O 解析及软解自愈支持
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // 实例化用于动态调整有声书倍速播放的 Sonic 处理器，common.audio 包路径
                val sonicProcessor = SonicAudioProcessor()
                
                // 将 Sonic 处理器强制捆绑塞入 DefaultAudioSink 渲染链中
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sonicProcessor))
                    .build()
                
                // 触发解耦回调，将生成的物理 sink 和倍速处理器暴露给外部，由控制组件执行反射提取与时长热更
                sinkListener.onAudioSinkCreated(sink, sonicProcessor)
                
                return sink
            }

            override fun buildMetadataRenderers(
                context: Context,
                output: MetadataOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // 重写置空，强制禁止 ExoPlayer 加载和请求多媒体容器的元数据音轨
                // 从根本上实现外置字幕单向加载及有声书内置标签零 I/O 资源消耗
            }
        }.apply {
            // 允许在系统底层硬解组件失败时无缝退化到软解，增强各种冷门格式播放的稳定性
            setEnableDecoderFallback(true)
        }

        // 3. 配置长有声书内存映射优化的多媒体文件提取器 (DefaultExtractorsFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            // 开启 MP3 索引寻轨并显式禁用内置 ID3 元数据加载以规避磁盘二次摩擦
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
            // 开启 ADTS 格式的恒定码率快速寻轨
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        // 4. 将提取器工厂与专用于虚拟文件系统寻轨的 VfsPlaybackDataSource 工厂绑定
        val mediaSourceFactory = DefaultMediaSourceFactory(VfsPlaybackDataSource.Factory(context), extractorsFactory)

        // 5. 组装并初始化最终的 ExoPlayer 内核对象
        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH) // 声音类型设置为语音（SPEECH）以提供最适合有声书朗读的混音特性
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                isAutomaticAudioFocusAllowed // 根据通知避让状态控制是否允许播放器自己处理系统音频焦点
            )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)   // 系统硬性设定双击快退 10 秒
            .setSeekForwardIncrementMs(30000)  // 系统硬性设定双击快进 30 秒
            .build()
            .apply {
                // 挂载全局唯一的运行状态及错误监听器
                addListener(listener)
            }
    }
}
