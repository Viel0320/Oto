# :runtime:observability

## Interface

- Runtime diagnostic logging contracts and Android-backed logger implementations.
- Domain-specific loggers for ABS, playback, cache, cover loading, sync, and VFS diagnostics.
- Safe log sanitization helpers used by modules that emit runtime diagnostics.

## Allowed Dependencies

- Android library runtime for Logcat-backed diagnostics.
- Coil and OkHttp types needed by image and network diagnostic adapters.

## Forbidden Dependencies

- `:app`
- Room entity types as public logging contracts
- UI screen, widget rendering, playback control, ABS sync orchestration, or library import workflow ownership
- Broad observability facade APIs that collect unrelated operational decisions

## Verification

```powershell
.\gradlew.bat --no-problems-report :runtime:observability:testDebugUnitTest
.\gradlew.bat --no-problems-report compileDebugKotlin
```
