package com.viel.aplayer.application.library.home

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.shared.settings.HomeBookStatusFilter

/**
 * Home Book Status Filter Policy (Application-layer availability matching)
 *
 * Keeps the shared settings enum decoupled from database status types while letting the Home ViewModel apply
 * page-level catalog filtering without re-owning schema translation rules.
 */
fun HomeBookStatusFilter.matchesHomeBookStatus(bookStatus: AudiobookSchema.BookStatus): Boolean =
    when (this) {
        HomeBookStatusFilter.All -> true
        HomeBookStatusFilter.Ready -> bookStatus == AudiobookSchema.BookStatus.READY
        HomeBookStatusFilter.Partial -> bookStatus == AudiobookSchema.BookStatus.PARTIAL
        HomeBookStatusFilter.Unavailable -> bookStatus == AudiobookSchema.BookStatus.UNAVAILABLE
    }
