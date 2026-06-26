package com.viel.oto.data.db

object AudiobookSchema {
    enum class SourceType {
        SINGLE_AUDIO,
        CUE,
        M3U8,
        GENERATED_M3U8,
        ABS_REMOTE
    }

    enum class FileRole {
        SOURCE_MANIFEST,
        AUDIO
    }

    enum class ChapterSource {
        EMBEDDED,
        CUE,
        M3U8,
        GENERATED,
        MANUAL,
        ABS
    }

    enum class BookStatus {
        READY,
        PARTIAL,
        UNAVAILABLE,
        DELETED
    }

    enum class FileStatus {
        READY,
        MISSING
    }

    enum class AnchorStatus {
        OK,
        REMAPPED,
        UNRESOLVED
    }

    enum class ScanTrigger {
        COLD_START,
        USER,
        ADD_LIBRARY_ROOT
    }

    enum class ScanStatus {
        RUNNING,
        COMPLETED,
        ABANDONED
    }

    enum class LibraryRootStatus {
        ACTIVE,
        REVOKED,
        ERROR
    }

    enum class LibrarySourceType {
        SAF,
        WEBDAV,
        ABS
    }

    enum class AbsMirrorState {
        ACTIVE,
        STALE,
        REMOTE_DELETED
    }

    enum class AvailabilityStatus {
        AVAILABLE,
        UNKNOWN,
        REVOKED,
        AUTH_FAILED,
        NETWORK_UNAVAILABLE,
        NOT_FOUND,
        PERMISSION_DENIED,
        SERVER_ERROR,
        TIMEOUT,
        UNSUPPORTED
    }

    enum class ReadStatus {
        NOT_STARTED,
        IN_PROGRESS,
        FINISHED
    }

    enum class AbsPlaybackSessionState {
        OPEN,
        SYNCED
    }
}
