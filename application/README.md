# :application

## Interface

- Scene read models for home, detail, search, player, settings, recovery, and downloads.
- Command surfaces for user intent such as playback preparation, root management, metadata edits, backup, restore, and manual downloads.
- Use cases that coordinate persisted data, library import, ABS, and playback plans.

## Allowed Dependencies

- `:shared`
- `:data:store`
- `:library:import`
- `:library:vfs`
- `:media:playback`
- `:abs`
- `:runtime:observability`

## Forbidden Dependencies

- `:app`
- Compose UI
- Android service entrypoints
- Widget runtime
- Event feedback delivery

## Verification

```powershell
.\gradlew.bat --no-problems-report :application:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
```
