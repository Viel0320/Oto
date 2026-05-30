package com.viel.aplayer.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.viel.aplayer.library.vfs.VfsFileInterface
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

@OptIn(UnstableApi::class)
class VfsPlaybackDataSource private constructor(
    private val fileLookup: PlaybackFileLookup,
    private val fileReader: VfsFileInterface
) : BaseDataSource(false) {

    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        // 把 DataSource.open 拆成“查 BookFileEntity”和“通过 VFS 打开流”两段，
        // 便于确认首包读流前的固定成本到底落在数据库还是存储层。
        val openStart = SystemClock.elapsedRealtime()
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: throw DataSourceException("Only VFS playback URIs are supported", PlaybackException.ERROR_CODE_INVALID_STATE)

        transferInitializing(dataSpec)

        // 详尽的中文注释：如果在进入查库阶段前，线程已经被中断，则直接抛出 InterruptedIOException 快速拦截，防止后续多余的操作
        if (Thread.currentThread().isInterrupted) {
            throw DataSourceException(
                java.io.InterruptedIOException("Loader thread was interrupted before database lookup"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        val dbLookupStart = SystemClock.elapsedRealtime()
        // 依托解耦后的快速物理文件检索服务进行音频文件的库表查找
        val file = try {
            runBlocking {
                // 详尽的中文注释：启动运行于 Default 线程池的线程中断守护协程，高频轮询检测当前 loader 线程的 interrupt 状态。
                // 一旦被外部物理打断，立即取消当前的协程 Scope Job，迫使底层挂起操作立刻抛出 CancellationException 中断释放
                val interruptWatcher = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val currentThread = Thread.currentThread()
                    while (isActive) {
                        if (currentThread.isInterrupted) {
                            this@runBlocking.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                                kotlinx.coroutines.CancellationException("Database lookup interrupted physically")
                            )
                            break
                        }
                        kotlinx.coroutines.delay(50)
                    }
                }
                try {
                    fileLookup.getBookFileById(bookFileId)
                } finally {
                    interruptWatcher.cancel() // 必须在执行完毕后立即销毁守护 Job，防止协程泄漏
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw DataSourceException(
                java.io.InterruptedIOException("Database lookup was canceled by thread interrupt"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val dbLookupCost = SystemClock.elapsedRealtime() - dbLookupStart

        // 详尽的中文注释：查库结束后二次校准线程打断状态，如果已被打断则立刻退出，杜绝耗时的远程 VFS 连接握手
        if (Thread.currentThread().isInterrupted) {
            throw DataSourceException(
                java.io.InterruptedIOException("Loader thread was interrupted after database lookup"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        // 播放层只传 offset 给 VFS，SAF 由默认 skip 处理，WebDAV 由 Provider 转成 Range 请求。
        val vfsOpenStart = SystemClock.elapsedRealtime()
        val stream = try {
            runBlocking {
                // 详尽的中文注释：为最危险的网络流打开配置线程中断守护协程。在握手或建立 Socket 连接挂起时，
                // 一旦 loader 线程被 ExoPlayer 中断，立即物理取消协程 Job，迫使底层的网络挂起操作瞬间崩裂抛出 CancellationException
                val interruptWatcher = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val currentThread = Thread.currentThread()
                    while (isActive) {
                        if (currentThread.isInterrupted) {
                            this@runBlocking.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                                kotlinx.coroutines.CancellationException("VFS open stream was physically interrupted")
                            )
                          break
                        }
                        kotlinx.coroutines.delay(50)
                    }
                }
                try {
                    fileReader.open(file, dataSpec.position)
                } finally {
                    interruptWatcher.cancel() // 彻底注销守护协程，防范内存泄露
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw DataSourceException(
                java.io.InterruptedIOException("VFS open stream was physically canceled by thread interrupt"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } catch (e: IOException) {
            // Provider offset 打开失败统一映射成 Media3 可理解的读位置错误，避免远程异常穿透播放器。
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val vfsOpenCost = SystemClock.elapsedRealtime() - vfsOpenStart

        inputStream = stream
        openedUri = dataSpec.uri
        val knownSize = file.fileSize.takeIf { it > 0L }
        val availableFromPosition = knownSize?.let { (it - dataSpec.position).coerceAtLeast(0L) }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> availableFromPosition?.let { min(dataSpec.length, it) } ?: dataSpec.length
            availableFromPosition != null -> availableFromPosition
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        val totalOpenCost = SystemClock.elapsedRealtime() - openStart
        com.viel.aplayer.logger.PlaybackTimingLogger.logDataSourceOpen(
            bookFileId = bookFileId,
            offset = dataSpec.position,
            dbCostMs = dbLookupCost,
            vfsCostMs = vfsOpenCost,
            totalMs = totalOpenCost,
            fileSize = file.fileSize
        )
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream
            ?: throw DataSourceException("VFS playback stream is not open", PlaybackException.ERROR_CODE_INVALID_STATE)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesRemaining).toInt()
        }
        val bytesRead = try {
            stream.read(buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            inputStream?.closeQuietly()
        } finally {
            inputStream = null
            openedUri = null
            bytesRemaining = 0L
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // 关闭 VFS 播放流失败不应覆盖前面已抛出的真实播放错误。
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext
        // 详尽的中文注释：使用伴生对象中的 getContainer 安全方法惰性解析依赖容器，
        // 解除对 APlayerApplication 强制转换的硬编码，确保在 loader 线程中或播放源恢复的极其脆弱的生命周期内绝对时序安全
        private val container = com.viel.aplayer.APlayerApplication.getContainer(appContext)

        override fun createDataSource(): DataSource =
            VfsPlaybackDataSource(
                fileLookup = container.playbackFileLookup,
                fileReader = container.vfsFileInterface
            )
    }
}
