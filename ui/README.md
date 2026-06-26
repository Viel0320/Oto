# UI Module

## Ownership

`:ui` owns Compose routes, screens, overlays, ViewModels, theme, presentation formatters, and app locale UI support.

## Allowed Dependencies

`:ui` may depend on application read models and commands, event delivery contracts, shared resources/settings, media playback presentation models, library VFS/import models, network policy exceptions, and runtime observability.

## Disallowed Responsibilities

- No application `Activity`, `Application`, manifest ownership, signing, version generation, or AboutLibraries raw resource ownership.
- No Room, network client, playback service, widget receiver, WorkManager worker, or ABS implementation logic.
- No bypass around application commands/read models to mutate data directly.

## Verification

```powershell
.\gradlew.bat --no-problems-report :ui:compileDebugKotlin
.\gradlew.bat --no-problems-report :ui:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report :app:testDebugUnitTest --tests "com.viel.oto.architecture.*"
.\gradlew.bat --no-problems-report assembleDebug
```
