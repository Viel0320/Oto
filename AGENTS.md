# AGENTS.md

## Purpose

This file defines repository-specific instructions for coding agents working on **APlayer**.

Use it to decide:

- where a change belongs,
- which architectural boundaries must be preserved,
- what must be verified before claiming work is complete.

Task-specific maintainer instructions take precedence over this file. When the request is narrow, make the smallest coherent change that satisfies it. For substantial features, invasive refactors, or behavior changes that span several layers, sketch a short phased plan before editing and keep that plan aligned with the implementation.

---

## Maintainer Preferences

- Communicate with the maintainer in Chinese unless they explicitly ask for another language.
- Keep responses concise and factual. Do not add filler, praise, or ceremony.
- Ask before running destructive operations such as deleting files, resetting Git state, or overwriting user-owned work.
- For code changes, follow the comment rules below.
- Do not write policy rules into code comments. Keep rules in documentation such as this file.

---

## Comments

- Write comments in English.
- Use `/** ... */` KDoc at the beginning of functions, composables, classes, properties, or interfaces when changed behavior, lifecycle, protocol, state-machine intent, or cross-layer ownership is not obvious from the signature.
- For every code change, check whether the changed boundary needs an English comment. Do not add mechanical comments that merely restate assignments, calls, or control flow.
- Avoid comments inside function bodies unless the local logic is genuinely complex.
- Keep repository rules in documentation, not in code comments.

---

## Read These First When Relevant

- `docs/release-policy.md` - release, backup, Room migration, unsafe network, R8, SDK, and dependency policy.
- `settings.gradle.kts` - active Gradle modules and centralized repository policy.
- `build.gradle.kts` and `app/build.gradle.kts` - Android, Kotlin, Compose, KSP, Room schema, signing, dependency, and test setup.
- `app/src/main/AndroidManifest.xml` - services, workers, permissions, backup, network security, and entry points.
- `app/src/main/java/com/viel/aplayer/AppContainer.kt` - public dependency surface and graph wiring boundary.
- `app/src/main/java/com/viel/aplayer/di/graph/` - graph ownership and shutdown order.
- `app/src/main/java/com/viel/aplayer/data/db/AudiobookSchema.kt` - canonical database status, source, and type constants.

Do not duplicate or contradict these files casually. Update this file only for stable, repository-wide agent rules.

---

## Repository Overview

APlayer is an Android audiobook player with:

- local library import through SAF,
- CUE, M3U8, generated multi-file, and single-audio book support,
- AudiobookShelf and WebDAV remote-source infrastructure,
- Media3 playback with progress persistence, auto rewind, bookmarks, subtitles, cache, and notifications,
- Jetpack Compose Material 3 UI with Navigation 3 and MaterialKolor seed-driven color schemes,
- Room, DataStore, WorkManager, OkHttp, Moshi, Coil, and Glance widgets,
- exported Room schemas and architecture tests that guard important boundaries.

Many behaviors are source-specific. Do not assume SAF, WebDAV, ABS, cached playback, manual downloads, widgets, and foreground playback share the same failure modes or lifecycle rules unless current code proves it.

---

## Critical Project Constraints

- Keep the layered architecture intact: UI -> Application -> Data/Library/Media/ABS -> Android or network infrastructure.
- Do not create broad facade or provider classes that centralize unrelated responsibilities.
- Prefer deleting obsolete transition layers over adding another wrapper around them.
- Use the existing gateway, read-model, command, graph, and dependency-view patterns before inventing new surfaces.
- Keep playback centered on `BookPlaybackPlan -> VfsPlaybackUri -> VfsPlaybackDataSource -> VfsFileInterface`.
- Keep ABS as an anti-corruption layer under `abs/`; do not leak raw ABS DTO fields into UI or general library logic.
- Treat cleartext HTTP and insecure TLS as global runtime policy decisions controlled by settings and `UnsafeNetworkPolicy`.
- Preserve Room migrations, exported schema files, and the version `41` production baseline described in `docs/release-policy.md`.
- Keep release shrinking, backup allowlists, network security, signing, SDK levels, and dependency policy aligned with `docs/release-policy.md`.

---

## Project Layout

Top-level areas:

- `app/` - the single active Android application module.
- `app/src/main/java/com/viel/aplayer/` - production Kotlin source.
- `app/src/test/java/com/viel/aplayer/` - JVM, Robolectric, architecture, ABS, parser, mapper, and policy tests.
- `app/src/androidTest/java/com/viel/aplayer/` - instrumentation and Compose UI tests.
- `app/schemas/` - exported Room schemas used by migration tests.
- `docs/` - maintainer-facing policy and architecture documents.

Confirm active modules in `settings.gradle.kts` before making module-level assumptions. This repository currently includes only `:app`.

Main package map:

