# Toolchain And Verification

## Toolchain

Use the repository Gradle Wrapper:

```powershell
.\gradlew.bat <task>
```

Project facts from `app/build.gradle.kts`:

- Android namespace: `com.viel.oto`
- compile SDK: `37`
- min SDK: `33`
- target SDK: `36`
- Java/Kotlin JVM target: `21`
- Compose is enabled through the Kotlin Compose plugin.
- Dependencies are managed through the Gradle version catalog.
- Repositories are centralized through `settings.gradle.kts` with
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- Room schemas are exported to `app/schemas`.

Do not hardcode dependency versions in module build files unless the existing
pattern already does so for that exact class of dependency.

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

For release policy, resource, manifest, R8, network, backup, or
generated-code-sensitive changes:

```powershell
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

For Compose UI, accessibility, Android framework integration, widgets, services,
or behavior that requires a device/emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

When summarizing work, state exactly which commands were run and whether they
passed. If verification was not run or could not complete, say so directly.
