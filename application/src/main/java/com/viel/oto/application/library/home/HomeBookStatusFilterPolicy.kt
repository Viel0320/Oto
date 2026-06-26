package com.viel.oto.application.library.home

import com.viel.oto.application.library.LibraryBookStatus
import com.viel.oto.shared.model.HomeBookStatusFilter

/**
 * Application-layer availability matching.
 *
 * Keeps the shared settings enum decoupled from database status types while letting the Home ViewModel apply
 * page-level catalog filtering without re-owning schema translation rules.
 */
fun HomeBookStatusFilter.matchesHomeBookStatus(bookStatus: LibraryBookStatus): Boolean =
    when (this) {
        HomeBookStatusFilter.All -> true
        HomeBookStatusFilter.Ready -> bookStatus == LibraryBookStatus.READY
        HomeBookStatusFilter.Partial -> bookStatus == LibraryBookStatus.PARTIAL
        HomeBookStatusFilter.Unavailable -> bookStatus == LibraryBookStatus.UNAVAILABLE
    }
