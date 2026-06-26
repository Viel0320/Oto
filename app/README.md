# :app

## Interface

- Android application packaging through `AndroidManifest.xml`, app signing, build variants, backup policy, and release metadata.
- `MainActivity`, `OtoApplication`, and `OtoKoinApplication` as the app shell and composition root.
- App-owned adapters that must bind Android entrypoints to domain Android library modules.
- Shared Android resources are consumed from `:shared`; `:app` should not add a local `src/main/res` tree.

## Allowed Dependencies

- Domain Android library modules declared in `settings.gradle.kts`.
- Android shell dependencies required by Activity startup, WorkManager workers, app widget routing, Koin startup, Coil image cache setup, and AboutLibraries metadata rendering.

## Forbidden Dependencies

- Feature business rules that belong in `:application`, `:media:*`, `:library:*`, `:abs`, `:data:store`, `:ui`, or `:widget`.
- Raw ABS DTO mapping, Room gateway implementation, VFS source implementation, playback implementation, or Compose screen implementation.
- KSP processors unless app-owned generated sources are introduced and documented here.
- Local app resources under `app/src/main/res`; add shared resources to `shared/src/main/res` instead.

## Verification

```powershell
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report testDebugUnitTest
.\gradlew.bat --no-problems-report assembleDebug
```