- `abs/` - AudiobookShelf auth, DTOs, catalog sync, progress sync, playback session sync, mapping, and ABS VFS source provider.
- `application/` - use cases, read models, commands, download orchestration, and startup warmup.
- `data/` - Room DAOs/entities/database, gateways, services, cache policies, and DataStore-backed stores.
- `di/` - dependency contracts, manually wired graphs, graph lifecycle policy, and container-facing graph modules.
- `event/` - app-level feedback and event sinks.
- `i18n/` - app locale control.
- `library/` - SAF/WebDAV source scanning, import pipeline, VFS, availability checks, and library-root lifecycle.
- `logger/` - specialized diagnostics for import, playback, cache, ABS, cover loading, and focus behavior.
- `media/` - Media3 playback, playback plans, metadata parsers, manifests, subtitles, cache, notifications, and playback service.
- `network/` - shared HTTP/network policy helpers.
- `shared/` - small cross-layer shared utilities or models.
- `timeline/` - timeline presentation and calculation support.
- `ui/` - Compose screens, routes, overlays, Navigation 3 shell, adaptive layout helpers, themes, and UI actions.
- `widget/` - Glance app widget state, rendering, receivers, and playback actions.
- `work/` - WorkManager scheduling and workers.

---

## Toolchain And Builds

Use the repository Gradle Wrapper:

```powershell
.\gradlew.bat <task>
```

Project facts from `app/build.gradle.kts`:

- Android namespace: `com.viel.aplayer`
- compile SDK: `37`
- min SDK: `33`
- target SDK: `36`
- Java/Kotlin JVM target: `21`
- Compose is enabled through the Kotlin Compose plugin.
- Dependencies are managed through the Gradle version catalog.
- Repositories are centralized through `settings.gradle.kts` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- Room schemas are exported to `app/schemas`.

Do not hardcode dependency versions in module build files unless the existing pattern already does so for that exact class of dependency.

---

## Default Verification

Choose the narrowest meaningful verification for the change.

For Kotlin-only edits that should compile:

```powershell
.\gradlew.bat compileDebugKotlin
```

For regular JVM test coverage:

```powershell
.\gradlew.bat testDebugUnitTest
```

For release policy, resource, manifest, R8, network, backup, or generated-code-sensitive changes:

