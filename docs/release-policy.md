# Release Policy

## Release Policy Interface (Auditable production behavior priority)

This document is the first stop before changing SDK levels, Android Gradle Plugin versions, R8 rules,
network defaults, backup scope, or runtime security settings.

## Priority Order

1. Runtime user settings are the highest priority for compatibility toggles that can be safely changed by the user.
2. Platform policy is the next priority: Android manifest, backup rules, data extraction rules, and network security config.
3. Build policy is the next priority: min/target/compile SDK, release shrinking, and dependency versions.
4. R8 policy is the final packaging layer: remove release-only noise, keep required metadata, and avoid broad keep rules.

## Debug And Release Differences

Debug builds keep development tooling and do not run release shrinking.

Release builds must enable both code shrinking and resource shrinking. Release signing is configured in the
Gradle release build type, and release builds must use the optimized default ProGuard file plus the app-specific
`app/proguard-rules.pro`.

## Logs

Release builds may keep warning and error logs for production diagnostics.

Verbose, debug, and info calls to `android.util.Log` are treated as release noise and are stripped by R8 through
`-assumenosideeffects`. App code must not add broad keep rules that prevent this stripping.

## Backup And Transfer

The app permits Android backup, but device-scoped preferences are excluded from both cloud backup and
device transfer:

<!-- Update Backup Policy (Document WebDAV and ABS credentials backup exclusion to prevent secrets leak)
     Update release-policy.md to note that webdav_credentials.xml and abs_credentials.preferences_pb are excluded from backup and device transfer. -->
- `app/src/main/res/xml/backup_rules.xml` excludes `sharedpref/device.xml`, `sharedpref/webdav_credentials.xml`, and `files/datastore/abs_credentials.preferences_pb`.
- `app/src/main/res/xml/data_extraction_rules.xml` excludes `sharedpref/device.xml`, `sharedpref/webdav_credentials.xml`, and `files/datastore/abs_credentials.preferences_pb` from cloud backup.
- `app/src/main/res/xml/data_extraction_rules.xml` excludes `sharedpref/device.xml`, `sharedpref/webdav_credentials.xml`, and `files/datastore/abs_credentials.preferences_pb` from device transfer.

## Unsafe Network

The platform network security config permits cleartext traffic at the socket layer so WebDAV and LAN libraries
can still connect to user-owned HTTP endpoints.

Runtime playback and remote access code must consult app settings before using insecure network behavior:

- `AppSettings.isCleartextTrafficAllowed` defaults to `false`.
- `AppSettings.isAllowInsecureTls` defaults to `false`.
- `UnsafeNetworkPolicy` is the single runtime rule for cleartext HTTP and insecure TLS decisions.
- WebDAV, ABS, root registration, connection testing, availability checks, and playback preflight must reject HTTP unless the global cleartext setting is enabled.
- WebDAV and ABS clients must use unsafe TLS only when the global insecure TLS setting is enabled.
- Credential or root-scoped TLS exceptions are not allowed; unsafe network behavior is global-only.

## Dependency Upgrade Rule

Before upgrading AGP, Kotlin, compile SDK, target SDK, Media3, WorkManager, Room, OkHttp, or backup/network
related libraries, update or re-run the release policy tests. If the tests fail, update this document first,
then update the production configuration.
