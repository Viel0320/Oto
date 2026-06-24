# Maintainer Collaboration

## Communication

- Communicate with the maintainer in Chinese unless they explicitly ask for
  another language.
- Keep responses concise and factual. Do not add filler, praise, or ceremony.
- Do not add meaningless content just to satisfy a format.

## Scope And Safety

- Task-specific maintainer instructions take precedence over repository rules.
- When the request is narrow, make the smallest coherent change that satisfies
  it.
- Ask before running destructive operations such as deleting files, resetting
  Git state, or overwriting user-owned work.
- For code changes, follow `.agents/docs/comment-policy.md`.
- Keep repository rules in documentation, not in code comments.

## Planning

- For substantial features, invasive refactors, or behavior changes that span
  several layers, sketch a short phased plan before editing.
- Plans must be independently regressable by phase.
- Keep phases close to domain boundaries instead of grouping unrelated work by
  convenience.
- Preserve architecture and component decoupling. Do not create god classes.
