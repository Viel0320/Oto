# Shared Module

## Ownership

`:shared` owns small cross-layer utilities and resources that are intentionally reused by multiple domains.

Current shared resources include:

- common user-visible strings and maintained locale files,
- shared playback and navigation drawables,
- small utility functions that do not depend on Android framework state.

## Allowed Dependencies

This module may depend only on the Android Gradle plugin runtime and the Kotlin/JDK standard library unless a narrower shared need is proven.

## Disallowed Responsibilities

- No application shell code.
- No Room, network, playback, ABS, widget, UI screen, or Koin composition logic.
- No broad facade APIs that collect unrelated domain responsibilities.

## Verification

```powershell
.\gradlew.bat --no-problems-report :shared:compileDebugKotlin
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report :app:testDebugUnitTest --tests "com.viel.oto.architecture.*"
.\gradlew.bat --no-problems-report assembleDebug
```
