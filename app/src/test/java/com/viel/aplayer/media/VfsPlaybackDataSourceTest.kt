package com.viel.aplayer.media

import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.vfs.VfsPlaybackStreamReader
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class VfsPlaybackDataSourceTest {
    @Test
    fun `open cancellation interrupts the Media3 loader thread instead of the watcher thread`() {
        val enteredOpen = CountDownLatch(1)
        val lookup = StaticPlaybackFileLookup(testFile())
        val reader = object : VfsPlaybackStreamReader {
            override suspend fun openPlaybackStream(file: BookFileEntity, offset: Long): java.io.InputStream? {
                enteredOpen.countDown()
                while (true) {
                    delay(100)
                }
            }
        }
        val errorRef = AtomicReference<Throwable?>()
        val source = VfsPlaybackDataSource(lookup, reader)
        val loaderThread = Thread {
            try {
                source.open(dataSpec())
            } catch (error: Throwable) {
                errorRef.set(error)
            }
        }

        loaderThread.start()
        assertTrue(enteredOpen.await(2, TimeUnit.SECONDS))
        loaderThread.interrupt()
        loaderThread.join(2_000L)

        assertTrue("loader thread should exit after interruption", !loaderThread.isAlive)
        assertTrue(errorRef.get() is InterruptedIOException)
    }

    @Test
    fun `open maps provider cancellation IOException to InterruptedIOException`() {
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile()),
            fileReader = ThrowingPlaybackStreamReader(
                IOException("socket canceled", InterruptedIOException("call interrupted"))
            )
        )

        val error = runCatching { source.open(dataSpec()) }.exceptionOrNull()

        assertTrue(error is InterruptedIOException)
    }

    @Test
    fun `open maps ABS timeout to network timeout playback error`() {
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile()),
            fileReader = ThrowingPlaybackStreamReader(
                AbsApiError(
                    code = "TIMEOUT",
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                    message = "ABS timeout"
                )
            )
        )

        val error = runCatching { source.open(dataSpec()) }.exceptionOrNull() as DataSourceException

        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, error.reason)
    }

    @Test
    fun `open maps ranged not found to read position out of range`() {
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile()),
            fileReader = ThrowingPlaybackStreamReader(
                AbsApiError(
                    code = "RANGE_NOT_SATISFIABLE",
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    message = "ABS range was out of bounds"
                )
            )
        )

        val error = runCatching { source.open(dataSpec(position = 128L)) }.exceptionOrNull() as DataSourceException

        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun `read maps stream cancellation to InterruptedIOException`() {
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile(fileSize = 4L)),
            fileReader = StaticPlaybackStreamReader(
                object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        throw InterruptedIOException("read interrupted")
                    }
                }
            )
        )
        source.open(dataSpec())

        val error = runCatching { source.read(ByteArray(4), 0, 4) }.exceptionOrNull()

        assertTrue(error is InterruptedIOException)
    }

    @Test
    fun `open returns EOF clipped readable length when requested length exceeds file size`() {
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile(fileSize = 256L)),
            fileReader = StaticPlaybackStreamReader(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)))
        )
        val buffer = ByteArray(16)

        val openedLength = source.open(dataSpec(position = 250L, length = 16L))
        val firstRead = source.read(buffer, 0, buffer.size)
        val secondRead = source.read(buffer, firstRead, buffer.size - firstRead)

        assertEquals(6L, openedLength)
        assertEquals(6, firstRead)
        assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, secondRead)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), buffer.take(firstRead).map { it.toInt() })
    }

    @Test
    fun `close interrupts the active read thread`() {
        val enteredRead = CountDownLatch(1)
        val source = VfsPlaybackDataSource(
            fileLookup = StaticPlaybackFileLookup(testFile(fileSize = 4L)),
            fileReader = StaticPlaybackStreamReader(
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        enteredRead.countDown()
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("read interrupted by close")
                            }
                            try {
                                Thread.sleep(25L)
                            } catch (_: InterruptedException) {
                                throw InterruptedIOException("read interrupted by close")
                            }
                        }
                    }
                }
            )
        )
        val errorRef = AtomicReference<Throwable?>()
        source.open(dataSpec())
        val readerThread = Thread {
            try {
                source.read(ByteArray(4), 0, 4)
            } catch (error: Throwable) {
                errorRef.set(error)
            }
        }

        readerThread.start()
        assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
        source.close()
        readerThread.join(2_000L)

        assertTrue("read thread should exit after close", !readerThread.isAlive)
        assertTrue(errorRef.get() is InterruptedIOException)
    }

    private class StaticPlaybackFileLookup(
        private val file: BookFileEntity?
    ) : PlaybackFileLookup {
        override suspend fun getBookFileById(bookFileId: String): BookFileEntity? = file
    }

    private class StaticPlaybackStreamReader(
        private val stream: java.io.InputStream
    ) : VfsPlaybackStreamReader {
        override suspend fun openPlaybackStream(file: BookFileEntity, offset: Long): java.io.InputStream? = stream
    }

    private class ThrowingPlaybackStreamReader(
        private val error: IOException
    ) : VfsPlaybackStreamReader {
        override suspend fun openPlaybackStream(file: BookFileEntity, offset: Long): java.io.InputStream? {
            throw error
        }
    }

    private fun dataSpec(
        position: Long = 0L,
        length: Long = androidx.media3.common.C.LENGTH_UNSET.toLong()
    ): DataSpec =
        DataSpec.Builder()
            .setUri(VfsPlaybackUri.fromBookFile(testFile()))
            .setPosition(position)
            .setLength(length)
            .build()

    private fun testFile(fileSize: Long = 256L): BookFileEntity =
        BookFileEntity(
            id = "file-1",
            bookId = "book-1",
            rootId = "root-1",
            index = 0,
            sourcePath = "chapter.mp3",
            sourceIdentity = "chapter.mp3",
            displayName = "chapter.mp3",
            durationMs = 1_000L,
            fileSize = fileSize,
            lastModified = 0L
        )
}