```powershell
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

For Compose UI, accessibility, Android framework integration, widgets, services, or behavior that requires a device/emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

When summarizing work, state exactly which commands were run and whether they passed. If verification was not run or could not complete, say so directly.

---

## Architecture Rules

### UI Layer

UI code belongs under `ui/` and should use the existing `Route`, `Screen`, `Overlay`, `ViewModel`, `UiState`, and `Actions` patterns.

Keep page-level state private to the page unless it is genuinely shared application state. `APlayerApp` is the app shell and should not absorb feature-specific business rules. `APlayerNavHost` should remain a thin Navigation 3 host.

For adaptive layouts, use `ui/common/layout/AppWindowSizeClass.kt` and `LocalAppWindowSizeClass`. Preserve portrait phone, landscape phone, and landscape tablet variants when touching screens that already split layouts.

### Application Layer

Application code coordinates user intent through read models, commands, and use cases. It should not know about Compose widgets, Android view concerns, or raw network DTO shape.

Use existing feature packages under `application/library/`, `application/playback/`, `application/download/`, `application/startup/`, and `application/usecase/`. Keep command surfaces small and scene-oriented.

### Data Layer

Room entities, DAOs, gateways, services, cache policies, and DataStore-backed stores live under `data/`.

The data layer is packaged **by feature**, not by type: each capability has its own package under `data/<feature>/` (e.g. `data/book/`, `data/availability/`, `data/cover/`, `data/root/`, `data/metadata/`, `data/progress/`, `data/scan/`, `data/search/`, `data/subtitle/`, `data/cleanup/`). Within a feature package, `XxxGateway` is the application-facing interface contract and `XxxService` is its implementation, kept side by side. There is no longer a type-based `data/gateway/` or `data/service/` directory; do not reintroduce one. A single capability may be split into several narrow gateways (the `data/book/` package backs six), each with its own `XxxService` implementation.

Do not bypass gateways from UI or feature ViewModels. Keep database constants in `AudiobookSchema`. When adding or changing persisted state, update entities, DAOs, migrations, schema exports, services, tests, and affected read models together.

### Library And VFS

Library scanning and import belong under `library/`. VFS source access belongs under `library/vfs/` and `library/vfs/sourceProvider/`.

Use `VirtualFileSystem`, `VfsFileInterface`, and source providers for SAF, WebDAV, and ABS access. Do not add separate file-provider abstractions that duplicate VFS responsibilities.

### Media And Playback

Playback behavior belongs under `media/`, with Android service integration under `media/service/`.

Preserve the playback plan pipeline. Validate file access through playback preflight and VFS rather than adding direct source-specific reads in UI or ViewModels.

Parser additions belong in `media/parser/` and should be registered through `RangeAudioParserRouter`. Manifest behavior belongs in `media/manifest/`.

### ABS Integration

ABS code belongs under `abs/` and should translate remote protocol facts into local catalog, playback, VFS, and progress concepts.

Use `AbsApiClient`, DTOs, mappers, sync coordinators, mirror entities, credential store, and `AbsSourceProvider`. Keep current protocol field names accurate: batch item responses use `libraryItems`, and playable tracks come from `media.tracks[].contentUrl`.

ABS changes usually require tests with `MockWebServer` and local mapping assertions. If the source document or server behavior is uncertain, verify against current code and a real or mocked protocol response before freezing the design.

### Dependency Graphs

DI is intentionally manual.

- Public dependency contracts live under `di/dependencies/`.
- Graph ownership lives under `di/graph/`.
- `AppContainer` exposes only narrow caller-facing dependency views.
- `DefaultAppContainer` wires concrete graph implementations.

Do not introduce Hilt, Koin, service locators, or global singleton shortcuts unless explicitly requested. When adding a dependency, place it in the smallest relevant dependency view and graph.

Graph shutdown order is policy. Preserve `closeAppGraphsInLifecycleOrder(...)` unless the lifecycle consequence is understood and tested.

---

## Feature-Specific Checklists

### Adding Or Changing A Setting

Check whether the change needs:

1. DataStore model or storage updates.
2. Read-model and command updates under `application/library/settings/`.
3. Settings use case updates under `application/usecase/`.
4. `SettingsViewModel`, settings state, screen, dialog, or sub-screen updates.
5. Dependency-view and graph wiring updates.
6. English plus maintained localized string updates.
7. Tests for persistence, UI state, and downstream behavior.

Do not implement only the visible switch while leaving persistence or behavior disconnected.

### Adding Or Changing A Library Source

Check:

1. `AudiobookSchema` constants and status mapping.
2. `LibraryRootEntity` and related DAO/query assumptions.
3. Source provider registration.
4. VFS open/list/range behavior.
5. Import, availability, playback preflight, cache, and cleanup behavior.
6. Settings/root management UI and commands.
7. Tests for scan, playback, missing-file recovery, and cleanup.

### Changing Playback

Check:

1. `BuildPlaybackPlanUseCase`.
2. `PlaybackPlanService`.
3. `PlaybackPlanBuilder`.
4. `PlaybackFileLookup` and `PlaybackSourcePreflight`.
5. `VfsPlaybackUri` and `VfsPlaybackDataSource`.
6. `PlaybackManager`, `PlaybackService`, and `ProgressSyncTracker`.
7. ABS session/progress sync when the change touches remote books.
8. Widget and notification presentation when the user-visible playback state changes.

### Changing ABS Sync

Check:

1. DTOs and `AbsApiClient` protocol behavior.
2. `AbsCatalogMapper` and remote ID mapping.
3. Mirror tables and sync state.
4. `AbsCatalogSynchronizer` stage behavior.
5. `AbsSyncTaskCoordinator`, worker scheduling, cancellation, and retry policy.
6. Cover caching and invalidation.
7. Playback session sync and pending progress sync.
8. MockWebServer contract tests and stage tests.

### Changing UI Screens

Check:

1. Route and overlay boundaries.
2. ViewModel state ownership.
3. Actions classes.
4. One-shot feedback through `AppEventSink`.
5. `LocalAppWindowSizeClass` usage for adaptive layout.
6. Accessibility tests where labels, touch targets, focus, or stable bounds change.
7. Maintained strings in `values/` and localized resources.

### Changing Theme Or Dynamic Color

Check:

1. App-wide theme ownership under `ui/common/theme/`, especially `APlayerTheme`, `LocalDarkTheme`, and any CompositionLocal exposed from the theme boundary.
2. Seed extraction and palette generation separately: wallpaper seed lookup may use Android platform APIs, while seed-to-`ColorScheme` generation should use MaterialKolor instead of ad-hoc HSL or color slicing.
3. Cover-seeded themes stay scoped to the surfaces that own cover presentation, such as player, detail, and edit screens. Do not move cover-derived theme rules into `APlayerApp`.
4. Settings persistence and live preview flow when adding theme preferences: DataStore model, application settings commands, read models, Settings UI, localized strings, and tests.
5. Static fallback schemes in `Color.kt` when dynamic color is disabled or no seed is available.
6. Visual and compile verification for light mode, dark mode, dynamic color enabled or disabled, and cover-seeded surfaces.

### Changing Widgets Or Notifications

Widget code under `widget/` uses Glance. Playback notifications and manual download notifications live under `media/service/`.

Keep widget state read-only and action receivers thin. Route actual playback work through existing playback/application boundaries.

---

## Text, Localization, And Accessibility

User-visible strings belong in Android resources. Do not hardcode UI text in Compose code unless the existing file is explicitly test-only or preview-only.

Maintained locales include English and Chinese variants, and the app also ships Japanese, French, German, Russian, Spanish, and Portuguese resources. When changing user-visible copy, update all directly maintained resources that the current feature already covers, or state the localization gap.

Preserve accessibility semantics, content descriptions, stable bounds, and touch target behavior. There are architecture and instrumentation tests for several of these expectations.

---

## Release, Data, And Security Policy

Read `docs/release-policy.md` before changing:

- SDK levels,
- Android Gradle Plugin or Kotlin versions,
- dependency versions for AGP, Media3, WorkManager, Room, OkHttp, backup, or network behavior,
- release shrinking or R8 rules,
- backup and data extraction rules,
- network security config,
- cleartext HTTP or insecure TLS behavior.

Release builds must keep code shrinking and resource shrinking enabled. Do not add broad keep rules to silence R8 unless the runtime failure is proven and the rule is scoped.

Backup must stay limited to portable app settings. Room data, credentials, search history, device markers, downloads, and runtime sync state must remain device-local unless the release policy is deliberately changed.

---

## Source And Protocol Discipline

Use structured parsers and DTOs where available. Do not parse JSON, XML, manifests, or protocol payloads with ad-hoc string slicing when Moshi, Room, Android resources, or existing parser abstractions are available.

For ABS and WebDAV, do not trust stale design notes over current protocol behavior. Verify the current code path and, when needed, the actual server or a representative MockWebServer response.

For Android system behavior, prefer platform APIs already used by the project. Do not add shell-command or reflection shortcuts for playback, files, widgets, notifications, or permissions.

---

## Git And Submission Discipline

Do not create commits, push branches, or open pull requests unless the maintainer explicitly asks.

Before staging or committing:

- inspect `git status`,
- inspect the relevant diff,
- include only files related to the requested task,
- never include credentials, local IDE files, build outputs, unrelated user changes, or generated artifacts not required by the task.

Use Conventional Commits when asked to write a commit message:

```text
type: short lowercase description
type(scope): short lowercase description
```

Common types:

- `fix:` - bug fixes and behavior corrections.
- `feat:` - new user-facing or maintainer-facing capabilities.
- `refactor:` - code restructuring without intended behavior changes.
- `docs:` - documentation-only changes.
- `test:` - test-only changes.
- `build:` - build-system changes.
- `i18n:` - translation-only changes.
- `chore(deps):` - dependency updates.

Keep the subject at or below 72 characters when practical. Do not add AI signatures or generated-by trailers unless explicitly requested.

For dependency changes:

- explicitly list package, library, plugin, or tool names,
- include version changes in `from -> to` form when available,
- mention scope when inferable, such as runtime, build plugin, Gradle, Maven, npm, or pnpm,
- do not collapse unrelated dependency updates into vague wording like "update dependencies" or "bump deps".

---

## Common Mistakes To Avoid

- Trusting the old `AGENTS.md` architecture text over current source files.
- Treating `APlayerApp` as a convenient place for feature business logic.
- Creating a broad provider or facade that duplicates existing gateways, VFS, or use cases.
- Updating a UI switch without updating persistence, commands, read models, and tests.
- Adding Room fields without migrations, schema exports, and migration tests.
- Hardcoding strings in Compose UI.
- Assuming ABS, WebDAV, SAF, cached playback, and manual downloads share the same source lifecycle.
- Bypassing `UnsafeNetworkPolicy` for HTTP or insecure TLS.
- Changing release, backup, R8, signing, or SDK policy during unrelated work.
- Claiming compilation or tests passed when they were not run.

---

## Recommended Agent Workflow

For implementation tasks:

1. Restate the concrete behavior being changed.
2. Locate the smallest relevant package and nearest existing pattern.
3. Identify affected layers before editing.
4. Make targeted, reviewable changes.
5. Add or update tests in the closest existing test family.
6. Run the narrowest meaningful verification.
7. Summarize changed files, behavior, verification, and any remaining risk.

For architecture or migration plans:

1. Inspect current code paths first.
2. Split the plan into independently regressable phases.
3. Keep phases close to domain boundaries rather than UI/file convenience.
4. Name concrete files, ownership boundaries, and rollback or verification points.
5. Avoid speculative layers and god objects.

---

## Maintainer-Facing Handoff Format

When finishing a task, report:

- **Changed:** files or areas updated.
- **Behavior:** what changed for users or maintainers.
- **Verification:** commands run and result.
- **Notes:** only genuine migration concerns, unverified scenarios, or follow-up risks.

Keep the handoff compact, factual, and specific.
