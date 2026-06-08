package com.viel.aplayer.application.library.edit

/**
 * Edit Book Read Model (Edit-scene metadata lookup surface)
 * Exposes only an edit-scene draft so ViewModel callers do not depend on the persisted Room entity.
 */
interface EditBookReadModel {
    /**
     * Get Editable Book (Resolve the selected book for editing)
     * Keeps route and ViewModel code away from both the broader library facade and the database entity shape.
     */
    suspend fun getEditableBook(bookId: String): EditBookDraft?
}
