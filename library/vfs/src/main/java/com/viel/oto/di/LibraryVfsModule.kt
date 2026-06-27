package com.viel.oto.di

import android.content.Context
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.library.vfs.VfsPlaybackStreamReader
import com.viel.oto.library.vfs.cache.DirectoryListingCache
import com.viel.oto.library.vfs.cache.RoomDirectoryListingCache
import com.viel.oto.library.vfs.cache.VfsRangeCache
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Library VFS cache and file-interface bindings.
 *
 * The app shell still contributes the LibrarySourceProviderFactory because ABS is an external source
 * adapter, while this module owns the reusable VFS readers and cache definitions.
 * VfsFileInterface is also bound to the playback stream contract so Media3 callers can depend on
 * the narrow reader interface instead of the concrete VFS facade.
 */
object LibraryVfsModule {

    val module: Module = module {
        single { VfsRangeCache(get<Context>()) }

        single {
            RoomDirectoryListingCache(directoryChildCacheDao = get<AppDatabase>().directoryChildCacheDao())
        } bind DirectoryListingCache::class

        single {
            VfsFileInterface(
                get(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                rangeCache = get(),
                providerFactory = get<LibrarySourceProviderFactory>()
            )
        } bind VfsPlaybackStreamReader::class
    }
}
