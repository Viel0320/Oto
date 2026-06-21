package com.viel.aplayer.application.di.graph

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.di.graph.DataGraph
import com.viel.aplayer.di.graph.DownloadGraph
import com.viel.aplayer.di.graph.MediaGraph
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

            assertTrue(cacheAccess.manualCache.cacheSpace >= 0L)
            assertFalse(graph.isDownloadRuntimeInitialized)
        } finally {
            graph.close()
            media.close()
        }
    }

    @Test
    fun `download manager should receive raw vfs upstream instead of nested manual cache data source`() {
        val source = resolveSourceFile("di/graph/DownloadGraph.kt").readText()
        val createDownloadManagerBody = source
            .substringAfter("private fun createDownloadManager")
            .substringBefore("override fun close")

        assertTrue(createDownloadManagerBody.contains("VfsPlaybackDataSource.Factory(appContext)"))
        assertFalse(createDownloadManagerBody.contains("CacheDataSource.Factory"))
    }

    @Test
    fun `download graph should not create playback disk cache`() {
        val source = resolveSourceFile("di/graph/DownloadGraph.kt").readText()

        assertFalse(source.contains("PLAYBACK_CACHE_DIRECTORY"))
        assertFalse(source.contains("Threshold" + "CacheEvictor"))
    }

    private fun resolveSourceFile(path: String): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer/$path"),
            File("app/src/main/java/com/viel/aplayer/$path")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate source file: $path")
    }
}
