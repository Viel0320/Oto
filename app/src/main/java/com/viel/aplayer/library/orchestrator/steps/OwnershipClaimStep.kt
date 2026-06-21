package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.BookDraftFactory
import com.viel.aplayer.library.orchestrator.ConflictResolution
import com.viel.aplayer.library.orchestrator.ConflictResolver
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportSourceRef
import com.viel.aplayer.library.orchestrator.draftmodels.BookDraft
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedCue
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedM3u8
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult

/**
 * Ownership Claim Decision Step
 *
 * Resolves scan-time file ownership without writing database rows directly.
 * It emits import commands for ready books, partial manifest books, existing-book refreshes,
 * and higher-priority ownership replacements.
 */
@OptIn(UnstableApi::class)
internal class OwnershipClaimStep(
    @Suppress("unused") private val context: Context,
    private val draftFactory: BookDraftFactory
) {
    private val conflictResolver = ConflictResolver()

    /**
     * Pipeline command compilation.
     *
     * Converts parsed scan results into persistence commands. Conflicts are resolved by source priority
     * or ignored when an equal/higher-priority owner already exists.
     */
    suspend fun execute(
        input: CoverExtractedResult,
        context: ImportContext
    ): ImportRunResult {
        val readyImports = mutableListOf<ImportCommand.CreateReadyBook>()
        val refreshedBooks = mutableListOf<ImportCommand.RefreshExistingBook>()
        val replacementImports = mutableListOf<ImportCommand.ReplaceExistingBooks>()
        val failures = mutableListOf<ImportCommand.RecordFailure>()

        input.cueBooks.forEach { cueBook ->
            val cue = cueBook.draft.sourceFile
            if (cueBook.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(cue.vfsDisplayId(), "CUE references no resolvable audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.CUE, cue.vfsDisplayId(), cue.displayName)
            val claimedIdentities = cueBook.audioRefs.map { it.identity } + cue.identity
            handleManifestClaim(
                source = source,
                claimedIdentities = claimedIdentities,
                currentParentSourcePath = cue.parentSourcePath,
                missingCount = cueBook.draft.missingCount,
                context = context,
                readyImports = readyImports,
                refreshedBooks = refreshedBooks,
                replacementImports = replacementImports,
                buildDraft = { bookStatus -> buildCueDraft(cueBook, bookStatus) }
            )
        }

        input.m3u8Books.forEach { m3u8Book ->
            val m3u8 = m3u8Book.draft.sourceFile
            if (m3u8Book.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.vfsDisplayId(), "M3U8 references no resolvable local audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.vfsDisplayId(), m3u8.displayName)
            val claimedIdentities = m3u8Book.audioRefs.map { it.identity } + m3u8.identity
            handleManifestClaim(
                source = source,
                claimedIdentities = claimedIdentities,
                currentParentSourcePath = m3u8.parentSourcePath,
                missingCount = m3u8Book.draft.missingCount,
                context = context,
                readyImports = readyImports,
                refreshedBooks = refreshedBooks,
                replacementImports = replacementImports,
                buildDraft = { bookStatus -> buildM3u8Draft(m3u8Book, bookStatus) }
            )
        }

        input.aggregatedBooks.forEach { aggBook ->
            val orderedFiles = aggBook.plan.chapters.map { it.audio }
            val firstChapter = orderedFiles.first()
            val source = ImportSourceRef(
                sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
                sourceUri = "generated://${firstChapter.file.parentSourceKey}/${orderedFiles.joinToString("-") { it.file.sourceIdentity.hashCode().toString() }}",
                displayName = aggBook.plan.title
            )

            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = orderedFiles.map { it.file.identity },
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = firstChapter.file.parentSourcePath
            )
            if (!reservation.reserved) return@forEach

            val firstAudioMeta = firstChapter.metadata
            val description = firstAudioMeta.description.ifBlank {
                aggBook.plan.sidecarDescription.orEmpty()
            }
            val draft = draftFactory.buildGeneratedDraft(aggBook.bookId, source, aggBook.plan, description, aggBook.coverResult)
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        input.singleBooks.forEach { singleBook ->
            val audio = singleBook.audioRef
            val source = ImportSourceRef(AudiobookSchema.SourceType.SINGLE_AUDIO, audio.file.vfsDisplayId(), audio.file.displayName)
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = listOf(audio.file.identity),
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = audio.file.parentSourcePath
            )
            if (!reservation.reserved) return@forEach

            val draft = draftFactory.buildSingleAudioDraft(
                bookId = singleBook.bookId,
                audio = audio,
                description = audio.metadata.description,
                cover = singleBook.coverResult
            )
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        return ImportRunResult(
            scanId = context.scanId,
            readyImports = readyImports,
            refreshedBooks = refreshedBooks,
            failures = failures,
            replacementImports = replacementImports
        )
    }

    /**
     * Partial and replacement policy.
     *
     * A reserved manifest becomes a READY or PARTIAL new book. A blocked manifest refreshes the same existing book,
     * replaces lower-priority persisted owners, or yields silently to equal/higher-priority owners.
     */
    private suspend fun handleManifestClaim(
        source: ImportSourceRef,
        claimedIdentities: List<FileIdentity>,
        currentParentSourcePath: String?,
        missingCount: Int,
        context: ImportContext,
        readyImports: MutableList<ImportCommand.CreateReadyBook>,
        refreshedBooks: MutableList<ImportCommand.RefreshExistingBook>,
        replacementImports: MutableList<ImportCommand.ReplaceExistingBooks>,
        buildDraft: suspend (bookStatus: AudiobookSchema.BookStatus) -> BookDraft
    ) {
        val reservation = context.runClaimLedger.reserve(
            source = source,
            files = claimedIdentities,
            existingClaimIndex = context.existingClaimIndex,
            currentParentSourcePath = currentParentSourcePath
        )
        when (val decision = conflictResolver.resolveManifestOwnership(
            source = source,
            claimedIdentities = claimedIdentities,
            reservation = reservation,
            existingClaimIndex = context.existingClaimIndex,
            missingCount = missingCount
        )) {
            is ConflictResolution.CreateBook -> {
                readyImports.add(ImportCommand.CreateReadyBook(buildDraft(decision.bookStatus)))
            }
            is ConflictResolution.RefreshBook -> {
                refreshedBooks.add(ImportCommand.RefreshExistingBook(decision.bookId, decision.files))
            }
            is ConflictResolution.ReplaceBooks -> {
                replacementImports.add(
                    ImportCommand.ReplaceExistingBooks(
                        draft = buildDraft(decision.bookStatus),
                        replacedBookIds = decision.bookIds
                    )
                )
                context.runClaimLedger.promoteResolvedClaim(source, claimedIdentities)
            }
            ConflictResolution.Skip -> Unit
        }
    }

    /**
     * Manifest entity mapping.
     *
     * Builds the CUE draft after ownership is decided, applying READY or PARTIAL at the logical book level.
     */
    private suspend fun buildCueDraft(cueBook: CoverExtractedCue, bookStatus: AudiobookSchema.BookStatus): BookDraft {
        val cue = cueBook.draft.sourceFile
        val firstAudioMeta = draftFactory.firstManifestAudioMetadata(cueBook.audioRefs)
        val mergedMeta = draftFactory.resolveManifestBookMetadata(
            manifestMetadata = cueBook.draft.result.metadata,
            firstAudio = firstAudioMeta,
            sourceFile = cue,
            sidecarDescription = cueBook.draft.result.sidecarDescription
        )
        val entryToKey = cueBook.draft.resolvedAudioKeys
        val chapters = cueBook.draft.result.chapters.mapNotNull { chapter ->
            entryToKey[chapter.fileKey]?.let { chapter.copy(fileKey = it) }
        }

        return draftFactory.buildManifestDraft(
            bookId = cueBook.bookId,
            sourceType = AudiobookSchema.SourceType.CUE,
            sourceFile = cue,
            audioFiles = cueBook.audioRefs,
            chapterCandidates = chapters,
            fileTitles = emptyMap(),
            fileDurations = emptyMap(),
            title = mergedMeta.title,
            author = mergedMeta.author,
            narrator = mergedMeta.narrator,
            year = mergedMeta.year,
            description = mergedMeta.description,
            series = mergedMeta.series,
            bookStatus = bookStatus,
            cover = cueBook.coverResult
        )
    }

    /**
     * Playlist entity mapping.
     *
     * Builds the M3U8 draft after ownership is decided, preserving playlist titles and durations for resolved items.
     */
    private suspend fun buildM3u8Draft(m3u8Book: CoverExtractedM3u8, bookStatus: AudiobookSchema.BookStatus): BookDraft {
        val m3u8 = m3u8Book.draft.sourceFile
        val firstAudioMeta = draftFactory.firstManifestAudioMetadata(m3u8Book.audioRefs)
        val mergedMeta = draftFactory.resolveManifestBookMetadata(
            manifestMetadata = m3u8Book.draft.result.metadata,
            firstAudio = firstAudioMeta,
            sourceFile = m3u8,
            sidecarDescription = m3u8Book.draft.result.sidecarDescription
        )
        val resolved = m3u8Book.draft.result.items.distinctBy { it.uri }.mapNotNull { item ->
            m3u8Book.draft.resolvedAudioKeys[item.uri]?.let { fileKey -> item to fileKey }
        }
        val fileTitles = resolved.mapNotNull { (item, fileKey) -> item.title?.let { fileKey to it } }.toMap()
        val fileDurations = resolved.mapNotNull { (item, fileKey) -> item.durationMs?.let { fileKey to it } }.toMap()

        return draftFactory.buildManifestDraft(
            bookId = m3u8Book.bookId,
            sourceType = AudiobookSchema.SourceType.M3U8,
            sourceFile = m3u8,
            audioFiles = m3u8Book.audioRefs,
            chapterCandidates = emptyList(),
            fileTitles = fileTitles,
            fileDurations = fileDurations,
            title = mergedMeta.title,
            author = mergedMeta.author,
            narrator = mergedMeta.narrator,
            year = mergedMeta.year,
            description = mergedMeta.description,
            series = mergedMeta.series,
            bookStatus = bookStatus,
            cover = m3u8Book.coverResult
        )
    }

    private fun FileRef.vfsDisplayId(): String =
        "vfs://$rootId/$sourcePath"
}
