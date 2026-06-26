package com.viel.oto.di

import com.viel.oto.data.cover.CoverImageWriter
import com.viel.oto.data.cover.CoverRecoveryArtworkSource
import com.viel.oto.data.metadata.MetadataRefreshSource
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.media.parser.CoverExtractor
import com.viel.oto.media.parser.MediaCoverImageWriter
import com.viel.oto.media.parser.MediaCoverRecoveryArtworkSource
import com.viel.oto.media.parser.MediaMetadataRefreshSource
import com.viel.oto.media.parser.MetadataResolver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Media metadata parser adapters used by data-owned cover and metadata gateways.
 *
 * The data module owns persistence-facing interfaces, while this module supplies parser-backed
 * implementations that need VFS readers and media metadata extraction.
 */
object MediaMetadataModule {

    val module: Module = module {
        single { CoverExtractor(get()) }

        single<CoverImageWriter> {
            MediaCoverImageWriter(coverExtractor = get())
        }

        single<CoverRecoveryArtworkSource> {
            MediaCoverRecoveryArtworkSource(
                fileReader = get<VfsFileInterface>(),
                coverImageWriter = get<CoverImageWriter>()
            )
        }

        single { MetadataResolver(get<VfsFileInterface>()) }

        single<MetadataRefreshSource> {
            MediaMetadataRefreshSource(metadataResolver = get())
        }
    }
}
