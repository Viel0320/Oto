package com.viel.aplayer.media.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.media.PlaybackBufferPolicy
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackRootLookup
import com.viel.aplayer.media.VfsPlaybackUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ManualCachePlaybackDataSourceTest {
    @Test
    fun `manual cache hit should not open upstream`() {
        val fixture = CacheFixture()
        try {
            fixture.writeManualCache(byteArrayOf(1, 2, 3))
            val source = fixture.createSource(upstreamBytes = byteArrayOf(9), rootType = AudiobookSchema.LibrarySourceType.ABS)

            val bytes = source.readAll(fixture.dataSpec(length = 3L))

            assertEquals(listOf(1, 2, 3), bytes.toList().map { it.toInt() })
            assertEquals(0, fixture.upstreamOpenCount.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `direct playback uri should still read completed manual cache without upstream`() {
        val fixture = CacheFixture()
        try {
            fixture.writeManualCache(byteArrayOf(4, 5, 6))
            val source = fixture.createSource(upstreamBytes = byteArrayOf(9), rootType = AudiobookSchema.LibrarySourceType.ABS)

            val bytes = source.readAll(fixture.dataSpec(length = 3L, bufferPolicy = PlaybackBufferPolicy.Direct))

            assertEquals(listOf(4, 5, 6), bytes.toList().map { it.toInt() })
            assertEquals(0, fixture.upstreamOpenCount.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `remote cache miss should read upstream without writing manual cache`() {
        val fixture = CacheFixture()
        try {
            val source = fixture.createSource(upstreamBytes = byteArrayOf(7, 8), rootType = AudiobookSchema.LibrarySourceType.ABS)

            val bytes = source.readAll(fixture.dataSpec(length = 2L))

            assertEquals(listOf(7, 8), bytes.toList().map { it.toInt() })
            assertEquals(1, fixture.upstreamOpenCount.get())
            assertFalse(fixture.manualCache.isCached(FILE_ID, 0L, 2L))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `remote cache miss with unknown length should not write manual cache`() {
        val fixture = CacheFixture()
        try {
            val source = fixture.createSource(upstreamBytes = byteArrayOf(12, 13, 14), rootType = AudiobookSchema.LibrarySourceType.WEBDAV)

            val bytes = source.readAll(fixture.dataSpec(length = C.LENGTH_UNSET.toLong()))

            assertEquals(listOf(12, 13, 14), bytes.toList().map { it.toInt() })
            assertEquals(1, fixture.upstreamOpenCount.get())
            assertFalse(fixture.manualCache.isCached(FILE_ID, 0L, 3L))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `saf root should bypass manual cache`() {
        val fixture = CacheFixture()
        try {
            fixture.writeManualCache(byteArrayOf(1, 2))
            val source = fixture.createSource(upstreamBytes = byteArrayOf(10, 11), rootType = AudiobookSchema.LibrarySourceType.SAF)

            val bytes = source.readAll(fixture.dataSpec(length = 2L))

            assertEquals(listOf(10, 11), bytes.toList().map { it.toInt() })
            assertEquals(1, fixture.upstreamOpenCount.get())
        } finally {
            fixture.close()
        }
    }

    private class CacheFixture {
        private val context = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val manualCache: Cache = SimpleCache(
            context.cacheDir.resolve("manual-playback-${System.nanoTime()}"),
            NoOpCacheEvictor()
        )
        val upstreamOpenCount = AtomicInteger(0)

        fun createSource(
            upstreamBytes: ByteArray,
            rootType: AudiobookSchema.LibrarySourceType
        ): DataSource =
            ManualCachePlaybackDataSource(
                manualCache = manualCache,
                upstreamDataSourceFactory = CountingBytesDataSourceFactory(upstreamBytes, upstreamOpenCount),
                playbackFileLookup = StaticPlaybackFileLookup(testFile()),
                playbackRootLookup = StaticPlaybackRootLookup(testRoot(rootType))
            )

        fun writeManualCache(bytes: ByteArray) {
            val source = CacheDataSource.Factory()
                .setCache(manualCache)
                .setUpstreamDataSourceFactory(CountingBytesDataSourceFactory(bytes, AtomicInteger(0)))
                .setCacheKeyFactory { dataSpec ->
                    PlaybackCacheKeyPolicy.cacheKeyFor(dataSpec.uri, dataSpec.key)
                }
                .createDataSource()
            readAll(source, dataSpec(length = bytes.size.toLong()))
        }

        fun dataSpec(
            length: Long,
            bufferPolicy: PlaybackBufferPolicy = PlaybackBufferPolicy.Buffered
        ): DataSpec =
            DataSpec.Builder()
                .setUri(VfsPlaybackUri.fromBookFile(testFile(), bufferPolicy))
                .setPosition(0L)
                .setLength(length)
                .build()

        fun close() {
            runCatching { manualCache.release() }
        }

        private fun readAll(source: DataSource, dataSpec: DataSpec): ByteArray {
            source.open(dataSpec)
            return try {
                val buffer = ByteArray(16)
                val output = mutableListOf<Byte>()
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    repeat(read) { index -> output += buffer[index] }
                }
                output.toByteArray()
            } finally {
                source.close()
            }
        }
    }

    private class StaticPlaybackFileLookup(
        private val file: BookFileEntity
    ) : PlaybackFileLookup {
        override suspend fun getBookFileById(bookFileId: String): BookFileEntity = file
    }

    private class StaticPlaybackRootLookup(
        private val root: LibraryRootEntity
    ) : PlaybackRootLookup {
        override suspend fun getRootById(rootId: String): LibraryRootEntity = root
    }

    private class CountingBytesDataSourceFactory(
        private val bytes: ByteArray,
        private val openCount: AtomicInteger
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = CountingBytesDataSource(bytes, openCount)
    }

    private class CountingBytesDataSource(
        private val bytes: ByteArray,
        private val openCount: AtomicInteger
    ) : BaseDataSource(false) {
        private var position: Int = 0
        private var openedUri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            openCount.incrementAndGet()
            openedUri = dataSpec.uri
            position = dataSpec.position.toInt()
            return (bytes.size - position).coerceAtLeast(0).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val readLength = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + readLength)
            position += readLength
            return readLength
        }

        override fun getUri(): Uri? = openedUri

        override fun close() {
            openedUri = null
        }
    }

    private fun DataSource.readAll(dataSpec: DataSpec): ByteArray {
        open(dataSpec)
        return try {
            val buffer = ByteArray(16)
            val output = mutableListOf<Byte>()
            while (true) {
                val read = read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                repeat(read) { index -> output += buffer[index] }
            }
            output.toByteArray()
        } finally {
            close()
        }
    }

    private companion object {
        const val FILE_ID = "file-1"

        fun testFile(): BookFileEntity =
            BookFileEntity(
                id = FILE_ID,
                bookId = "book-1",
                rootId = "root-1",
                index = 0,
                sourcePath = "chapter.mp3",
                sourceIdentity = "chapter.mp3",
                displayName = "chapter.mp3",
                durationMs = 1_000L,
                fileSize = 2L,
                lastModified = 0L
            )

        fun testRoot(sourceType: AudiobookSchema.LibrarySourceType): LibraryRootEntity =
            LibraryRootEntity(
                id = "root-1",
                sourceType = sourceType,
                sourceUri = "https://example.test",
                displayName = "Root"
            )
    }
}
