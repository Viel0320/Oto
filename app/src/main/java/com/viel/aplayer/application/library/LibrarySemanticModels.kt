package com.viel.aplayer.application.library

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Scene-facing source type for audiobook projections.
 *
 * This mirrors the persisted schema values without exposing the Room schema package to UI code.
 */
enum class LibraryBookSourceType {
    SINGLE_AUDIO,
    CUE,
    M3U8,
    GENERATED_M3U8,
    ABS_REMOTE
}

/**
 * Scene-facing catalog status for audiobook projections.
 *
 * UI filters and renderers use this application model while data adapters translate from database rows.
 */
enum class LibraryBookStatus {
    READY,
    PARTIAL,
    UNAVAILABLE,
    DELETED
}

/**
 * Scene-facing read status selected by users.
 *
 * Keeping this outside the database package prevents UI callbacks from depending on Room schema types.
 */
enum class LibraryReadStatus {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED
}

/**
 * Scene-facing chapter source for player timeline projections.
 *
 * Player UI previews and rows can describe chapter provenance without importing database constants.
 */
enum class LibraryChapterSource {
    EMBEDDED,
    CUE,
    M3U8,
    GENERATED,
    MANUAL,
    ABS
}

/**
 * Scene-facing bookmark anchor status for player bookmark projections.
 *
 * Bookmark UI can carry remapping state without reconstructing persistence-layer enum references.
 */
enum class LibraryAnchorStatus {
    OK,
    REMAPPED,
    UNRESOLVED
}

internal fun AudiobookSchema.SourceType.toLibraryBookSourceType(): LibraryBookSourceType =
    when (this) {
        AudiobookSchema.SourceType.SINGLE_AUDIO -> LibraryBookSourceType.SINGLE_AUDIO
        AudiobookSchema.SourceType.CUE -> LibraryBookSourceType.CUE
        AudiobookSchema.SourceType.M3U8 -> LibraryBookSourceType.M3U8
        AudiobookSchema.SourceType.GENERATED_M3U8 -> LibraryBookSourceType.GENERATED_M3U8
        AudiobookSchema.SourceType.ABS_REMOTE -> LibraryBookSourceType.ABS_REMOTE
    }

internal fun AudiobookSchema.BookStatus.toLibraryBookStatus(): LibraryBookStatus =
    when (this) {
        AudiobookSchema.BookStatus.READY -> LibraryBookStatus.READY
        AudiobookSchema.BookStatus.PARTIAL -> LibraryBookStatus.PARTIAL
        AudiobookSchema.BookStatus.UNAVAILABLE -> LibraryBookStatus.UNAVAILABLE
        AudiobookSchema.BookStatus.DELETED -> LibraryBookStatus.DELETED
    }

internal fun AudiobookSchema.ReadStatus.toLibraryReadStatus(): LibraryReadStatus =
    when (this) {
        AudiobookSchema.ReadStatus.NOT_STARTED -> LibraryReadStatus.NOT_STARTED
        AudiobookSchema.ReadStatus.IN_PROGRESS -> LibraryReadStatus.IN_PROGRESS
        AudiobookSchema.ReadStatus.FINISHED -> LibraryReadStatus.FINISHED
    }

internal fun LibraryReadStatus.toSchemaReadStatus(): AudiobookSchema.ReadStatus =
    when (this) {
        LibraryReadStatus.NOT_STARTED -> AudiobookSchema.ReadStatus.NOT_STARTED
        LibraryReadStatus.IN_PROGRESS -> AudiobookSchema.ReadStatus.IN_PROGRESS
        LibraryReadStatus.FINISHED -> AudiobookSchema.ReadStatus.FINISHED
    }

internal fun AudiobookSchema.ChapterSource.toLibraryChapterSource(): LibraryChapterSource =
    when (this) {
        AudiobookSchema.ChapterSource.EMBEDDED -> LibraryChapterSource.EMBEDDED
        AudiobookSchema.ChapterSource.CUE -> LibraryChapterSource.CUE
        AudiobookSchema.ChapterSource.M3U8 -> LibraryChapterSource.M3U8
        AudiobookSchema.ChapterSource.GENERATED -> LibraryChapterSource.GENERATED
        AudiobookSchema.ChapterSource.MANUAL -> LibraryChapterSource.MANUAL
        AudiobookSchema.ChapterSource.ABS -> LibraryChapterSource.ABS
    }

internal fun AudiobookSchema.AnchorStatus.toLibraryAnchorStatus(): LibraryAnchorStatus =
    when (this) {
        AudiobookSchema.AnchorStatus.OK -> LibraryAnchorStatus.OK
        AudiobookSchema.AnchorStatus.REMAPPED -> LibraryAnchorStatus.REMAPPED
        AudiobookSchema.AnchorStatus.UNRESOLVED -> LibraryAnchorStatus.UNRESOLVED
    }

internal fun LibraryAnchorStatus.toSchemaAnchorStatus(): AudiobookSchema.AnchorStatus =
    when (this) {
        LibraryAnchorStatus.OK -> AudiobookSchema.AnchorStatus.OK
        LibraryAnchorStatus.REMAPPED -> AudiobookSchema.AnchorStatus.REMAPPED
        LibraryAnchorStatus.UNRESOLVED -> AudiobookSchema.AnchorStatus.UNRESOLVED
    }
