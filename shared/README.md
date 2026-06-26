# Shared Module

## Ownership

`:shared` owns cross-module resources and pure policies that have no Oto-domain dependencies and are intentionally reused by multiple modules.

Current shared categories are:

- Android resource files under `shared/src/main/res`, including app shell, widget, UI, notification, and shared locale resources,
- pure function policy classes under `com.viel.oto.shared.policy`,
- pure value models under `com.viel.oto.shared.model`.

The module may be depended on by any other module, but it should not depend on other Oto packages.

## Allowed Dependencies

This module may depend only on the Android Gradle plugin runtime and the Kotlin/JDK standard library unless a narrower shared need is proven.

## Disallowed Responsibilities

- No application shell code.
- No Room, network, playback, ABS, widget, UI screen, or Koin composition logic.
- No broad facade APIs that collect unrelated domain responsibilities.
- No imports from other `com.viel.oto.*` domain packages.
- No Kotlin production files outside `com.viel.oto.shared.policy` or `com.viel.oto.shared.model`.

## Verification

```powershell
.\gradlew.bat --no-problems-report :shared:compileDebugKotlin
.\gradlew.bat --no-problems-report compileDebugKotlin
.\gradlew.bat --no-problems-report :app:testDebugUnitTest --tests "com.viel.oto.architecture.*"
.\gradlew.bat --no-problems-report assembleDebug
```
