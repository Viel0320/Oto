# Work Policy Module

## Interface

- `WorkSchedulingPolicy`
- `UniqueWorkSchedulingPolicy`

## Allowed Dependencies

- `:data:store` for persisted scan trigger constants that are part of the public scheduling input.
- AndroidX WorkManager types for constraints, unique-work replacement, and retry backoff values.

## Forbidden Dependencies

- Do not depend on `:app`, Worker implementations, library import runners, ABS sync coordinators, UI, or widgets.
- Do not enqueue work here; callers own Worker input data and `WorkManager.enqueueUniqueWork`.
- Do not add source-specific business rules beyond reusable queue policy.

## Verification

```powershell
.\gradlew.bat --no-problems-report :work:policy:compileDebugKotlin
.\gradlew.bat --no-problems-report :work:policy:testDebugUnitTest
```
