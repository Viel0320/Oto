# Workflow And Handoff

## Implementation Tasks

For implementation tasks:

1. Restate the concrete behavior being changed.
2. Locate the smallest relevant package and nearest existing pattern.
3. Identify affected layers before editing.
4. Make targeted, reviewable changes.
5. Add or update tests in the closest existing test family.
6. Run the narrowest meaningful verification.
7. Summarize changed files, behavior, verification, and any remaining risk.

## Architecture Or Migration Plans

For architecture or migration plans:

1. Inspect current code paths first.
2. Split the plan into independently regressable phases.
3. Keep phases close to domain boundaries rather than UI/file convenience.
4. Name concrete files, ownership boundaries, and rollback or verification
   points.
5. Avoid speculative layers and god objects.

## Maintainer-Facing Handoff Format

When finishing a task, report:

- **Changed:** files or areas updated.
- **Behavior:** what changed for users or maintainers.
- **Verification:** commands run and result.
- **Notes:** only genuine migration concerns, unverified scenarios, or follow-up
  risks.

Keep the handoff compact, factual, and specific.
