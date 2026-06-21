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
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.media.VfsPlaybackDataSource
import com.viel.aplayer.media.cache.ManualCachePlaybackDataSource

/**
 * Constructs and configures highly optimized ExoPlayer media engine instances.
 * Manages low-level configuration of renderers, extractor options, buffer strategies (LoadControl), and virtual VFS data sources.
 * Decouples complex multi-track and custom focus logic from the playback service domain, adhering to Single Responsibility Principle.
 */
@UnstableApi
object ExoPlayerFactory {

    /**
     * Builds and configures an optimized ExoPlayer instance tailored for audiobook playback.
     * Keeps audio rendering on the native Media3 pipeline without exposing AudioSink internals.
     *
     * @param context Application runtime context
     * @param listener Global playback status and exception event observer
     * @param isAutomaticAudioFocusAllowed Controls whether ExoPlayer handles system audio focus tracking internally
     * @return Fully configured and initialized ExoPlayer kernel instance
     */
    @Suppress("DEPRECATION")
    fun createExoPlayer(
        context: Context,
        listener: Player.Listener,
        isAutomaticAudioFocusAllowed: Boolean
    ): ExoPlayer {

        val settings = AppSettingsRepository.getInstance(context.applicationContext).cachedSettings

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                SIZE_ONLY_BUFFER_DURATION_MS,
                SIZE_ONLY_BUFFER_DURATION_MS,
                1000,
                2000
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(settings.playbackBufferMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sonicProcessor = SonicAudioProcessor()

                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sonicProcessor))
                    .build()

                return sink
            }

            override fun buildMetadataRenderers(
                context: Context,
                output: MetadataOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
            }
        }.apply {
            setEnableDecoderFallback(true)
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        val downloadRuntimeDependencies = APlayerApplication.getDownloadRuntimeDependencies(context)
        val vfsPlaybackDependencies = APlayerApplication.getVfsPlaybackDependencies(context)
        val manualCacheDataSourceFactory = ManualCachePlaybackDataSource.Factory(
            manualCache = downloadRuntimeDependencies.downloadCacheAccess.manualCache,
            upstreamDataSourceFactory = VfsPlaybackDataSource.Factory(context),
            playbackFileLookup = vfsPlaybackDependencies.playbackFileLookup,
            playbackRootLookup = vfsPlaybackDependencies.playbackRootLookup
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(manualCacheDataSourceFactory, extractorsFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                isAutomaticAudioFocusAllowed
            )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(20000)
            .build()
            .apply {
                addListener(listener)
            }
    }

    private const val SIZE_ONLY_BUFFER_DURATION_MS = Int.MAX_VALUE
}
