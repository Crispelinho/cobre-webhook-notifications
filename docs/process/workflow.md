# Development Workflow

How the challenge is built: a hybrid ODD + SDD process (see the [challenge plan](../challenge/CHALLENGE_PLAN.md)) with human approval gates.

## Lifecycle of an OpenSpec change

```
 1. Specify     proposal, design, specs (Given/When/Then) and tasks in openspec/changes/<change>/
 2. Validate    openspec validate <change> --strict
 3. Approve     the developer approves the specs                      ← human gate
 4. Implement   in batches: scenario test first → code → ./gradlew build passes
 5. Review      the developer reviews each batch diff                 ← human gate
 6. Commit      only after approval; Conventional Commits, no AI attribution
 7. Verify      every scenario has a test; fresh-context AI review of the diff against the specs
 8. Pull request into develop; the developer merges (squash)         ← human gate
 9. Archive     openspec archive <change> merges the deltas into openspec/specs/
10. AI log      one entry in docs/ai-usage/ (prompts, corrections, validations)
```

## Branching and merging

- Long-lived branches: `main` and `develop`.
- One feature branch per OpenSpec change, created from `develop` (for example `feature/add-self-service-query`).
- Pull requests into `develop` are **squash-merged**; the feature branch is deleted after the merge.
- `develop` is merged into `main` with a **merge commit**.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/). Types: `feat`, `fix`, `test`, `docs`, `chore`, `ci`, `build`, `refactor`. Scopes: `notifications`, `events`, `subscriptions`, `security`, `openspec`, `scaffold`, `deps`.

```
feat(notifications): add self-service query endpoints

Refs: openspec/changes/add-self-service-query
```

One commit per task group (about five per change), so review stays manageable.

## Continuous integration

Every pull request into `develop` or `main` runs:

| Job | Checks |
|---|---|
| Validate OpenSpec | `openspec validate --all --strict` |
| Build and test | `./gradlew build` with Java 25; Testcontainers uses the runner's Docker |
| Dependency review | Submits the Gradle dependency graph and fails on new high-severity vulnerabilities |

## AI review before each pull request

1. A sub-agent that does **not** see the design conversation receives the diff, the OpenSpec change, `openspec/project.md` and a fixed checklist (scenario coverage, framework-free domain, `ClientId` on every query, OWASP mitigations, error handling, test quality).
2. It returns findings as CRITICAL, WARNING or SUGGESTION with file and line.
3. The main session verifies each finding and presents accepted and rejected findings, with reasons, to the developer.
4. Accepted findings are fixed in a new commit, reviewed like any other.
5. The report goes into the pull request description and the AI usage log.

The delivery and security change uses a dual blind review: two independent reviewers, and only findings both confirm are fixed.

## Change roadmap and dependencies

```
1 add-self-service-query  (schema V1 + security base)
 ├─► 2 add-event-ingestion-and-delivery ─► 3 add-notification-replay ─► 5 add-observability-and-audit
 └─► 4 add-subscription-management   (separate package; can run in parallel with 2)
Architecture docs (Task 1) and security docs (Task 3) depend only on the decision log.
```

## Parallel work

Fronts run in separate sessions, each in its own Git worktree and feature branch, so changes never overlap in the working tree (decisions D45 and D46):

| Front | Session | Starts when |
|---|---|---|
| Changes 1 → 2 → 3 (critical path) | Main session | Now |
| Documentation and presentation (Tasks 1 and 3, Phase 6) | Parallel session | After the scaffold is in `develop` |
| Change 4 (subscription management) | Parallel session | After change 1 is in `develop` |

Flyway migration numbers are reserved per change (V1 query, V2 ingestion and outbox, V3 subscriptions, V4 replay, V5 observability and audit). Each branch is updated from `develop` after every squash merge.
