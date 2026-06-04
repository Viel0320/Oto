package com.viel.aplayer.data.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Audiobook Chapter Track Composition (Combined data object wrapping chapter metadata with physical asset states)
 * 
 * Uses Room's official one-to-one relationship mapping (@Relation) to query the logical Chapter entity
 * alongside its underlying physical BookFile asset snapshot.
 * This dynamically propagates VFS track states (e.g. READY / MISSING) into active chapter screens.
 * This design avoids structural modifications to the database schema, introducing zero migration risks.
 */
data class ChapterWithBookFile(
    /** Embedded database entity representing raw chapter coordinates */
    @Embedded
    val chapter: ChapterEntity,

    /** Associated physical book file entity, nullable if deleted via cascade policies */
    @Relation(
        parentColumn = "bookFileId",
        entityColumn = "id"
    )
    val bookFile: BookFileEntity?
)
