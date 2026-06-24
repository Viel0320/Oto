# Common Mistakes To Avoid

- Trusting old `AGENTS.md` architecture text over current source files.
- Treating `OtoApp` as a convenient place for feature business logic.
- Creating a broad provider or facade that duplicates existing gateways, VFS, or
  use cases.
- Updating a UI switch without updating persistence, commands, read models, and
  tests.
- Adding Room fields without migrations, schema exports, and migration tests.
- Hardcoding strings in Compose UI.
- Assuming ABS, WebDAV, SAF, cached playback, and manual downloads share the
  same source lifecycle.
- Bypassing `UnsafeNetworkPolicy` for HTTP or insecure TLS.
- Changing release, backup, R8, signing, or SDK policy during unrelated work.
- Claiming compilation or tests passed when they were not run.
