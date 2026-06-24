package com.viel.aplayer.ui.libraryManagement

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.application.library.settings.SettingsAbsSyncInspection
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.FormatSettingsRootUseCase
import com.viel.aplayer.application.usecase.LibraryRootManagementUseCase
import com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.LibraryAccessFeedbackFacts
import com.viel.aplayer.logger.AbsSettingsLogger
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.ui.settings.SettingsConnectionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-scoped owner of the standalone remote-connection flow.
 *
 * Holds the WebDAV/ABS form buffer ([form]), derives the overlay's visibility from [form]'s source,
 * and owns the connection logic (delegated to [SettingsConnectionHandler]). Lifting this out of the
 * settings overlay lets "add or edit a remote (or relocate a SAF) library root" run as an app-level
 * overlay that no longer depends on settings being mounted or open.
 */
class RemoteConnectionViewModel(
    application: android.app.Application,
    private val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase,
    private val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase,
    private val testWebDavConnectionUseCase: TestWebDavConnectionUseCase,
    private val settingsQueryUseCase: SettingsQueryUseCase,
    private val settingsRootCommands: SettingsRootCommands,
    private val formatSettingsRootUseCase: FormatSettingsRootUseCase,
    private val appEventSink: AppEventSink,
    private val libraryRootManagementUseCase: LibraryRootManagementUseCase
) : ViewModel() {
    private val maintenanceUseCase = settingsLibraryMaintenanceUseCase

    private val handler = SettingsConnectionHandler(
        absSettingsConnectionUseCase = absSettingsConnectionUseCase,
        testWebDavConnectionUseCase = testWebDavConnectionUseCase,
        settingsQueryUseCase = settingsQueryUseCase,
        settingsRootCommands = settingsRootCommands,
        formatSettingsRootUseCase = formatSettingsRootUseCase,
        appEventSink = appEventSink,
        scope = viewModelScope,
        app = application
    )

    private val _form = MutableStateFlow(RemoteConnectionFormState())
    val form: StateFlow<RemoteConnectionFormState> = _form.asStateFlow()

    val absConnectionState: StateFlow<AbsConnectionUiState> = handler.absConnectionState
    val webDavConnectionState: StateFlow<WebDavConnectionUiState> = handler.webDavConnectionState

    /** Root being relocated through the SAF picker, set just before launching it; null for a new SAF root. */
    var editingSafRootId: String? = null
        private set

    fun setEditingSafRootId(id: String?) {
        editingSafRootId = id
    }

    // --- Open / close ---

    fun openWebDav() {
        handler.resetWebDavConnectionState()
        _form.value = RemoteConnectionFormState(source = RemoteConnectionSource.WebDav)
    }

    fun openAbs() {
        handler.resetAbsConnectionState()
        _form.value = RemoteConnectionFormState(source = RemoteConnectionSource.Abs)
    }

    fun openWebDavForEdit(root: SettingsRootItem) {
        handler.resetWebDavConnectionState()
        val cred = handler.getWebDavCredentials(root.credentialId)
        _form.value = RemoteConnectionFormState(
            source = RemoteConnectionSource.WebDav,
            editingRootId = root.rootId,
            webDavUrl = root.sourceUri,
            webDavUsername = cred?.username.orEmpty(),
            webDavPassword = cred?.password.orEmpty(),
            webDavDisplayName = root.displayName,
            webDavBasePath = root.basePath
        )
    }

    fun openAbsForEdit(root: SettingsRootItem) {
        handler.resetAbsConnectionState()
        viewModelScope.launch {
            val cred = handler.getAbsCredential(root.credentialId)
            _form.value = RemoteConnectionFormState(
                source = RemoteConnectionSource.Abs,
                editingRootId = root.rootId,
                absBaseUrl = root.sourceUri,
                absUsername = cred?.username.orEmpty(),
                absPassword = "",
                absSelectedLibraries = mapOf(root.basePath to root.displayName),
                absDisplayName = root.displayName
            )
        }
    }

    fun close() {
        _form.value = RemoteConnectionFormState()
        handler.resetWebDavConnectionState()
        handler.resetAbsConnectionState()
    }

    // --- WebDAV field setters (server fields invalidate the prior test result; display name does not) ---

    fun onWebDavUrlChange(value: String) {
        _form.update { it.copy(webDavUrl = value) }
        handler.resetWebDavConnectionState()
    }

    fun onWebDavUsernameChange(value: String) {
        _form.update { it.copy(webDavUsername = value) }
        handler.resetWebDavConnectionState()
    }

    fun onWebDavPasswordChange(value: String) {
        _form.update { it.copy(webDavPassword = value) }
        handler.resetWebDavConnectionState()
    }

    fun onWebDavBasePathChange(value: String) {
        _form.update { it.copy(webDavBasePath = value) }
        handler.resetWebDavConnectionState()
    }

    fun onWebDavDisplayNameChange(value: String) {
        _form.update { it.copy(webDavDisplayName = value) }
    }

    // --- ABS field setters ---

    fun onAbsBaseUrlChange(value: String) {
        _form.update { it.copy(absBaseUrl = value) }
        handler.resetAbsConnectionState()
    }

    fun onAbsUsernameChange(value: String) {
        _form.update { it.copy(absUsername = value) }
        handler.resetAbsConnectionState()
    }

    fun onAbsPasswordChange(value: String) {
        _form.update { it.copy(absPassword = value) }
        handler.resetAbsConnectionState()
    }

    fun onAbsLibrarySelected(id: String, name: String) {
        _form.update { state ->
            if (state.editingRootId != null) {
                state.copy(absSelectedLibraries = mapOf(id to name))
            } else {
                val current = state.absSelectedLibraries
                val next = if (current.containsKey(id)) {
                    current - id
                } else {
                    current + (id to name)
                }
                state.copy(absSelectedLibraries = next)
            }
        }
    }

    // --- Test / confirm ---

    fun testWebDav() {
        val f = _form.value
        handler.testWebDavConnection(
            url = f.webDavUrl.trim(),
            username = f.webDavUsername.trim(),
            password = f.webDavPassword,
            basePath = f.webDavBasePath.trim(),
            editingRootId = f.editingRootId
        )
    }

    fun confirmWebDav() {
        val f = _form.value
        if (f.editingRootId != null) {
            updateWebDavRoot(
                id = f.editingRootId,
                url = f.webDavUrl.trim(),
                username = f.webDavUsername.trim(),
                password = f.webDavPassword,
                displayName = f.webDavDisplayName.trim(),
                basePath = f.webDavBasePath.trim()
            )
        } else {
            handler.onWebDavRootSubmitted(
                url = f.webDavUrl.trim(),
                username = f.webDavUsername.trim(),
                password = f.webDavPassword,
                displayName = f.webDavDisplayName.trim(),
                basePath = f.webDavBasePath.trim()
            )
        }
        close()
    }

    fun testAbs() {
        val f = _form.value
        handler.testAbsConnection(
            baseUrl = f.absBaseUrl.trim(),
            username = f.absUsername.trim(),
            password = f.absPassword,
            editingRootId = f.editingRootId
        )
    }

    fun confirmAbs() {
        val f = _form.value
        handler.addAbsServersWithPassword(
            baseUrl = f.absBaseUrl.trim(),
            username = f.absUsername.trim(),
            password = f.absPassword,
            libraries = f.absSelectedLibraries,
            editingRootId = f.editingRootId
        )
        close()
    }

    // --- SAF roots (lifted from SettingsViewModel; routed from the app-level SAF picker) ---

    fun onLibraryRootSelected(uri: Uri) {
        settingsRootCommands.addLocalRootAndScheduleSync(uri)
    }

    fun onSafRootRelocated(id: String, newUri: Uri) {
        viewModelScope.launch {
            runCatching {
                maintenanceUseCase.updateSafRootAndScheduleSync(id, newUri)
            }.onSuccess {
                appEventSink.emitFeedback(LibraryAccessFeedbackFacts.localLibraryRelocated(id))
            }.onFailure { error ->
                ScanWorkflowLogger.error("onSafRootRelocated failed", error)
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.localLibraryRelocationFailed(id, error.message)
                )
            }
        }
    }

    private fun updateWebDavRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        viewModelScope.launch {
            runCatching {
                maintenanceUseCase.updateWebDavRootAndScheduleSync(
                    id = id,
                    url = url,
                    username = username,
                    password = password,
                    displayName = displayName,
                    basePath = basePath
                )
            }.onSuccess {
                appEventSink.emitFeedback(LibraryAccessFeedbackFacts.webDavRootUpdated(id))
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.webDavRootUpdateFailed(id, error.message)
                )
            }
        }
    }

    // --- Root management (sync / rescan / delete), surfaced from the app-level root-actions dialog ---

    fun syncAbsRoot(rootId: String) {
        viewModelScope.launch {
            when (val inspection = settingsRootCommands.inspectManualAbsSync(rootId)) {
                SettingsAbsSyncInspection.MissingRoot ->
                    appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncRootMissing())
                is SettingsAbsSyncInspection.Blocked ->
                    appEventSink.emitFeedback(inspection.fact)
                is SettingsAbsSyncInspection.Ready -> {
                    val start = AbsSettingsLogger.mark()
                    AbsSettingsLogger.logManualSyncStart(rootId = inspection.rootId, displayName = inspection.displayName)
                    if (inspection.requiresConfirmation) {
                        AbsSettingsLogger.logManualSyncRequiresConfirmation(rootId = inspection.rootId, totalItems = inspection.totalItems)
                        return@launch
                    }
                    val scheduled = settingsRootCommands.startManualAbsSync(inspection.rootId)
                    if (scheduled) {
                        AbsSettingsLogger.logManualSyncFinished(rootId = inspection.rootId, costMs = AbsSettingsLogger.elapsedMs(start))
                        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncStarted(inspection.rootId))
                    } else {
                        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncAlreadyRunning(inspection.rootId))
                    }
                }
            }
        }
    }

    fun triggerRescan(rootId: String) {
        settingsRootCommands.scheduleUserSync(rootId)
    }

    fun deleteLibraryRoot(root: SettingsRootItem) {
        viewModelScope.launch {
            AbsSettingsLogger.logDeleteServerStart(rootId = root.rootId, sourceType = root.sourceType)
            val playbackWasStopped = libraryRootManagementUseCase.deleteLibraryRoot(root.rootId)
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.rootId, playbackStopped = playbackWasStopped)
            appEventSink.emitFeedback(
                LibraryAccessFeedbackFacts.rootRemoved(
                    rootId = root.rootId,
                    sourceType = root.sourceType,
                    playbackWasStopped = playbackWasStopped
                )
            )
        }
    }
}
