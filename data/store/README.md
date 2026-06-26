# :data:store

## Interface

- Room database, DAOs, entities, schema export, and migration fixtures.
- DataStore-backed settings, credentials, and search-history persistence.
- Gateway contracts and persistence implementations for books, chapters, bookmarks, progress, roots, availability, metadata, covers, cache cleanup, and ABS mirror state.

## Allowed Dependencies

- `:shared` for pure settings/value models and shared resource identifiers.
- `:runtime:lifecycle` for ordered shutdown registration.
- `:runtime:observability` for diagnostic logging contracts.
- AndroidX Room, DataStore, Core KTX, and Koin Android runtime required by persistence implementations.

## Forbidden Dependencies

- `:app`
- Compose UI, widget rendering, Android service entrypoints, or app shell adapters
- ABS DTO parsing or remote protocol orchestration
- Media metadata parsing, playback runtime, scan scheduling, or VFS source implementation ownership

## Verification

```powershell
.\gradlew.bat --no-problems-report :data:store:compileDebugKotlin
.\gradlew.bat --no-problems-report :data:store:testDebugUnitTest
.\gradlew.bat --no-problems-report :app:testDebugUnitTest --tests "com.viel.oto.architecture.ReleasePolicyTest"
```
