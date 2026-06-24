package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.application.download.CacheMaintenanceCommands
import com.viel.oto.application.download.CacheStatisticsProvider
import com.viel.oto.application.download.DefaultCacheMaintenanceCommands
import com.viel.oto.application.download.DownloadController
import com.viel.oto.application.download.DownloadManagementReadModel
import com.viel.oto.application.download.DownloadStatusReadModel
import com.viel.oto.application.download.RoomDownloadManagementReadModel
import com.viel.oto.application.download.RoomDownloadStatusReadModel
import com.viel.oto.data.db.AppDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Presentation-facing download read models and cache maintenance commands.
 * Kept separate from DownloadModule so UI-facing projections can change independently.
 */
@OptIn(UnstableApi::class)
internal object DownloadReadModelModule {

    val module: Module = module {
        single<DownloadStatusReadModel> {
            RoomDownloadStatusReadModel(
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                bookDao = get<AppDatabase>().bookDao()
            )
        }

        single<DownloadManagementReadModel> {
            RoomDownloadManagementReadModel(
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                bookDao = get<AppDatabase>().bookDao()
            )
        }

        single { CacheStatisticsProvider(get(), get<AppDatabase>().downloadMetadataDao()) }

        single<CacheMaintenanceCommands> {
            DefaultCacheMaintenanceCommands(
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadController = get<DownloadController>()
            )
        }
    }
}
