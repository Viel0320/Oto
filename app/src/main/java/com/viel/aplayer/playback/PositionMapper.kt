package com.viel.aplayer.playback

import com.viel.aplayer.data.BookFileEntity

/**
 * 负责在全局播放位置与具体文件的位置之间进行转换。
 */
object PositionMapper {
    /**
     * 将全局位置转换为文件索引和文件内位置。
     */
    fun globalToFilePosition(
        globalPositionMs: Long,
        files: List<BookFileEntity>
    ): Pair<Int, Long> {
        var accumulatedMs = 0L
        for ((index, file) in files.withIndex()) {
            if (globalPositionMs < accumulatedMs + file.durationMs) {
                return Pair(index, globalPositionMs - accumulatedMs)
            }
            accumulatedMs += file.durationMs
        }
        // 如果超过总时长，返回最后一个文件的末尾
        return if (files.isNotEmpty()) {
            Pair(files.size - 1, files.last().durationMs)
        } else {
            Pair(0, 0L)
        }
    }

    /**
     * 将文件索引和文件内位置转换为全局位置。
     */
    fun fileToGlobalPosition(
        fileIndex: Int,
        positionInFileMs: Long,
        files: List<BookFileEntity>
    ): Long {
        var accumulatedMs = 0L
        for (i in 0 until fileIndex.coerceAtMost(files.size)) {
            accumulatedMs += files[i].durationMs
        }
        return accumulatedMs + positionInFileMs
    }
}
