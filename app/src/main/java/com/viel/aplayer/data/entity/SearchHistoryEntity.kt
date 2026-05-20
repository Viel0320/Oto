package com.viel.aplayer.data.entity


// Search history is now a DataStore model, not a Room entity in the main database.
data class SearchHistoryEntity(
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)