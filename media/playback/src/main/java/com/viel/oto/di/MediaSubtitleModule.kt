package com.viel.oto.di

import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.media.subtitle.SubtitleFileResolver
import com.viel.oto.media.subtitle.SubtitleGateway
import com.viel.oto.media.subtitle.SubtitleGatewayImpl
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Playback subtitle gateway wiring.
 *
 * Subtitle resolution needs playback-side subtitle lookup plus VFS file access, so the public
 * SubtitleGateway binding lives with the playback module instead of the app shell.
 */
object MediaSubtitleModule {

    val module: Module = module {
        single {
            SubtitleFileResolver(
                context = get(),
                bookDao = get<AppDatabase>().bookDao(),
                fileReader = get<VfsFileInterface>()
            )
        }

        single<SubtitleGateway> { SubtitleGatewayImpl(subtitleResolver = get()) }
    }
}
