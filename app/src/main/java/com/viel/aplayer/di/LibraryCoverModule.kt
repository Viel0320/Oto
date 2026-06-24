package com.viel.aplayer.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.cover.AndroidCoverUriResolver
import com.viel.aplayer.data.cover.CoverAssetGateway
import com.viel.aplayer.data.cover.CoverAssetGatewayImpl
import com.viel.aplayer.data.cover.CoverRecoveryGateway
import com.viel.aplayer.data.cover.CoverRecoveryGatewayImpl
import com.viel.aplayer.data.cover.CoverRecoveryHelper
import com.viel.aplayer.data.cover.CoverSelfHealer
import com.viel.aplayer.data.cover.CoverUriResolver
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.metadata.MetadataRefreshGateway
import com.viel.aplayer.data.metadata.MetadataRefreshGatewayImpl
import com.viel.aplayer.data.subtitle.SubtitleGateway
import com.viel.aplayer.data.subtitle.SubtitleGatewayImpl
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.Closeable

/**
 * Cover extraction, recovery, asset gateway, metadata resolver, and subtitle resolver.
 * Replaces the cover/metadata section of LibraryGraph.
 *
 * CoverRecoveryHelper owns the background repair scope and binds CoverSelfHealer from the
 * same singleton definition, avoiding release-only redirect providers after R8 optimization.
 */
@OptIn(UnstableApi::class)
internal object LibraryCoverModule {

    /**
     * Owns the cover recovery coroutine scope registered with graph shutdown.
     */
    private class CoverRecoveryResources(
        private val scope: CoroutineScope
    ) : Closeable {
        override fun close() {
            scope.coroutineContext.get(Job)?.cancel()
        }
    }

    val module: Module = module {
        single { CoverExtractor(get()) }

        single { MetadataResolver(get<VfsFileInterface>()) }

        single<CoverUriResolver> { AndroidCoverUriResolver(get()) }

        single<CoverRecoveryHelper> {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val helper = CoverRecoveryHelper(
                bookDao = get<AppDatabase>().bookDao(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                coverExtractor = get(),
                scope = scope,
                fileReader = get<VfsFileInterface>(),
                absItemMirrorDao = get<AppDatabase>().absItemMirrorDao(),
                absCoverStoreProvider = { getOrNull() }
            )
            GraphClosePolicy.register(
                stage = GraphClosePolicy.Stage.Library,
                closeable = CoverRecoveryResources(scope)
            )
            helper
        } bind CoverSelfHealer::class

        single<CoverRecoveryGateway> {
            CoverRecoveryGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                coverSelfHealer = get<CoverSelfHealer>()
            )
        }

        single<CoverAssetGateway> {
            CoverAssetGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                coverExtractor = get()
            )
        }

        single<MetadataRefreshGateway> {
            MetadataRefreshGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                chapterDao = get<AppDatabase>().chapterDao(),
                coverRecoveryGateway = get(),
                metadataResolver = get(),
                database = get()
            )
        }

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
