package com.viel.aplayer.application.library.detail

import kotlinx.coroutines.flow.Flow

/**
 * Detail Book Read Model (Scene-level detail read surface)
 * Provides source-location resolution and live selected-book snapshots without exposing broad library or root/file gateways to DetailViewModel.
 */
interface DetailBookReadModel {
    /**
     * Resolve Source Location (Build the user-facing storage breadcrumb for a selected book)
     * The module owns file/root lookup so the ViewModel does not query storage roots or physical file rows directly.
     */
    suspend fun resolveSourceLocation(snapshot: DetailSnapshot): String

    /**
     * Observe Live Snapshot (Track metadata edits for the selected book only)
     * Emits updated detail snapshots while preserving the detail scene boundary around the database observer.
     */
    fun observeLiveSnapshot(snapshot: DetailSnapshot): Flow<DetailSnapshot>
}
