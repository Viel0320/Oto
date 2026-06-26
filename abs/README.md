# ABS Module

## Responsibility

Owns the AudiobookShelf anti-corruption layer: credentials, DTOs, Moshi API client, catalog mapping, sync orchestration, progress sync, cover cache, and ABS VFS source adapter.

## Public Interfaces

- `AbsApiClient`
- `AbsCredentialStore`
- `AbsSourceProvider`
- `AbsCatalogSynchronizer`
- `AbsSyncTaskCoordinator`
- `AbsPlaybackSessionSyncGateway`
- `AbsSyncFeedbackSink`

## Allowed Dependencies

- `:data:store` for persisted catalog, root, progress, and credential-adjacent gateway contracts.
- `:library:vfs` and `:library:import` for source-provider and root-availability contracts.
- `:media:metadata` and `:media:playback` for cover extraction and remote playback-session contracts.
- `:shared`, `:work:policy`, and runtime modules for shared policy, WorkManager policy, and lifecycle support.

## Forbidden Dependencies

- No direct dependency on app UI, Compose, widget, or event rendering.
- Do not expose raw ABS DTOs to UI or general library logic.
- Do not add a broad facade that centralizes unrelated ABS, library, media, and settings responsibilities.

## Verification

```powershell
.\gradlew.bat --no-problems-report :abs:testDebugUnitTest
.\gradlew.bat --no-problems-report :abs:testDebugUnitTest --tests "com.viel.oto.abs.*"
.\gradlew.bat --no-problems-report assembleDebug
```
