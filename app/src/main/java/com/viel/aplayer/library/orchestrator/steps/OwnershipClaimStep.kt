package com.viel.aplayer.library.orchestrator.steps

// Safety Escape Import (Imports the package-level String.escapeJson extension to ensure secure escaping of JSON reserved characters)
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.PendingScanActionEntity
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.BookDraftFactory
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportSourceRef
import com.viel.aplayer.library.orchestrator.ReservationResult
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.orchestrator.escapeJson
import java.util.UUID

// Import Correction (Ensures package names for ChapterCandidate and MetadataSuggestion align with definitions in com.viel.aplayer.library)

/**
 * Ownership Claim Decision Step
 *
 * This step does not modify database states directly. Instead, it evaluates the in-memory ClaimLedger.
 * Decides whether the importing audiobook is cleared for immediate insertion, silently updates existing ownership records,
 * or transitions to the PendingAction conflict queue for user decision.
 * Additionally, it handles entity mapping for BookDraft, BookEntity, BookFileEntity, and ChapterEntity,
 * returning the compiled ImportRunResult to align with BookImporter and ScanSessionRunner.
 */
// Visibility Restriction (Declares the class as internal to prevent public type exposure issues from referenced internal dependencies)
@OptIn(UnstableApi::class)
/**
 * Ownership Claim Decision Step.
 * Refactored to discard legacy generic ImportStep<I, O> and StepResult wrapper bounds.
 * The execute method yields a direct ImportRunResult, propagating runtime exceptions directly.
 */
