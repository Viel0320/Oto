package com.viel.oto.event.feedback

/**
 * User-distinguishable subject within a feedback category.
 *
 * Topics follow the user-perceived task, not the component or callback that raised it. Two topics in
 * the same category aggregate independently, so playback speed and sleep timer feedback never absorb
 * each other even though both are playback control.
 */
sealed interface FeedbackTopic {
    data object ConnectionTesting : FeedbackTopic
    data object LibraryRootChange : FeedbackTopic
    data object LibrarySync : FeedbackTopic
    data object Rescan : FeedbackTopic
    data object PlaybackSpeed : FeedbackTopic
    data object SleepTimer : FeedbackTopic
    data object PlaybackSessionShutdown : FeedbackTopic
    data object PlaybackSourcePreflight : FeedbackTopic
    data object PlaybackContentAvailability : FeedbackTopic
    data object BookmarkCreation : FeedbackTopic
    data object BookDeletion : FeedbackTopic
    data object ReadStatusChange : FeedbackTopic
    data object CoverMetadataRegeneration : FeedbackTopic
    data object DeletedBookRecovery : FeedbackTopic
    data object DownloadCacheTask : FeedbackTopic
    data object DownloadNotificationPermission : FeedbackTopic

    /**
     * Exporting and importing the listener's portable app data.
     *
     * Data transfer is app-wide rather than bound to a root, book, or access form, so these topics always
     * pair with [FeedbackContext.Global].
     */
    data object DataExport : FeedbackTopic
    data object DataImport : FeedbackTopic

    /**
     * Pinning the playback widget onto the launcher home screen.
     *
     * Home-screen integration is app-wide rather than bound to a root, book, or access form, so this
     * topic always pairs with [FeedbackContext.Global].
     */
    data object HomeScreenWidget : FeedbackTopic
}

/**
 * User-meaningful object that refines an aggregation identity.
 *
 * Context narrows the topic without replacing it: two downloads for different books share the topic but
 * keep separate contexts. Context must stay free of credentials, tokens, full URLs, and unstable
 * implementation identifiers; a missing object is represented explicitly by [MissingObject] rather than
 * collapsing into a blank identity.
 */
sealed interface FeedbackContext {
    data object Global : FeedbackContext
    data object MissingObject : FeedbackContext
    data object PlaybackControl : FeedbackContext
    data class PlaybackContent(val bookId: String, val queueIndex: Int? = null) : FeedbackContext
    data class Book(val bookId: String) : FeedbackContext
    data class LibraryRoot(val rootId: String, val accessForm: LibraryAccessForm) : FeedbackContext
    data class DraftLibraryAccess(val draftId: String, val accessForm: LibraryAccessForm) : FeedbackContext
    data class DownloadCacheTask(val bookId: String) : FeedbackContext
}

/**
 * User-visible way a library becomes available.
 *
 * Local folders, WebDAV, and AudiobookShelf are peer access forms from the listener's point of view, so
 * they stay part of the aggregation identity instead of being modeled as separate top-level categories.
 */
enum class LibraryAccessForm {
    LOCAL_FOLDER,
    WEBDAV,
    AudiobookShelf
}

/**
 * Distinguishes repeated runs of the same task identity.
 *
 * A new task instance lets a fresh provisional outcome appear even after an earlier final outcome with
 * the same identity was already shown.
 */
sealed interface FeedbackTaskInstance {
    data object SingleShot : FeedbackTaskInstance
    data class Token(val value: String) : FeedbackTaskInstance
}
