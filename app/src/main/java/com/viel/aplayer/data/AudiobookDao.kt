package com.viel.aplayer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobooks ORDER BY lastPlayedAt DESC")
    fun getAllAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT uri FROM audiobooks")
    suspend fun getAllUris(): List<String>
    
    @Query("SELECT * FROM audiobooks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): AudiobookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(audiobook: AudiobookEntity)
    
    @Query("UPDATE audiobooks SET lastPosition = :lastPosition, lastPlayedAt = :lastPlayedAt WHERE uri = :uri")
    suspend fun updateProgress(uri: String, lastPosition: Long, lastPlayedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE audiobooks SET title = :title, author = :author, narrator = :narrator, description = :description, duration = :duration WHERE uri = :uri")
    suspend fun updateMetadata(uri: String, title: String, author: String, narrator: String, description: String, duration: Long)
    
    @Query("UPDATE audiobooks SET coverPath = :coverPath WHERE uri = :uri")
    suspend fun updateCoverPath(uri: String, coverPath: String)

    @Query("UPDATE audiobooks SET thumbnailPath = :thumbnailPath WHERE uri = :uri")
    suspend fun updateThumbnailPath(uri: String, thumbnailPath: String)

    @Query("UPDATE audiobooks SET coverPath = :coverPath, thumbnailPath = :thumbnailPath WHERE uri = :uri")
    suspend fun updateCoverPaths(uri: String, coverPath: String?, thumbnailPath: String?)

    @Query("UPDATE audiobooks SET subtitlePath = :subtitlePath WHERE uri = :uri")
    suspend fun updateSubtitlePath(uri: String, subtitlePath: String?)
    
    @Query("SELECT * FROM audiobooks ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getMostRecent(): AudiobookEntity?

    @Query("SELECT * FROM audiobooks WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' OR narrator LIKE '%' || :query || '%' OR year LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchAudiobooks(query: String): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE year LIKE '%' || :year || '%' ORDER BY title ASC")
    fun filterByYear(year: String): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE author LIKE '%' || :author || '%' ORDER BY title ASC")
    fun filterByAuthor(author: String): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE narrator LIKE '%' || :narrator || '%' ORDER BY title ASC")
    fun filterByNarrator(narrator: String): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int): Flow<List<AudiobookEntity>>

    @Query("DELETE FROM audiobooks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
    
    @Delete
    suspend fun delete(audiobook: AudiobookEntity)
}