internal class OwnershipClaimStep(
    private val context: Context,
    // Inject the factory to delegate draft construction and entity mapping details.
    private val draftFactory: BookDraftFactory
) {

    /**
     * Resolves ownership claims. Directly processes CoverExtractedResult and returns ImportRunResult.
     */
    suspend fun execute(
        input: CoverExtractedResult,
        context: ImportContext
    ): ImportRunResult {
        val readyImports = mutableListOf<ImportCommand.CreateReadyBook>()
        val refreshedBooks = mutableListOf<ImportCommand.RefreshExistingBook>()
        val pendingActions = mutableListOf<ImportCommand.CreatePendingAction>()
        val failures = mutableListOf<ImportCommand.RecordFailure>()


        // ==========================================
        // 1. Process CUE draft ownership and conflict resolution
        // ==========================================
        input.cueBooks.forEach { cueBook ->
            val cue = cueBook.draft.sourceFile
            val missingCount = cueBook.draft.missingCount

            if (cueBook.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(cue.vfsDisplayId(), "CUE references no resolvable audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.CUE, cue.vfsDisplayId(), cue.displayName)
            val claimedIdentities = cueBook.audioRefs.map { it.identity } + cue.identity
            // Ownership validation passes current parent VFS path to restrict conflict scoping to the same folder.
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = claimedIdentities,
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = cue.parentSourcePath
            )
            
            if (!reservation.reserved) {
                // Silently updates ownership details if the CUE is fully scanned and has no current run-time conflicts.
                if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context, refreshedBooks)) return@forEach
                pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
                return@forEach
            }
            if (missingCount > 0) {
                pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
                return@forEach
            }

            // Merge metadata suggestions
            val firstAudioMeta = draftFactory.firstManifestAudioMetadata(cueBook.audioRefs)
            val mergedMeta = draftFactory.resolveManifestBookMetadata(
                manifestMetadata = cueBook.draft.result.metadata,
                firstAudio = firstAudioMeta,
                sourceFile = cue,
                // The manifest description was already fetched via folder snapshot inside the parser;
                // Reuses the parsed results instead of calling listChildren sequentially.
                sidecarDescription = cueBook.draft.result.sidecarDescription
            )

            val entryToKey = cueBook.draft.resolvedAudioKeys
            val chapters = cueBook.draft.result.chapters.mapNotNull { chapter ->
                entryToKey[chapter.fileKey]?.let { chapter.copy(fileKey = it) }
            }

            val draft = draftFactory.buildManifestDraft(
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
                // Parameter Correction (Fixes parameter name misalignment from coverResult to cover)
                cover = cueBook.coverResult
            )
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        // ==========================================
        // 2. Process M3U8 draft ownership and conflict resolution
        // ==========================================
        input.m3u8Books.forEach { m3u8Book ->
            val m3u8 = m3u8Book.draft.sourceFile
            val missingCount = m3u8Book.draft.missingCount

            if (m3u8Book.audioRefs.isEmpty()) {
                failures.add(ImportCommand.RecordFailure(ImportFailure(m3u8.vfsDisplayId(), "M3U8 references no resolvable local audio")))
                return@forEach
            }

            val source = ImportSourceRef(AudiobookSchema.SourceType.M3U8, m3u8.vfsDisplayId(), m3u8.displayName)
            val claimedIdentities = m3u8Book.audioRefs.map { it.identity } + m3u8.identity
            // Ownership validation passes current parent VFS path to restrict conflict scoping to the same folder.
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = claimedIdentities,
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = m3u8.parentSourcePath
            )
            
            if (!reservation.reserved) {
                if (missingCount == 0 && maybeRefreshExistingBook(claimedIdentities, reservation, context, refreshedBooks)) return@forEach
                pendingActions.add(createConflict(scanId = context.scanId, source = source, reservation = reservation))
                return@forEach
            }
            if (missingCount > 0) {
                pendingActions.add(createPartial(scanId = context.scanId, source = source, missingCount = missingCount))
                return@forEach
            }

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

            val draft = draftFactory.buildManifestDraft(
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
                // Parameter Correction (Fixes parameter name misalignment from coverResult to cover)
                cover = m3u8Book.coverResult
            )
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        // ==========================================
        // 3. Process Heuristic aggregated audiobook ownership and conflict resolution
        // ==========================================
        input.aggregatedBooks.forEach { aggBook ->
            val orderedFiles = aggBook.plan.chapters.map { it.audio }
            val firstChapter = orderedFiles.first()
            val source = ImportSourceRef(
                sourceType = AudiobookSchema.SourceType.GENERATED_M3U8,
                sourceUri = "generated://${firstChapter.file.parentSourceKey}/${orderedFiles.joinToString("-") { it.file.sourceIdentity.hashCode().toString() }}",
                displayName = aggBook.plan.title
            )
            
            // Ownership validation passes the first audio track's VFS folder path.
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = orderedFiles.map { it.file.identity },
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = firstChapter.file.parentSourcePath
            )
            if (reservation.reserved) {
                // Sidecar synopsis for heuristic audio aggregations is compiled in parser stages;
                // Merges existing audio tags with parsed sidecars directly without querying physical files again.
                val firstAudioMeta = firstChapter.metadata
                val description = firstAudioMeta.description.ifBlank {
                    aggBook.plan.sidecarDescription.orEmpty()
                }

                val draft = draftFactory.buildGeneratedDraft(aggBook.bookId, source, aggBook.plan, description, aggBook.coverResult)
                readyImports.add(ImportCommand.CreateReadyBook(draft))
            } else {
                pendingActions.add(createConflict(context.scanId, source, reservation))
            }
        }

        // ==========================================
        // 4. Process Single Audio draft ownership and conflict resolution
        // ==========================================
        input.singleBooks.forEach { singleBook ->
            val audio = singleBook.audioRef
            val source = ImportSourceRef(AudiobookSchema.SourceType.SINGLE_AUDIO, audio.file.vfsDisplayId(), audio.file.displayName)
            // Ownership validation passes parent VFS folder path.
            val reservation = context.runClaimLedger.reserve(
                source = source,
                files = listOf(audio.file.identity),
                existingClaimIndex = context.existingClaimIndex,
                currentParentSourcePath = audio.file.parentSourcePath
            )
            
            if (!reservation.reserved) {
                pendingActions.add(createConflict(context.scanId, source, reservation))
                return@forEach
            }

            // Single audio track ignores sidecar fallbacks;
            // The final description comes strictly from internal media metadata.
            val description = audio.metadata.description

            val draft = draftFactory.buildSingleAudioDraft(singleBook.bookId, audio, description, singleBook.coverResult)
            readyImports.add(ImportCommand.CreateReadyBook(draft))
        }

        val runResult = ImportRunResult(
            scanId = context.scanId,
            readyImports = readyImports,
            refreshedBooks = refreshedBooks,
            pendingActions = pendingActions,
            failures = failures
        )
        return runResult
    }

    // =========================================================================
    // 5. Subroutines for draft assembly and claim checking mirroring legacy ImportOrchestrator behavior
    // =========================================================================

    private fun maybeRefreshExistingBook(
        claimedIdentities: List<FileIdentity>,
        reservation: ReservationResult,
        context: ImportContext,
        refreshedBooks: MutableList<ImportCommand.RefreshExistingBook>
    ): Boolean {
        if (reservation.runConflicts.isNotEmpty()) return false
        val claim = context.existingClaimIndex.completeExistingClaim(claimedIdentities) ?: return false
        refreshedBooks.add(ImportCommand.RefreshExistingBook(claim.bookId, claim.files))
        return true
    }

    private fun createConflict(scanId: String, source: ImportSourceRef, reservation: ReservationResult): ImportCommand.CreatePendingAction {
        val existingBookId = reservation.existingConflicts.firstOrNull()?.bookId
        val actionKey = "CONFLICT:${source.sourceUri}:${reservation.existingConflicts.joinToString { it.id }}:${reservation.runConflicts.joinToString { it.sourceUri }}"
        return ImportCommand.CreatePendingAction(PendingScanActionEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = scanId,
            actionKey = actionKey,
            type = AudiobookSchema.PendingActionType.CONFLICT,
            bookId = existingBookId,
            // JSON Safety Escape (Escapes sourceUri using escapeJson extension before composing JSON blocks)
            // Ensures backslashes or quotation marks inside URIs do not break structural JSON boundaries or crash parsers.
            payloadJson = "{\"sourceUri\":\"${source.sourceUri.escapeJson()}\"}",
            message = "Source \"${source.displayName}\" conflicts with an existing or earlier source.",
            lastSeenScanId = scanId
        ))
    }

    private fun createPartial(scanId: String, source: ImportSourceRef, missingCount: Int): ImportCommand.CreatePendingAction =
        ImportCommand.CreatePendingAction(PendingScanActionEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = scanId,
            actionKey = "PARTIAL:${source.sourceUri}:$missingCount",
            type = AudiobookSchema.PendingActionType.PARTIAL_NEW_BOOK,
            // JSON Safety Escape (Escapes sourceUri using escapeJson extension before composing JSON blocks)
            // Ensures backslashes or quotation marks inside URIs do not break structural JSON boundaries or crash parsers.
            payloadJson = "{\"sourceUri\":\"${source.sourceUri.escapeJson()}\",\"missingCount\":$missingCount}",
            message = "Source \"${source.displayName}\" is missing $missingCount referenced file(s).",
            lastSeenScanId = scanId
        ))

    private fun FileRef.vfsDisplayId(): String =
        // Uses rootId/sourcePath as log identifiers to avoid outputting provider URIs.
        "vfs://$rootId/$sourcePath"

    companion object {
        private const val TAG = "OwnershipClaimStep"
    }
}
