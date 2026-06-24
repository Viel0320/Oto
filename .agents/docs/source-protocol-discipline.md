# Source And Protocol Discipline

Use structured parsers and DTOs where available. Do not parse JSON, XML,
manifests, or protocol payloads with ad-hoc string slicing when Moshi, Room,
Android resources, or existing parser abstractions are available.

For ABS and WebDAV, do not trust stale design notes over current protocol
behavior. Verify the current code path and, when needed, the actual server or a
representative MockWebServer response.

For Android system behavior, prefer platform APIs already used by the project.
Do not add shell-command or reflection shortcuts for playback, files, widgets,
notifications, or permissions.
