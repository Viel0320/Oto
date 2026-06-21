package com.viel.aplayer.data.db

import androidx.room.TypeConverter
import com.viel.aplayer.data.entity.DownloadStatus

class AudiobookDatabaseConverters {
    @TypeConverter
    fun toBookStatus(value: String): AudiobookSchema.BookStatus = try {
        AudiobookSchema.BookStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.BookStatus.READY
    }

    @TypeConverter
    fun fromBookStatus(status: AudiobookSchema.BookStatus): String = status.name

    @TypeConverter
    fun toReadStatus(value: String): AudiobookSchema.ReadStatus = try {
        AudiobookSchema.ReadStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.ReadStatus.NOT_STARTED
    }

    @TypeConverter
    fun fromReadStatus(status: AudiobookSchema.ReadStatus): String = status.name

    @TypeConverter
    fun toAnchorStatus(value: String): AudiobookSchema.AnchorStatus = try {
        AudiobookSchema.AnchorStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.AnchorStatus.OK
    }

    @TypeConverter
    fun fromAnchorStatus(status: AudiobookSchema.AnchorStatus): String = status.name

    @TypeConverter
    fun toSourceType(value: String): AudiobookSchema.SourceType = try {
        AudiobookSchema.SourceType.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.SourceType.SINGLE_AUDIO
    }

    @TypeConverter
    fun fromSourceType(sourceType: AudiobookSchema.SourceType): String = sourceType.name

    @TypeConverter
    fun toFileRole(value: String): AudiobookSchema.FileRole = try {
        AudiobookSchema.FileRole.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.FileRole.AUDIO
    }

    @TypeConverter
    fun fromFileRole(fileRole: AudiobookSchema.FileRole): String = fileRole.name

    @TypeConverter
    fun toChapterSource(value: String): AudiobookSchema.ChapterSource = try {
        AudiobookSchema.ChapterSource.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.ChapterSource.EMBEDDED
    }

    @TypeConverter
    fun fromChapterSource(chapterSource: AudiobookSchema.ChapterSource): String = chapterSource.name

    @TypeConverter
    fun toFileStatus(value: String): AudiobookSchema.FileStatus = try {
        AudiobookSchema.FileStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.FileStatus.READY
    }

    @TypeConverter
    fun fromFileStatus(fileStatus: AudiobookSchema.FileStatus): String = fileStatus.name

    @TypeConverter
    fun toScanTrigger(value: String): AudiobookSchema.ScanTrigger = try {
        AudiobookSchema.ScanTrigger.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.ScanTrigger.USER
    }

    @TypeConverter
    fun fromScanTrigger(scanTrigger: AudiobookSchema.ScanTrigger): String = scanTrigger.name

    @TypeConverter
    fun toScanStatus(value: String): AudiobookSchema.ScanStatus = try {
        AudiobookSchema.ScanStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.ScanStatus.COMPLETED
    }

    @TypeConverter
    fun fromScanStatus(scanStatus: AudiobookSchema.ScanStatus): String = scanStatus.name

    @TypeConverter
    fun toLibraryRootStatus(value: String): AudiobookSchema.LibraryRootStatus = try {
        AudiobookSchema.LibraryRootStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.LibraryRootStatus.ACTIVE
    }

    @TypeConverter
    fun fromLibraryRootStatus(status: AudiobookSchema.LibraryRootStatus): String = status.name

    @TypeConverter
    fun toLibrarySourceType(value: String): AudiobookSchema.LibrarySourceType = try {
        AudiobookSchema.LibrarySourceType.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.LibrarySourceType.SAF
    }

    @TypeConverter
    fun fromLibrarySourceType(sourceType: AudiobookSchema.LibrarySourceType): String = sourceType.name

    @TypeConverter
    fun toAbsMirrorState(value: String): AudiobookSchema.AbsMirrorState = try {
        AudiobookSchema.AbsMirrorState.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.AbsMirrorState.ACTIVE
    }

    @TypeConverter
    fun fromAbsMirrorState(state: AudiobookSchema.AbsMirrorState): String = state.name

    @TypeConverter
    fun toAvailabilityStatus(value: String): AudiobookSchema.AvailabilityStatus = try {
        AudiobookSchema.AvailabilityStatus.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.AvailabilityStatus.UNKNOWN
    }

    @TypeConverter
    fun fromAvailabilityStatus(status: AudiobookSchema.AvailabilityStatus): String = status.name

    @TypeConverter
    fun toAbsPlaybackSessionState(value: String): AudiobookSchema.AbsPlaybackSessionState = try {
        AudiobookSchema.AbsPlaybackSessionState.valueOf(value)
    } catch (e: Exception) {
        AudiobookSchema.AbsPlaybackSessionState.OPEN
    }

    @TypeConverter
    fun fromAbsPlaybackSessionState(state: AudiobookSchema.AbsPlaybackSessionState): String = state.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = try {
        DownloadStatus.valueOf(value)
    } catch (e: Exception) {
        DownloadStatus.FAILED
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name
}
