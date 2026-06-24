# Feature Checklists

## Adding Or Changing A Setting

Check whether the change needs:

1. DataStore model or storage updates.
2. Read-model and command updates under `application/library/settings/`.
3. Settings use case updates under `application/usecase/`.
4. `SettingsViewModel`, settings state, screen, dialog, or sub-screen updates.
5. Koin graph wiring and dependency updates.
6. English plus maintained localized string updates.
7. Tests for persistence, UI state, and downstream behavior.

Do not implement only the visible switch while leaving persistence or behavior
disconnected.

## Adding Or Changing A Library Source

Check:

1. `AudiobookSchema` constants and status mapping.
2. `LibraryRootEntity` and related DAO/query assumptions.
3. Source provider registration.
4. VFS open/list/range behavior.
5. Import, availability, playback preflight, cache, and cleanup behavior.
6. Settings/root management UI and commands.
7. Tests for scan, playback, missing-file recovery, and cleanup.

## Changing Playback

Check:

1. `BuildPlaybackPlanUseCase`.
2. `PlaybackPlanService`.
3. `PlaybackPlanBuilder`.
4. `PlaybackFileLookup` and `PlaybackSourcePreflight`.
5. `VfsPlaybackUri` and `VfsPlaybackDataSource`.
6. `PlaybackManager`, `PlaybackService`, and `ProgressSyncTracker`.
7. ABS session/progress sync when the change touches remote books.
8. Widget and notification presentation when the user-visible playback state
   changes.

## Changing ABS Sync

Check:

1. DTOs and `AbsApiClient` protocol behavior.
2. `AbsCatalogMapper` and remote ID mapping.
3. Mirror tables and sync state.
4. `AbsCatalogSynchronizer` stage behavior.
5. `AbsSyncTaskCoordinator`, worker scheduling, cancellation, and retry policy.
6. Cover caching and invalidation.
7. Playback session sync and pending progress sync.
8. MockWebServer contract tests and stage tests.

## Changing UI Screens

Check:

1. Route and overlay boundaries.
2. ViewModel state ownership.
3. Actions classes.
4. One-shot feedback through `AppEventSink`.
5. `LocalAppWindowSizeClass` usage for adaptive layout.
6. Accessibility tests where labels, touch targets, focus, or stable bounds
   change.
7. Maintained strings in `values/` and localized resources.

## Changing Theme Or Dynamic Color

Check:

1. App-wide theme ownership under `ui/common/theme/`, especially `OtoTheme`,
   `LocalDarkTheme`, and any CompositionLocal exposed from the theme boundary.
2. Seed extraction and palette generation separately: wallpaper seed lookup may
   use Android platform APIs, while seed-to-`ColorScheme` generation should use
   MaterialKolor instead of ad-hoc HSL or color slicing.
3. Cover-seeded themes stay scoped to the surfaces that own cover presentation,
   such as player, detail, and edit screens. Do not move cover-derived theme
   rules into `OtoApp`.
4. Settings persistence and live preview flow when adding theme preferences:
   DataStore model, application settings commands, read models, Settings UI,
   localized strings, and tests.
5. Static fallback schemes in `Color.kt` when dynamic color is disabled or no
   seed is available.
6. Visual and compile verification for light mode, dark mode, dynamic color
   enabled or disabled, and cover-seeded surfaces.

## Changing Widgets Or Notifications

Widget code under `widget/` uses Glance. Playback notifications and manual
download notifications live under `media/service/`.

Keep widget state read-only and action receivers thin. Route actual playback
work through existing playback/application boundaries.
