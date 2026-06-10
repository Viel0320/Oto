package com.viel.aplayer.data.db


// Unified Constants Registry (Aggregates source, role, and status values to prevent string literal drift across layers)
object AudiobookSchema {
    object SourceType {
        const val SINGLE_AUDIO = "SINGLE_AUDIO"
        const val CUE = "CUE"
        const val M3U8 = "M3U8"
        const val GENERATED_M3U8 = "GENERATED_M3U8"
        // Remote Audio Source Identifier (Flags remote tracks to prevent collisions with locally imported files)
        const val ABS_REMOTE = "ABS_REMOTE"
    }

    object FileRole {
        const val SOURCE_MANIFEST = "SOURCE_MANIFEST"
        const val AUDIO = "AUDIO"
    }

    object ChapterSource {
        const val EMBEDDED = "EMBEDDED"
        const val CUE = "CUE"
        const val M3U8 = "M3U8"
        const val GENERATED = "GENERATED"
        const val MANUAL = "MANUAL"
        // Server-side Chapter Registry (Identifies chapters parsed from external servers rather than local resources)
        const val ABS = "ABS"
    }

    object BookStatus {
        const val READY = "READY"
        const val PARTIAL = "PARTIAL"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val DELETED = "DELETED"
    }

    object FileStatus {
        const val READY = "READY"
        const val MISSING = "MISSING"
    }

    object AnchorStatus {
        const val OK = "OK"
        const val REMAPPED = "REMAPPED"
        const val UNRESOLVED = "UNRESOLVED"
    }

    object ScanTrigger {
        const val COLD_START = "COLD_START"
        const val USER = "USER"
        const val ADD_LIBRARY_ROOT = "ADD_LIBRARY_ROOT"
    }

    object ScanStatus {
        const val RUNNING = "RUNNING"
        const val COMPLETED = "COMPLETED"
        // Abandoned Scan Status (Represents scan lifecycles that stopped before durable completion)
        // Failed imports persist this explicit terminal state so command outcome mapping cannot mistake them for completed empty scans.
        const val ABANDONED = "ABANDONED"
    }

    object LibraryRootStatus {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
        const val ERROR = "ERROR"
    }

    // Read Status Registry (Declare ReadStatus state flags to prevent spelling errors when managing progress states)
    // Prepares domain constants for remote storage providers (SAF enabled, WebDAV configured as a future addition).
    object LibrarySourceType {
        const val SAF = "SAF"
        const val WEBDAV = "WEBDAV"
        // Server Library Root (Specifies that the library root represents a remote Audiobookshelf directory tree)
        const val ABS = "ABS"
    }

    object AbsMirrorState {
        // Active Mirror Flag (Specifies that the catalog item exists on the remote server list during the current sync cycle)
        const val ACTIVE = "ACTIVE"
        // Stale Mirror Flag (Indicates that the item was missing during sync but is pending deletion auditing)
        const val STALE = "STALE"
        // Remote Deletion Flag (Indicates that the item is deleted on the server, pending local cleanup decisions)
        const val REMOTE_DELETED = "REMOTE_DELETED"
    }

    // Availability Status Codes (Reserved codes mapping local storage permissions and remote connectivity statuses)
    object AvailabilityStatus {
        const val AVAILABLE = "AVAILABLE"
        const val UNKNOWN = "UNKNOWN"
        const val REVOKED = "REVOKED"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
        const val NOT_FOUND = "NOT_FOUND"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val SERVER_ERROR = "SERVER_ERROR"
        const val TIMEOUT = "TIMEOUT"
        const val UNSUPPORTED = "UNSUPPORTED"
    }

    object ReadStatus {
        const val NOT_STARTED = "NOT_STARTED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val FINISHED = "FINISHED"
    }
}
