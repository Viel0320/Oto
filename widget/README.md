# :widget

## Interface

- Glance `PlayerWidget` rendering and widget-local playback state storage.
- `PlayerWidgetReceiver` and non-exported playback action receiver entrypoints.
- Widget presentation helpers for playback controls, seek-step controls, and cover background rendering.
- Widget resources are consumed from `:shared`; `:widget` should not add a local `src/main/res` tree.

## Allowed Dependencies

- `:shared` for seek-step value objects.
- `:runtime:observability` for diagnostic logging.
- AndroidX Glance and Media3 session APIs for widget rendering and command dispatch.

## Forbidden Dependencies

- `:app`
- Compose screen/UI packages
- Media service implementation classes
- Room, ABS, library import, or application command implementation packages
- Local widget resources under `widget/src/main/res`; add shared widget resources to `shared/src/main/res` instead.

## Verification

```powershell
.\gradlew.bat --no-problems-report :widget:compileDebugKotlin
.\gradlew.bat --no-problems-report :widget:testDebugUnitTest
.\gradlew.bat --no-problems-report assembleDebug
```
