package com.viel.oto.di

import com.viel.oto.data.cover.AndroidCoverUriResolver
import com.viel.oto.data.cover.CoverAssetGateway
import com.viel.oto.data.cover.CoverAssetGatewayImpl
import com.viel.oto.data.cover.CoverImageWriter
import com.viel.oto.data.cover.CoverRecoveryGateway
import com.viel.oto.data.cover.CoverRecoveryGatewayImpl
import com.viel.oto.data.cover.CoverRecoveryArtworkSource
import com.viel.oto.data.cover.CoverRecoveryHelper
import com.viel.oto.data.cover.CoverSelfHealer
import com.viel.oto.data.cover.CoverUriResolver
import com.viel.oto.data.cover.RemoteCoverStore
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.metadata.MetadataRefreshGateway
import com.viel.oto.data.metadata.MetadataRefreshGatewayImpl
import com.viel.oto.data.metadata.MetadataRefreshSource
import com.viel.oto.logger.ScanWorkflowLogSink
import com.viel.oto.logger.SecureDiagnosticLogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.Closeable

/**
 * Data-owned cover recovery, cover asset, and metadata refresh gateways.
 *
 * Media parser adapters provide CoverImageWriter, CoverRecoveryArtworkSource, and
 * MetadataRefreshSource from the metadata module. This module keeps the Room-backed gateway
 * definitions close to their DAOs while consuming those parser capabilities only through data-owned
 * interfaces.
 *
 * CoverRecoveryHelper owns the background repair scope and binds CoverSelfHealer from the
 * same singleton definition, avoiding release-only redirect providers after R8 optimization.
 */
object LibraryCoverModule {

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
        single<CoverUriResolver> { AndroidCoverUriResolver(get()) }

        single<CoverRecoveryHelper> {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val helper = CoverRecoveryHelper(
                bookDao = get<AppDatabase>().bookDao(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                scope = scope,
                coverArtworkSource = get<CoverRecoveryArtworkSource>(),
                absItemMirrorDao = get<AppDatabase>().absItemMirrorDao(),
                remoteCoverStoreProvider = { getOrNull<RemoteCoverStore>() },
                workflowLogSink = ScanWorkflowLogSink
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
                coverSelfHealer = get<CoverSelfHealer>(),
                workflowLogSink = ScanWorkflowLogSink
            )
        }

        single<CoverAssetGateway> {
            CoverAssetGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                coverImageWriter = get<CoverImageWriter>()
            )
        }

        single<MetadataRefreshGateway> {
            MetadataRefreshGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                chapterDao = get<AppDatabase>().chapterDao(),
                coverRecoveryGateway = get(),
                metadataRefreshSource = get<MetadataRefreshSource>(),
                database = get(),
                diagnosticLogSink = SecureDiagnosticLogSink
            )
        }

    }
}
