# Comment Policy

## Language And Placement

- Write comments in English.
- For code changes, add detailed English comments at changed boundaries when the
  changed behavior, lifecycle, protocol, state-machine intent, ownership, or
  cross-layer consequence is not obvious from the signature.
- Use `/** ... */` KDoc at the beginning of functions, composables, classes,
  properties, or interfaces when a comment is needed.
- The same rule applies to Vue `computed`, `watch`, `watchEffect`, and similar
  reactive boundaries if Vue code is added in this repository.

## What To Avoid

- Do not add mechanical comments that merely restate assignments, calls, or
  control flow.
- Avoid comments inside function bodies unless the local logic is genuinely
  complex.
- Do not write repository rules, maintainer preferences, or policy text into
  code comments.
