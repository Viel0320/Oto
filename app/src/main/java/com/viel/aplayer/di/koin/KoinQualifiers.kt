package com.viel.aplayer.di.koin

import org.koin.core.qualifier.named

internal val UiEventScopeQualifier = named("uiEventScope")
internal val DownloadScopeQualifier = named("downloadScope")
internal val VfsPlaybackDependenciesQualifier = named("vfsPlaybackDependencies")
internal val DownloadRuntimeDependenciesQualifier = named("downloadRuntimeDependencies")
internal val ManualDownloadActionDependenciesQualifier = named("manualDownloadActionDependencies")
internal val LibrarySyncWorkerDependenciesQualifier = named("librarySyncWorkerDependencies")
internal val AbsSyncWorkerDependenciesQualifier = named("absSyncWorkerDependencies")
internal val PlaybackRecoveryDependenciesQualifier = named("playbackRecoveryDependencies")
