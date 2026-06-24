# Text, Localization, And Accessibility

## Text Resources

User-visible strings belong in Android resources. Do not hardcode UI text in
Compose code unless the existing file is explicitly test-only or preview-only.

## Locales

Maintained locales include English and Chinese variants. The app also ships
Japanese, French, German, Russian, Spanish, and Portuguese resources.

When changing user-visible copy, update all directly maintained resources that
the current feature already covers, or state the localization gap.

## Accessibility

Preserve accessibility semantics, content descriptions, stable bounds, and touch
target behavior. There are architecture and instrumentation tests for several of
these expectations.
