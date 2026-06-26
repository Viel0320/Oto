# AGENTS.md

## Purpose

This file is the Oto-specific entry point for coding agents. Use it to decide
where a change belongs, which project boundaries must be preserved, and which
topic rules must be loaded before editing.

Task-specific maintainer instructions take precedence over this file. When the request is narrow, make the smallest coherent change that satisfies it. For substantial features, invasive refactors, or behavior changes that span several layers, sketch a short phased plan before editing and keep that plan aligned with the implementation.

---

## Required Topic Rules

Read the relevant topic file before acting:

- `.agents/docs/maintainer-collaboration.md` - communication, safety, and scope.
- `.agents/docs/comment-policy.md` - English code comments and KDoc rules.
- `.agents/docs/toolchain-verification.md` - Gradle wrapper, project build facts, and verification commands.
- `.agents/docs/architecture-boundaries.md` - UI, application, data, VFS, media, ABS, and DI boundaries.
- `.agents/docs/feature-checklists.md` - settings, source, playback, ABS, UI, theme, widget, and notification checklists.
- `.agents/docs/text-localization-accessibility.md` - resources, locales, and accessibility expectations.
- `.agents/docs/release-data-security.md` - release, backup, network, R8, SDK, and dependency policy.
- `.agents/docs/source-protocol-discipline.md` - structured parsing, Android API, ABS, and WebDAV protocol discipline.
- `.agents/docs/git-submission.md` - status, diff, staging, commits, and dependency-change wording.
- `.agents/docs/workflow-handoff.md` - implementation workflow, architecture-plan workflow, and final handoff format.
- `.agents/docs/common-mistakes.md` - repository-specific traps to avoid.

## Maintainer Defaults

- Communicate with the maintainer in Chinese unless they explicitly ask for another language.
- Keep responses concise and factual.
- Ask before destructive operations such as deleting files, resetting Git state, or overwriting user-owned work.
- When inspecting a specific file, start with `rg -n "." <file>`.
- Do not write policy rules into code comments.

---

## Read These First When Relevant

- `docs/release-policy.md` - release, backup, Room migration, unsafe network, R8, SDK, and dependency policy.
- `settings.gradle.kts` - active Gradle modules and centralized repository policy.
- `build.gradle.kts` and `app/build.gradle.kts` - Android, Kotlin, Compose, KSP, Room schema, signing, dependency, and test setup.
- `app/src/main/AndroidManifest.xml` - services, workers, permissions, backup, network security, and entry points.
- `app/src/main/java/com/viel/oto/data/db/AudiobookSchema.kt` - canonical database status, source, and type constants.

Do not duplicate or contradict these files casually. Update this file only for stable, repository-wide agent rules.

---

## Repository Overview

Oto is an Android audiobook player with:

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
- Use the existing gateway, read-model, command, and graph patterns before inventing new surfaces.
- Keep playback centered on `BookPlaybackPlan -> VfsPlaybackUri -> VfsPlaybackDataSource -> VfsFileInterface`.
- Keep ABS as an anti-corruption layer under `abs/`; do not leak raw ABS DTO fields into UI or general library logic.
- Treat cleartext HTTP and insecure TLS as global runtime policy decisions controlled by settings and `UnsafeNetworkPolicy`.
- Preserve Room migrations, exported schema files, and the version `41` production baseline described in `docs/release-policy.md`.
- Keep release shrinking, backup allowlists, network security, signing, SDK levels, and dependency policy aligned with `docs/release-policy.md`.
- Do not use source-level alias constructs, including Kotlin `typealias` and Kotlin import aliases. Resolve naming conflicts with direct imports or fully qualified names.

---

## Project Layout

Top-level areas:

- `app/` - the Android application module (the only `com.android.application` module; other modules are libraries).
- `build-logic/` - included build holding the `oto.android.library` convention plugin shared by library modules.
- `app/src/main/java/com/viel/oto/` - production Kotlin source.
- `app/src/test/java/com/viel/oto/` - JVM, Robolectric, architecture, ABS, parser, mapper, and policy tests.
- `app/src/androidTest/java/com/viel/oto/` - instrumentation and Compose UI tests.
- `app/schemas/` - exported Room schemas used by migration tests.
- `docs/` - maintainer-facing policy and architecture documents.

Confirm active modules in `settings.gradle.kts` before making module-level
assumptions. The application lives in `:app`, with feature and foundation code
split across Gradle library modules: `:runtime:lifecycle`,
`:runtime:observability`, `:data:store`, `:library:vfs`, `:library:import`,
`:media:metadata`, `:media:playback`, `:media:service`, `:abs`, `:work:policy`,
`:application`, `:event`, `:widget`, `:shared`, and `:ui`. The shared Android
library configuration (compile SDK, min SDK, Java, and Kotlin JVM target) is
centralized in the `oto.android.library` convention plugin under `build-logic/`;
each module keeps only its namespace, plugins, and module-specific settings.

Main package map:

- `abs/` - AudiobookShelf auth, DTOs, catalog sync, progress sync, playback session sync, mapping, and ABS VFS source provider.
- `application/` - use cases, read models, commands, download orchestration, and startup warmup.
- `data/` - Room DAOs/entities/database, gateways, services, cache policies, and DataStore-backed stores.
- `di/` - dependency injection configuration using Koin modules and ordered shutdown lifecycle.
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
