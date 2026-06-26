# :runtime:lifecycle

## Interface

- Ordered graph shutdown policy through `GraphClosePolicy`.
- Lifecycle phase names shared by the app composition root and runtime owners.

## Allowed Dependencies

- None beyond the Android library toolchain and local unit test runtime.

## Forbidden Dependencies

- `:app`
- Android framework runtime APIs
- Koin module aggregation
- Media, ABS, library, data, application, event, UI, or widget implementation packages

## Verification

```powershell
.\gradlew.bat --no-problems-report :runtime:lifecycle:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
```
