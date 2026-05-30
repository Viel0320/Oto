package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import kotlinx.coroutines.flow.Flow

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

    /**
     * 详尽的中文注释：在同一个 Room 写事务中原子地清空特定有声书的旧章节关联并批量录入重扫得到的全新章节。
     * 无论是在协程被取消还是物理插入发生异常时，都能确保该书不会出现章节“数据空虚”的尴尬，并根除 Flow 收集端闪烁空状态的严重问题。
     */
    @Transaction
    suspend fun replaceChapters(bookId: String, chapters: List<ChapterEntity>) {
        deleteChaptersForBook(bookId)
        insertChapters(chapters)
    }
}