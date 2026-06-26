# Library VFS Module

## Interface

- `VirtualFileSystem`
- `VfsFileInterface`
- `VfsPlaybackStreamReader`
- `LibrarySourceProvider`
- `FileRef` and `FileIdentity` for stable VFS file coordinates

## Allowed Dependencies

- `:data:store` for source roots, file entities, cache policy, and WebDAV credentials.
- `:network:policy` for cleartext HTTP and unsafe TLS enforcement.
- `:runtime:observability` for VFS and cache diagnostics.

## Forbidden Dependencies

- Do not depend on `:app`, UI, media playback implementation, widget, or ABS implementation.
- Do not add import pipeline, scan orchestration, or book-draft ownership logic here.
- Do not create a broad source facade around SAF, WebDAV, and ABS; keep source adapters narrow.

## Verification

```powershell
.\gradlew.bat --no-problems-report :library:vfs:compileDebugKotlin
.\gradlew.bat --no-problems-report :library:vfs:testDebugUnitTest
```
