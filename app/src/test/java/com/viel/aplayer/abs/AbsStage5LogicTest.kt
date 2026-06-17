package com.viel.aplayer.abs

import com.viel.aplayer.abs.sync.AbsSyncPlan
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.root.shouldDeleteAbsCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsStage5LogicTest {

    @Test
    fun `shared credential still should not be deleted when removing one abs root`() {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )
        val sibling = root.copy(id = "root-2", basePath = "lib-2")

        assertFalse(shouldDeleteAbsCredential(root, listOf(root, sibling)))
    }

    @Test
    fun `single abs root should delete credential when removed`() {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )

        assertTrue(shouldDeleteAbsCredential(root, listOf(root)))
    }

    @Test
    fun `large library plan should require confirmation only above ten thousand`() {
        val small = AbsSyncPlan(totalItems = 3000, batchSize = 20, requiresConfirmation = false)
        val medium = AbsSyncPlan(totalItems = 10000, batchSize = 20, requiresConfirmation = false)
        val large = AbsSyncPlan(totalItems = 10001, batchSize = 20, requiresConfirmation = true)

        assertFalse(small.requiresConfirmation)
        assertFalse(medium.requiresConfirmation)
        assertTrue(large.requiresConfirmation)
        assertEquals(20, large.batchSize)
    }
}
