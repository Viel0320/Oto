package com.viel.oto.di

import com.viel.oto.data.availability.FileAvailabilityProbe
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.availability.AbsAvailabilityGateway
import com.viel.oto.library.availability.AvailabilityChecker
import com.viel.oto.library.availability.LibraryFileAvailabilityProbe
import com.viel.oto.library.availability.MissingBookFileRecoveryChecker
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Library availability probing bindings split out of LibraryScanModule.
 *
 * Availability checks form a cohesive sub-domain: they observe source reachability and missing-file
 * recovery independently of cache eviction, scan scheduling, and root lifecycle ownership.
 */
object LibraryAvailabilityModule {

    val module: Module = module {
        single {
            AvailabilityChecker(
                context = get(),
                database = get<AppDatabase>(),
                absAvailabilityGateway = get<AbsAvailabilityGateway>()
            )
        }

        single { MissingBookFileRecoveryChecker(get<AppDatabase>(), get()) }

        single<FileAvailabilityProbe> {
            LibraryFileAvailabilityProbe(availabilityChecker = get())
        }
    }
}
