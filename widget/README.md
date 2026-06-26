# :widget

## Interface

- Glance `PlayerWidget` rendering and widget-local playback state storage.
- `PlayerWidgetReceiver` and non-exported playback action receiver entrypoints.
- Widget presentation helpers for playback controls, seek-step controls, and cover background rendering.

## Allowed Dependencies

- `:settings:model` for seek-step value objects.
- `:runtime:observability` for diagnostic logging.
- AndroidX Glance and Media3 session APIs for widget rendering and command dispatch.

## Forbidden Dependencies

- `:app`
- Compose screen/UI packages
- Media service implementation classes
- Room, ABS, library import, or application command implementation packages

## Verification

```powershell
.\gradlew.bat --no-problems-report :widget:compileDebugKotlin
.\gradlew.bat --no-problems-report :widget:testDebugUnitTest
.\gradlew.bat --no-problems-report assembleDebug
```
