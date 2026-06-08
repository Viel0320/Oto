# Custom Seek Step Settings Design

## Goal

Allow users to configure rewind and fast-forward step sizes independently.

Default behavior:

- Rewind defaults to `10s`.
- Fast-forward defaults to `20s`.
- Both directions support only `10s`, `20s`, and `30s`.

The selected values must update the full playback surface:

- Full player controls
- Media notification command buttons
- Desktop widget controls

## Product Scope

This feature changes the transport step size for short seek actions only.

It does not change:

- Chapter navigation
- Timeline mapping between file position and global book position
- Notification chapter-progress mode behavior
- Auto-rewind-after-interruption behavior
- Sleep timer behavior

## Recommended Approach

Use a centralized playback seek-step model.

The app should introduce a small domain-facing configuration type instead of letting each UI surface store or interpret raw integers separately.

Suggested model:

- `SeekStepSeconds`: a constrained value that accepts only `10`, `20`, or `30`.
- `PlaybackSeekStepConfig`: a pair of `backward` and `forward` step values.

Storage should remain simple:

- `seek_backward_seconds`
- `seek_forward_seconds`

DataStore reads must validate the stored values. Invalid values should fall back to defaults:

- Invalid backward value -> `10`
- Invalid forward value -> `20`

## Architecture

### Settings Boundary

`AppSettingsRepository` owns persistence and validation.

`AppSettings` should expose the already-validated `PlaybackSeekStepConfig`, so consumers do not duplicate fallback rules.

The settings screen should update the two values through explicit repository methods:

- `updateSeekBackwardSeconds(...)`
- `updateSeekForwardSeconds(...)`

### Playback Boundary

Playback code should consume a single `PlaybackSeekStepConfig`.

The full player can perform short seeks by converting the selected seconds to milliseconds and clamping:

- Rewind target: `(currentPosition - backwardMs).coerceAtLeast(0L)`
- Fast-forward target: `(currentPosition + forwardMs).coerceAtMost(duration)`

`PlaybackService` should also sync the selected values into Media3 seek increments for the service-owned players:

- `ExoPlayer.setSeekBackIncrementMs(backwardMs)`
- `ExoPlayer.setSeekForwardIncrementMs(forwardMs)`
- `NotificationProgressPlayer` should expose matching seek increments for notification-specific timeline mapping.

This keeps widget and media notification commands aligned because they continue using `seekBack()` and `seekForward()`.

### Notification Boundary

Media notification buttons should be rebuilt from the centralized config.

When settings change:

- Rebuild rewind and forward `CommandButton` instances.
- Update display labels.
- Update custom icon resources.
- Re-apply the custom layout to both `mediaSession` and `notificationSession`.

The notification progress wrapper must keep its existing chapter/full-book timeline behavior. Only the short seek step size changes.

### Widget Boundary

The widget should render icons from the same centralized mapping as the notification.

Recommended widget state additions:

- Current rewind step seconds
- Current fast-forward step seconds

`PlayerWidgetStateHelper` should write those values when the service refreshes widget state, and `PlayerWidget` should resolve the icon resources during rendering.

Widget click behavior should stay simple:

- Rewind click sends `ACTION_REWIND`.
- Forward click sends `ACTION_FORWARD`.
- `PlayerWidgetActionReceiver` calls `MediaController.seekBack()` / `seekForward()`.

The service remains responsible for the effective step size.

## Icon And Copy Mapping

Use one resolver for playback seek-step presentation.

Backward icons:

- `10s` -> `ic_replay_10`
- `20s` -> `ic_replay_20`
- `30s` -> `ic_replay_30`

Forward icons:

- `10s` -> `ic_forward_10`
- `20s` -> `ic_forward_20`
- `30s` -> `ic_forward_30`

The same resolver should also provide string resource identifiers for accessibility labels and media notification display names.

Required new drawable resources:

- `ic_replay_20`
- `ic_replay_30`
- `ic_forward_10`
- `ic_forward_20`

Existing resources can be reused:

- `ic_replay_10`
- `ic_forward_30`

## Settings UI

Add two segmented controls to the playback behavior section:

- Rewind step: `10s`, `20s`, `30s`
- Fast-forward step: `10s`, `20s`, `30s`

Use the existing settings component style rather than creating a separate settings surface.

The row should live near other playback behavior options because it affects active listening behavior.

## Data Flow

1. User selects a seek step in Settings.
2. `SettingsViewModel` persists the selected value through `AppSettingsRepository`.
3. `settingsFlow` emits validated `PlaybackSeekStepConfig`.
4. Full player controls update icon, copy, and click behavior.
5. `PlaybackService` updates Media3 seek increments and notification command buttons.
6. Widget state refresh stores the selected step seconds.
7. Widget renders matching icons and keeps click behavior routed through MediaController.

## Error Handling

Invalid stored values should not crash the app.

Fallback behavior:

- Invalid rewind value uses `10s`.
- Invalid fast-forward value uses `20s`.

If icon mapping receives an invalid value, it should use the same default for that direction.

Seek operations must remain clamped to valid timeline bounds.

## Testing

Recommended tests:

- Settings parsing falls back to `10s` rewind and `20s` fast-forward when stored values are invalid.
- All valid `10s`, `20s`, and `30s` values map to the expected drawable and string resources.
- Full player short seek clamps below `0`.
- Full player short seek clamps above `duration`.
- Notification player uses configured increments while preserving chapter/full-book seek mapping.

Verification command:

```text
.\gradlew.bat compileDebugKotlin
```

## Rollback Plan

This work is easy to roll back in phases:

1. Remove settings UI entries while leaving defaults in the centralized model.
2. Revert notification and widget icon mapping to fixed resources.
3. Remove DataStore keys and model fields after consumers no longer use them.

The centralized model keeps these rollback steps isolated from library, chapter, and progress domains.

## Implementation Notes

Do not mix this feature with unrelated playback refactors.

Keep responsibilities separated:

- Settings stores user preference.
- Playback applies step sizes.
- Presentation maps step sizes to icons and text.
- Widget and notification trigger standard playback commands.

