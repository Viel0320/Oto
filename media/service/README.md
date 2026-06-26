# :media:service

## Interface

- Owns Media3 playback service entrypoints, offline download foreground service, audio focus, playback failure handling, and Android notification rendering.
- Exposes narrow adapter contracts for app launch intents, playback resumption planning, widget state projection, manual download commands, and notification resources.
- Keeps `MainActivity`, Glance widget classes, Compose UI, and application use-case implementations outside the service module.

## Allowed Dependencies

- `:settings:model` for playback seek-step and settings value objects.
- `:data:store` for persisted book, download, and progress gateway contracts.
- `:media:playback` for playback plans, Media3 item construction, preflight, data sources, and playback events.
- `:runtime:observability` for release-safe diagnostics.

## Forbidden Dependencies

- Do not depend on `:app`, `MainActivity`, app `R`, Compose UI, or widget implementation classes.
- Do not depend on `application.*` use-case or command implementations; add a narrow service contract and adapt it in the app composition root.
- Do not update widget storage directly from service code; route snapshots through `PlaybackWidgetStateSink`.

## Verification

```powershell
.\gradlew.bat --no-problems-report :media:service:compileDebugKotlin
.\gradlew.bat --no-problems-report :media:service:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report assembleDebug
```
