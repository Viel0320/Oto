package com.viel.aplayer.ui.navigation

import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.ui.detail.DetailEntrySource

data class DetailOpenRequest(
    val book: DetailBookItem?,
    val entrySource: DetailEntrySource
)
