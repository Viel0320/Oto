package com.viel.aplayer.application.di.graph

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DownloadGraphTest {
    @Test
    fun `download cache access should not initialize download manager runtime`() {
        val context = RuntimeEnvironment.getApplication()
        val data = DataGraph(context)
        val media = MediaGraph(context, data)
        val graph = DownloadGraph(context, data, media)

        try {
            val cacheAccess = graph.downloadCacheAccess

            // Manual Cache Lazy Boundary (Playback may resolve L1 manual cache without starting DownloadManager)
            // Remote playback now relies on memory buffering, so this access must not construct disk buffering or observers.
            assertTrue(cacheAccess.manualCache.cacheSpace >= 0L)
            assertFalse(graph.isDownloadRuntimeInitializedForTests())
        } finally {
            graph.close()
            media.close()
        }
    }

    @Test
    fun `download manager should receive raw vfs upstream instead of nested manual cache data source`() {
        val source = File("src/main/java/com/viel/aplayer/application/di/graph/DownloadGraph.kt").readText()
        val createDownloadManagerBody = source
            .substringAfter("private fun createDownloadManager")
            .substringBefore("override fun close")

        // Download Manager Cache Ownership (Prevent double-wrapping the same manual cache)
        // DownloadManager already receives manualCache directly, so passing a CacheDataSource as its upstream can block real downloads while tests only see queued metadata.
        assertTrue(createDownloadManagerBody.contains("VfsPlaybackDataSource.Factory(appContext)"))
        assertFalse(createDownloadManagerBody.contains("CacheDataSource.Factory"))
    }

    @Test
    fun `download graph should not create playback disk cache`() {
        val source = File("src/main/java/com/viel/aplayer/application/di/graph/DownloadGraph.kt").readText()

        // Removed Disk Buffer Guard (Keep playback buffering in ExoPlayer memory instead of DownloadGraph storage)
        // The graph may own manual cache for explicit downloads, but no playback-cache directory or evictor symbol should be present.
        assertFalse(source.contains("PLAYBACK_CACHE_DIRECTORY"))
        assertFalse(source.contains("Threshold" + "CacheEvictor"))
    }
}
