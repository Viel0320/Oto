package com.viel.oto.ui.navigation

import com.viel.oto.application.library.detail.DetailBookItem
import com.viel.oto.ui.detail.DetailEntrySource

data class DetailOpenRequest(
    val book: DetailBookItem?,
    val entrySource: DetailEntrySource
)
