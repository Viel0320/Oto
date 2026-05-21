package com.viel.aplayer.data.db


// 新架构统一常量：集中管理 source、role、status，避免导入、播放和 UI 层继续写散落字符串。
object AudiobookSchema {
    object SourceType {
        const val SINGLE_AUDIO = "SINGLE_AUDIO"
        const val CUE = "CUE"
        const val M3U8 = "M3U8"
        const val GENERATED_M3U8 = "GENERATED_M3U8"
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
    }

    object BookStatus {
        const val READY = "READY"
        const val PARTIAL = "PARTIAL"
        const val CONFLICT = "CONFLICT"
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
    }

    object PendingActionType {
        const val CONFLICT = "CONFLICT"
        const val UPDATE_EXISTING = "UPDATE_EXISTING"
        const val PARTIAL_NEW_BOOK = "PARTIAL_NEW_BOOK"
    }

    // Pending actions are current queue rows only; handled/skipped decisions delete rows instead of storing status.

    object LibraryRootStatus {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
        const val ERROR = "ERROR"
    }

    // 为每一次改动添加详尽的中文注释：新增 ReadStatus 常量对象，统一管理有声书的阅读状态，包含“未开始”、“进行中”和“已完成”三种状态，规避拼写错误
    object ReadStatus {
        const val NOT_STARTED = "NOT_STARTED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val FINISHED = "FINISHED"
    }
}