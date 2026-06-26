# Media Metadata Module

## Interface

- `AudiobookMetadata`
- `MetadataResolver`
- `AudioMetadataRef`, `HeuristicAggregationPlan`, and generated-book heuristic planning
- `CoverExtractor` and cover writer adapters
- `CueManifestParser`, `M3u8ManifestParser`, and manifest sidecar helpers
- `SubtitleParser` and `SubtitleLine`

## Allowed Dependencies

- `:data:store` for the current chapter entity and cover recovery adapter contracts.
- `:library:vfs` for source-file coordinates and range reads.
- `:runtime:observability` for parser and cover diagnostics.

## Forbidden Dependencies

- Do not depend on `:app`, UI, widget, playback service, or playback manager implementation.
- Do not add scan orchestration, source-root lifecycle, or database migration ownership here.
- Do not add ABS DTOs or raw remote protocol models to parser results.

## Verification

```powershell
.\gradlew.bat --no-problems-report :media:metadata:compileDebugKotlin
.\gradlew.bat --no-problems-report :media:metadata:testDebugUnitTest
```
