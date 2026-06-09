package com.viel.aplayer.ui.navigation

import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.ui.detail.DetailEntrySource

/*
 * Detail Open Request (Navigation-level shared-element handoff command)
 *
 * Carries the already mapped Detail scene item and its source surface so the app shell can
 * queue rapid re-entry without letting Home or Search mutate DetailViewModel directly.
 */
data class DetailOpenRequest(
    val book: DetailBookItem?,
    val entrySource: DetailEntrySource
)
