package com.viel.aplayer.data.usecase

import android.util.Log
import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.media.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 跨领域协调的具体用例：安全卸载/删除一个书库根目录。
 *
 * 核心职责：
 * 1. 跨领域协同：由于删除书库数据属于“数据领域”职责，而停止播放属于“媒体播放领域”职责。
 *    原本这两个不同的领域耦合在数据仓库底层（BookLibraryRepository），造成了严重的反向依赖。
 *    现在将此跨领域的控制协调逻辑上移到具体的应用用例（UseCase）中，实现彻底解耦。
 * 2. 安全策略执行：
 *    - 检查当前正在播放的有声书是否属于待删除的书库根目录。
 *    - 如果是，则先行安全停止媒体播放，防止出现底层 VFS 句柄丢失、IO 异常或播放器崩溃。
 *    - 最后再调用数据仓库的纯数据清理接口，级联清除所有文件、权限和 Room 记录。
 */
class DeleteLibraryRootUseCase(
    private val playbackManager: PlaybackManager,
    private val bookLibraryRepository: BookLibraryRepository
) {

    /**
     * 执行书库根卸载逻辑。
     * @param root 待删除的书库根实体
     * @return 如果在此过程中触发了紧急停播，则返回 true，否则返回 false
     */
    suspend fun invoke(root: LibraryRootEntity): Boolean = withContext(Dispatchers.IO) {
        var playbackStopped = false

        // 1. 获取当前正在播放的有声书 ID（来自媒体播放领域）
        try {
            val currentBookId = playbackManager.currentPlayingBookId
            if (currentBookId != null) {
                // 2. 根据 ID 查询书籍实体，判断其所属的书库根 ID 是否与当前待删的根匹配
                val currentBook = bookLibraryRepository.getBookById(currentBookId)
                if (currentBook != null && currentBook.rootId == root.id) {
                    // 3. 如果属于被删书库，立即下发紧急停播指令，并锁定返回状态
                    playbackManager.stopPlayback()
                    playbackStopped = true
                    Log.i("DeleteLibraryRootUseCase", "待删除的书库根目录 [${root.id}] 正在播放，已成功触发紧急停播")
                }
            }
        } catch (e: Exception) {
            Log.e("DeleteLibraryRootUseCase", "检测或暂停被删除根目录的有声书播放时发生异常", e)
        }

        // 4. 调用数据领域的纯数据清理接口，物理删除对应文件与 Room 实体
        bookLibraryRepository.deleteLibraryRootDataOnly(root)

        playbackStopped
    }
}
