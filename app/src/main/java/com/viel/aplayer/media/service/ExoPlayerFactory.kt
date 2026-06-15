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
 * ExoPlayer Core Production Factory (Constructs and configures highly optimized ExoPlayer media engine instances)
 * Manages low-level configuration of renderers, extractor options, buffer strategies (LoadControl), and virtual VFS data sources.
 * Decouples complex multi-track and custom focus logic from the playback service domain, adhering to Single Responsibility Principle.
 */
@UnstableApi
object ExoPlayerFactory {

    /**
     * Modular Audio Player Factory (Builds and configures an optimized ExoPlayer instance tailored for audiobook playback)
     * Refactoring completely removed the AudioSinkCreationListener reflection callbacks, returning to native Media3 architecture.
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

        // 1. Buffer Configuration (Caps remote playback buffering by memory size only)
        // The removed playback disk cache is replaced by ExoPlayer's RAM buffer, and loading stops when the target byte budget is reached.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                SIZE_ONLY_BUFFER_DURATION_MS, // Effective no-op duration floor because byte budget owns the loading stop condition
                SIZE_ONLY_BUFFER_DURATION_MS, // Effective no-op duration ceiling so long low-bitrate books are not capped by seconds
                1000,  // Playback start buffer (1 second) to achieve rapid low-latency initial startup
                2000   // Rebuffer recovery threshold (2 seconds) to recover quickly and prevent rapid play/pause loops
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(settings.playbackBufferMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .build()

        // 2. Renderer Customization (Builds a specialized renderers factory to disable container metadata I/O and support decoder fallback)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                /**
                 * Sonic Processor Initialization (Instantiates the audio speed processor for dynamic playback rate adjustments)
                 * Refactored to completely remove reflection callbacks exposing internal AudioSink settings to external components,
                 * ensuring stability by adhering strictly to the official Media3 audio rendering pipeline.
                 */
                val sonicProcessor = SonicAudioProcessor()
                
                // Registers the Sonic processor within the default AudioSink rendering chain
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
                // Suppresses standard metadata track creation, preventing ExoPlayer from parsing container-level metadata
                // Optimizes performance by avoiding redundant file I/O operations for built-in container tags
            }
        }.apply {
            // Enables automatic fallback to software decoders if hardware decoders fail, enhancing format compatibility
            setEnableDecoderFallback(true)
        }

        // 3. Extractor Options (Configures media extractors optimized for long-duration audiobooks)
        val extractorsFactory = DefaultExtractorsFactory()
            // Enables MP3 frame indexing for fast seeking and disables container ID3 extraction to prevent redundant disk reads
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
            // Enables fast constant-bitrate seeking for ADTS format files
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        // 4. Data Source Binding (Route playback through L1 manual cache before streaming from VFS)
        // Uncached remote playback now falls back to upstream reads only; LoadControl owns the in-memory buffer and no disk cache is written.
        val downloadRuntimeDependencies = APlayerApplication.getDownloadRuntimeDependencies(context)
        val vfsPlaybackDependencies = APlayerApplication.getVfsPlaybackDependencies(context)
        val manualCacheDataSourceFactory = ManualCachePlaybackDataSource.Factory(
            manualCache = downloadRuntimeDependencies.downloadCacheAccess.manualCache,
            upstreamDataSourceFactory = VfsPlaybackDataSource.Factory(context),
            playbackFileLookup = vfsPlaybackDependencies.playbackFileLookup,
            playbackRootLookup = vfsPlaybackDependencies.playbackRootLookup
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(manualCacheDataSourceFactory, extractorsFactory)

        // 5. Player Assembly (Builds and returns the fully assembled ExoPlayer core instance)
        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH) // Sets the audio type to SPEECH for optimal mix attributes during audiobook narration
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                isAutomaticAudioFocusAllowed // Controls whether ExoPlayer handles system audio focus dynamically based on notification settings
            )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            // Default Short Seek Increments (Match AppSettings fallback values before DataStore emits)
            // PlaybackService hot-reloads user-selected values after startup, while the factory keeps first-frame behavior aligned with product defaults.
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(20000)
            .build()
            .apply {
                // Attaches the global lifecycle and error state event listener
                addListener(listener)
            }
    }

    private const val SIZE_ONLY_BUFFER_DURATION_MS = Int.MAX_VALUE
}
