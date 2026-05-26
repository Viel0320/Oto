package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    /**
     * 响应式观察特定书籍的所有章节列表（自动一对一拼装物理音频分轨状态）。
     * 
     * 添加 @Transaction 注解以确保 Room 对包含 @Relation 字段的嵌套关系实体进行原子性多表联查，
     * 避免在高并发下因为数据表未同步修改而导致不一致结果。
     */
    @Transaction
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterWithBookFile>>

    /**
     * 同步获取特定书籍的所有章节列表（自动一对一拼装物理音频分轨状态）。
     * 
     * 添加 @Transaction 注解以确保嵌套的 BookFile 关联查询在同一个事务内原子性完成。
     */
    @Transaction
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getChaptersForBookList(bookId: String): List<ChapterWithBookFile>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)
}