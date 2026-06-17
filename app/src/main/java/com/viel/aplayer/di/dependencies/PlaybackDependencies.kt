package com.viel.aplayer.di.dependencies

import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.PlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PlaybackRootLookup
import com.viel.aplayer.media.PlaybackSourcePreflight

/**
 * Playback Recovery Dependencies (Minimal persistence view for progress self-healing)
 * Exposes only the book timeline reader and progress writer required by cold-start recovery.
 */
interface PlaybackRecoveryDependencies {
    /**
     * Playback/Core Book Catalog Gateway (Track timeline lookup for progress coordinate repair)
     *
     * Lets recovery code resolve book files without seeing unrelated library or settings operations.
     */
    val bookCatalogGateway: BookCatalogGateway

    /**
     * Playback/Core Progress Gateway (Progress checkpoint lookup and write seam)
     * Lets recovery code repair persisted positions without depending on the full playback runtime surface.
     */
    val progressGateway: ProgressGateway
}

/**
 * Playback Runtime Dependencies (Foreground media-core dependency view)
 * Collects only the gateways and event sink required by playback services and PlaybackManager.
 */
interface PlaybackRuntimeDependencies : PlaybackRecoveryDependencies {
    /**
     * Playback/Core Chapter Gateway (Timeline chapter read seam)
     *
     * Lets foreground playback read chapter timelines without receiving catalog filters or metadata mutation commands.
     */
    val chapterGateway: ChapterGateway

    /**
     * Playback/Core Bookmark Gateway (Notification bookmark command seam)
     *
     * Lets foreground playback create bookmarks from notification actions without depending on chapter or catalog operations.
     */
    val bookmarkGateway: BookmarkGateway

    /**
     * Playback/Core Book Availability Gateway (Status-writing reachability seam)
     * Keeps media failover logic on the availability interface instead of the broad library facade.
     */
    val bookAvailabilityGateway: BookAvailabilityGateway

    /**
     * Playback Plan Gateway (Playback-start read model for foreground services)
     * Provides playable plan materialization without exposing generic screen-facing library operations.
     */
    val playbackPlanGateway: PlaybackPlanGateway

    /**
     * ABS Playback Session Syncer (Remote progress session coordinator)
     * Lets playback runtime open and close remote sessions without seeing catalog synchronization modules.
     */
    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    /**
     * Playback Source Preflight (Persisted root lifecycle gate)
     * Allows playback startup to block unavailable roots before constructing media sources.
     */
    val playbackSourcePreflight: PlaybackSourcePreflight

    /**
     * Playback Domain Event Sink (Media-core outcome publisher)
     * Publishes playback facts for the application bridge without exposing app-shell event commands.
     */
    val playbackDomainEventSink: PlaybackDomainEventSink
}

/**
 * VFS Playback Dependencies (Media data-source dependency view)
 * Limits VfsPlaybackDataSource factories to file lookup and VFS reading capabilities.
 */
interface VfsPlaybackDependencies {
    /**
     * Virtual File System Interface (File-content access for playback reads)
     * Keeps data sources on the VFS reader seam without exposing scanner or library mutation capabilities.
     */
    val vfsFileInterface: VfsFileInterface

    /**
     * Playback File Resolver (Media-id to physical-file lookup)
     * Provides the data source with only the lookup needed to bind Media3 requests to stored book files.
     */
    val playbackFileLookup: PlaybackFileLookup

    /**
     * Playback Root Resolver (Media-id root lookup for manual-cache routing)
     * Allows manual-cache playback data sources to identify local SAF roots without depending on LibraryRootDao directly.
     */
    val playbackRootLookup: PlaybackRootLookup
}
