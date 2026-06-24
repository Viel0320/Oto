# Git And Submission Discipline

Do not create commits, push branches, or open pull requests unless the
maintainer explicitly asks.

Before staging or committing:

- inspect `git status`,
- inspect the relevant diff,
- include only files related to the requested task,
- never include credentials, local IDE files, build outputs, unrelated user
  changes, or generated artifacts not required by the task.

Use Conventional Commits when asked to write a commit message:

```text
type: short lowercase description
type(scope): short lowercase description
```

Common types:

- `fix:` - bug fixes and behavior corrections.
- `feat:` - new user-facing or maintainer-facing capabilities.
- `refactor:` - code restructuring without intended behavior changes.
- `docs:` - documentation-only changes.
- `test:` - test-only changes.
- `build:` - build-system changes.
- `i18n:` - translation-only changes.
- `chore(deps):` - dependency updates.

Keep the subject at or below 72 characters when practical. Do not add AI
signatures or generated-by trailers unless explicitly requested.

For dependency changes:

- explicitly list package, library, plugin, or tool names,
- include version changes in `from -> to` form when available,
- mention scope when inferable, such as runtime, build plugin, Gradle, Maven,
  npm, or pnpm,
- do not collapse unrelated dependency updates into vague wording like "update
  dependencies" or "bump deps".
