package com.viel.aplayer.abs.net.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsLibraryItemsResponseDto(
    val results: List<AbsLibraryItemDto>? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val page: Int? = null
)

@JsonClass(generateAdapter = true)
data class AbsBatchGetItemsResponseDto(
    @field:Json(name = "libraryItems")
    val libraryItems: List<AbsLibraryItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class AbsLibraryItemDto(
    val id: String? = null,
    val libraryId: String? = null,
    val mediaType: String? = null,
    val title: String? = null,
    val updatedAt: Long? = null,
    val addedAt: Long? = null,
    val numFiles: Int? = null,
    val media: AbsItemMediaDto? = null,
    val progress: AbsUserProgressDto? = null,
    val authors: List<AbsAuthorDto>? = null
)

@JsonClass(generateAdapter = true)
data class AbsItemMediaDto(
    val metadata: AbsMediaMetadataDto? = null,
    val tracks: List<AbsTrackDto>? = null,
    val audioFiles: List<AbsAudioFileDto>? = null,
    val chapters: List<AbsChapterDto>? = null,
    val duration: Double? = null,
    val size: Long? = null,
    val numTracks: Int? = null,
    val numAudioFiles: Int? = null,
    val numChapters: Int? = null
)

@JsonClass(generateAdapter = true)
data class AbsTrackDto(
    val index: Int? = null,
    val ino: String? = null,
    val startOffset: Double? = null,
    val duration: Double? = null,
    val contentUrl: String? = null,
    val mimeType: String? = null,
    val metadata: AbsTrackMetadataDto? = null,
    val title: String? = null,
    val chapters: List<AbsChapterDto>? = null
)

@JsonClass(generateAdapter = true)
data class AbsTrackMetadataDto(
    val filename: String? = null,
    val ext: String? = null,
    val size: Long? = null,
    val mtimeMs: Long? = null,
    val path: String? = null,
    val relPath: String? = null
)

@JsonClass(generateAdapter = true)
data class AbsAudioFileDto(
    val ino: String? = null,
    val index: Int? = null,
    val metadata: AbsTrackMetadataDto? = null,
    val duration: Double? = null,
    val size: Long? = null
)

@JsonClass(generateAdapter = true)
data class AbsChapterDto(
    val id: Int? = null,
    val title: String? = null,
    val start: Double? = null,
    val end: Double? = null
)

@JsonClass(generateAdapter = true)
data class AbsAuthorDto(
    val id: String? = null,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class AbsMediaMetadataDto(
    val title: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val publishedYear: String? = null,
    val description: String? = null,
    val seriesName: String? = null
)
