package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.download.CacheMaintenanceCommands
import com.viel.aplayer.application.download.CacheStatisticsProvider
import com.viel.aplayer.application.download.DefaultCacheMaintenanceCommands
import com.viel.aplayer.application.download.DownloadController
import com.viel.aplayer.application.download.DownloadManagementReadModel
import com.viel.aplayer.application.download.DownloadStatusReadModel
import com.viel.aplayer.application.download.RoomDownloadManagementReadModel
import com.viel.aplayer.application.download.RoomDownloadStatusReadModel
import com.viel.aplayer.data.db.AppDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Presentation-facing download read models and cache maintenance commands.
 * Kept separate from DownloadModule so UI-facing projections can change independently.
 */
@UnstableApi
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
