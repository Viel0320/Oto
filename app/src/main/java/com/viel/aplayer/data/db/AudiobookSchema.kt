package com.viel.aplayer.data.db

// Unified Constants Registry (Aggregates source, role, and status values to prevent string literal drift across layers)
object AudiobookSchema {
    // SourceType Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid source types.
    enum class SourceType {
        SINGLE_AUDIO,
        CUE,
        M3U8,
        GENERATED_M3U8,
        // Remote Audio Source Identifier (Flags remote tracks to prevent collisions with locally imported files)
        ABS_REMOTE
    }

    // FileRole Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid file roles.
    enum class FileRole {
        SOURCE_MANIFEST,
        AUDIO
    }

    // ChapterSource Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid chapter sources.
    enum class ChapterSource {
        EMBEDDED,
        CUE,
        M3U8,
        GENERATED,
        MANUAL,
        // Server-side Chapter Registry (Identifies chapters parsed from external servers rather than local resources)
        ABS
    }

    // BookStatus Enum Refactoring: Convert string constants to a type-safe enum class to prevent invalid states in books.
    enum class BookStatus {
        READY,
        PARTIAL,
        UNAVAILABLE,
        DELETED
    }

    // FileStatus Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid file status values.
    enum class FileStatus {
        READY,
        MISSING
    }

    // AnchorStatus Enum Refactoring: Convert string constants to a type-safe enum class to prevent invalid states in progress anchor mappings.
    enum class AnchorStatus {
        OK,
        REMAPPED,
        UNRESOLVED
    }

    // ScanTrigger Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid scan triggers.
    enum class ScanTrigger {
        COLD_START,
        USER,
        ADD_LIBRARY_ROOT
    }

    // ScanStatus Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid scan status values.
    enum class ScanStatus {
        RUNNING,
        COMPLETED,
        // Abandoned Scan Status (Represents scan lifecycles that stopped before durable completion)
        // Failed imports persist this explicit terminal state so command outcome mapping cannot mistake them for completed empty scans.
        ABANDONED
    }

    // LibraryRootStatus Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid library root status values.
    enum class LibraryRootStatus {
        ACTIVE,
        REVOKED,
        ERROR
    }

    // LibrarySourceType Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid library source types.
    // Prepares domain constants for remote storage providers (SAF enabled, WebDAV configured as a future addition).
    enum class LibrarySourceType {
        SAF,
        WEBDAV,
        // Server Library Root (Specifies that the library root represents a remote Audiobookshelf directory tree)
        ABS
    }

    // AbsMirrorState Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid ABS mirror states.
    enum class AbsMirrorState {
        // Active Mirror Flag (Specifies that the catalog item exists on the remote server list during the current sync cycle)
        ACTIVE,
        // Stale Mirror Flag (Indicates that the item was missing during sync but is pending deletion auditing)
        STALE,
        // Remote Deletion Flag (Indicates that the item is deleted on the server, pending local cleanup decisions)
        REMOTE_DELETED
    }

    // AvailabilityStatus Enum Refactoring: Convert string constants to type-safe enum class to prevent invalid availability status codes.
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

    // ReadStatus Enum Refactoring: Convert string constants to a type-safe enum class to ensure consistent user reading progress states.
    enum class ReadStatus {
        NOT_STARTED,
        IN_PROGRESS,
        FINISHED
    }

    // AbsPlaybackSessionState Enum Refactoring: Introduce type-safe representation for ABS session lifecycle to replace string literals.
    enum class AbsPlaybackSessionState {
        OPEN,
        SYNCED
    }
}
