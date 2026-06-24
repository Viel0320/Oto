# Architecture Boundaries

## UI Layer

UI code belongs under `ui/` and should use the existing `Route`, `Screen`,
`Overlay`, `ViewModel`, `UiState`, and `Actions` patterns.

Keep page-level state private to the page unless it is genuinely shared
application state. `OtoApp` is the app shell and should not absorb
feature-specific business rules. `OtoNavHost` should remain a thin Navigation 3
host.

For adaptive layouts, use `ui/common/layout/AppWindowSizeClass.kt` and
`LocalAppWindowSizeClass`. Preserve portrait phone, landscape phone, and
landscape tablet variants when touching screens that already split layouts.

## Application Layer

Application code coordinates user intent through read models, commands, and use
cases. It should not know about Compose widgets, Android view concerns, or raw
network DTO shape.

Use existing feature packages under `application/library/`,
`application/playback/`, `application/download/`, `application/startup/`, and
`application/usecase/`. Keep command surfaces small and scene-oriented.

## Data Layer

Room entities, DAOs, gateways, services, cache policies, and DataStore-backed
stores live under `data/`.

The data layer is packaged by feature, not by type. Each capability has its own
package under `data/<feature>/`, such as `data/book/`, `data/availability/`,
`data/cover/`, `data/root/`, `data/metadata/`, `data/progress/`, `data/scan/`,
`data/search/`, `data/subtitle/`, and `data/cleanup/`.

Within a feature package, `XxxGateway` is the application-facing interface
contract and `XxxService` is its implementation, kept side by side. There is no
longer a type-based `data/gateway/` or `data/service/` directory; do not
reintroduce one. A single capability may be split into several narrow gateways,
such as the six gateways backed by `data/book/`.

Do not bypass gateways from UI or feature ViewModels. Keep database constants in
`AudiobookSchema`. When adding or changing persisted state, update entities,
DAOs, migrations, schema exports, services, tests, and affected read models
together.

## Library And VFS

Library scanning and import belong under `library/`. VFS source access belongs
under `library/vfs/` and `library/vfs/sourceProvider/`.

Use `VirtualFileSystem`, `VfsFileInterface`, and source providers for SAF,
WebDAV, and ABS access. Do not add separate file-provider abstractions that
duplicate VFS responsibilities.

## Media And Playback

Playback behavior belongs under `media/`, with Android service integration under
`media/service/`.

Preserve the playback plan pipeline. Validate file access through playback
preflight and VFS rather than adding direct source-specific reads in UI or
ViewModels.

Parser additions belong in `media/parser/` and should be registered through
`RangeAudioParserRouter`. Manifest behavior belongs in `media/manifest/`.

## ABS Integration

ABS code belongs under `abs/` and should translate remote protocol facts into
local catalog, playback, VFS, and progress concepts.

Use `AbsApiClient`, DTOs, mappers, sync coordinators, mirror entities,
credential store, and `AbsSourceProvider`. Keep current protocol field names
accurate: batch item responses use `libraryItems`, and playable tracks come
from `media.tracks[].contentUrl`.

ABS changes usually require tests with `MockWebServer` and local mapping
assertions. If the source document or server behavior is uncertain, verify
against current code and a real or mocked protocol response before freezing the
design.

## Dependency Graphs

DI is provided by Koin, which directly injects resolved implementations into
target constructors.

- Koin module definitions live under `di/`.
- `OtoKoinApplication` starts the global Koin context with every Oto module.
- `GraphClosePolicy` preserves the ordered shutdown policy:
  media -> download -> abs -> library -> uiEvents -> data.
- Do not create Koin alias definitions, including provider bodies that only call
  `get()` or `getOrNull()`, such as `single<Contract> { get<Implementation>() }`,
  `factory<Contract> { get() }`, or `single { get<Implementation>() as Contract }`.
- Register the implementation directly under the public contract, or use `bind`
  / `binds` on the owning singleton when one object intentionally implements
  multiple contracts.

When adding a dependency, declare it in the constructor of the target component
and register the binding within the appropriate Koin module in `di/`. Keep
modules small and focused; split a module when it grows past roughly 80 lines.

Graph shutdown order is policy. Preserve `GraphClosePolicy.closeInLifecycleOrder()`
unless the lifecycle consequence is understood and tested.
