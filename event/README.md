# :event

## Interface

- Process-wide one-shot feedback stream through `AppEventSink`.
- Feedback identity, outcome, render-mode, and delivery-policy models.
- Resource-neutral `FeedbackMessage` carriers used by app-owned rendering adapters.

## Allowed Dependencies

- Kotlin coroutines core for `SharedFlow` delivery.

## Forbidden Dependencies

- `:app`
- Android `R` resources
- Compose UI
- Media, ABS, library, data, or application implementation packages

## Verification

```powershell
.\gradlew.bat --no-problems-report :event:test
.\gradlew.bat --no-problems-report compileDebugKotlin
```
