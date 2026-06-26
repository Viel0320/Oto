# :media:playback

## Interface

- Owns playback plan models, plan materialization, VFS playback URIs, Media3 controller runtime, progress persistence coordination, sidecar subtitle loading, and playback recovery policies.
- Exposes `PlaybackManager`, `PlaybackPlanGateway`, `PlaybackSourcePreflight`, `SubtitleGateway`, `PlaybackDomainEventSink`, `PlaybackSessionTokenFactory`, and `RemotePlaybackSessionSyncGateway`.
- Keeps Android service class selection and ABS protocol session sync behind app-side adapters.

## Allowed Dependencies

- `:shared` for playback setting value objects and pure seek-step policy.
- `:data:store` for persisted book, file, root, and progress gateway contracts.
- `:library:vfs` for VFS playback stream access and remote availability exception contracts.
- `:media:metadata` for subtitle line models carried by playback plans.
- `:runtime:observability` for diagnostics.

## Forbidden Dependencies

- Do not depend on `:app`, app resources, `MainActivity`, widgets, or Compose UI.
- Do not depend on `media/service` implementation classes.
- Do not depend on ABS implementation classes; use `RemotePlaybackSessionSyncGateway`.

## Verification

```powershell
.\gradlew.bat --no-problems-report :media:playback:compileDebugKotlin
.\gradlew.bat --no-problems-report :media:playback:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report assembleDebug
```
