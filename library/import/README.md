# :library:import

## Interface

- Owns library scan scheduling, source inventory, import orchestration, root lifecycle, and availability checks.
- Exposes scan/root contracts such as `ScanScheduler`, `ScanNoticeSink`, `LibraryRootGateway`, and `AbsRootCredentialGateway`.
- Provides `LibraryScanModule` for Koin wiring; the app only aggregates this module.

## Allowed Dependencies

- `:data:store` for Room/DataStore gateway contracts and persistence services.
- `:library:vfs` for SAF/WebDAV/VFS file access and source-provider contracts.
- `:media:metadata` for manifest, metadata, cover, and parser behavior used by import.
- `:shared`, `:runtime:lifecycle`, `:runtime:observability`, and `:work:policy`.

## Forbidden Dependencies

- Do not depend on `:app`, Compose UI, app resources, or feedback delivery implementation.
- Do not depend on ABS implementation. ABS root credentials and availability enter through narrow interfaces.
- Do not own playback runtime, MediaSession service, widgets, or notification behavior.

## Verification

```powershell
.\gradlew.bat --no-problems-report :library:import:compileDebugKotlin
.\gradlew.bat --no-problems-report :library:import:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report assembleDebug
```
