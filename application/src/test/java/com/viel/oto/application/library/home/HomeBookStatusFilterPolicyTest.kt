package com.viel.oto.application.library.home

import com.viel.oto.application.library.LibraryBookStatus
import com.viel.oto.shared.model.HomeBookStatusFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the application-layer mapping between the shared settings filter enum and database-derived
 * LibraryBookStatus values so Home page filtering stays decoupled from schema status types.
 */
class HomeBookStatusFilterPolicyTest {

    @Test
    fun `All filter matches every library book status`() {
        LibraryBookStatus.entries.forEach { status ->
            assertTrue(
                "All should match $status",
                HomeBookStatusFilter.All.matchesHomeBookStatus(status)
            )
        }
    }

    @Test
    fun `Ready filter matches only the READY status`() {
        assertTrue(HomeBookStatusFilter.Ready.matchesHomeBookStatus(LibraryBookStatus.READY))
        assertFalse(HomeBookStatusFilter.Ready.matchesHomeBookStatus(LibraryBookStatus.PARTIAL))
        assertFalse(HomeBookStatusFilter.Ready.matchesHomeBookStatus(LibraryBookStatus.UNAVAILABLE))
        assertFalse(HomeBookStatusFilter.Ready.matchesHomeBookStatus(LibraryBookStatus.DELETED))
    }

    @Test
    fun `Partial filter matches only the PARTIAL status`() {
        assertTrue(HomeBookStatusFilter.Partial.matchesHomeBookStatus(LibraryBookStatus.PARTIAL))
        assertFalse(HomeBookStatusFilter.Partial.matchesHomeBookStatus(LibraryBookStatus.READY))
        assertFalse(HomeBookStatusFilter.Partial.matchesHomeBookStatus(LibraryBookStatus.UNAVAILABLE))
        assertFalse(HomeBookStatusFilter.Partial.matchesHomeBookStatus(LibraryBookStatus.DELETED))
    }

    @Test
    fun `Unavailable filter matches only the UNAVAILABLE status`() {
        assertTrue(HomeBookStatusFilter.Unavailable.matchesHomeBookStatus(LibraryBookStatus.UNAVAILABLE))
        assertFalse(HomeBookStatusFilter.Unavailable.matchesHomeBookStatus(LibraryBookStatus.READY))
        assertFalse(HomeBookStatusFilter.Unavailable.matchesHomeBookStatus(LibraryBookStatus.PARTIAL))
        assertFalse(HomeBookStatusFilter.Unavailable.matchesHomeBookStatus(LibraryBookStatus.DELETED))
    }

    @Test
    fun `DELETED status is only reachable through the All filter`() {
        assertTrue(HomeBookStatusFilter.All.matchesHomeBookStatus(LibraryBookStatus.DELETED))
        assertFalse(HomeBookStatusFilter.Ready.matchesHomeBookStatus(LibraryBookStatus.DELETED))
        assertFalse(HomeBookStatusFilter.Partial.matchesHomeBookStatus(LibraryBookStatus.DELETED))
        assertFalse(HomeBookStatusFilter.Unavailable.matchesHomeBookStatus(LibraryBookStatus.DELETED))
    }
}
