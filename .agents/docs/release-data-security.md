# Release, Data, And Security Policy

Read `docs/release-policy.md` before changing:

- SDK levels,
- Android Gradle Plugin or Kotlin versions,
- dependency versions for AGP, Media3, WorkManager, Room, OkHttp, backup, or
  network behavior,
- release shrinking or R8 rules,
- backup and data extraction rules,
- network security config,
- cleartext HTTP or insecure TLS behavior.

Release builds must keep code shrinking and resource shrinking enabled. Do not
add broad keep rules to silence R8 unless the runtime failure is proven and the
rule is scoped.

Backup must stay limited to portable app settings. Room data, credentials,
search history, device markers, downloads, and runtime sync state must remain
device-local unless the release policy is deliberately changed.
