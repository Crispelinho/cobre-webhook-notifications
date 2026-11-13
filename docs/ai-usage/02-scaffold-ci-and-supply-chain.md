# 02 - Scaffold, CI and supply chain

## Goal

Create the repository and base project (Phase 4), the first OpenSpec change (Phase 3) and a CI pipeline.

## How AI was used

- Read Spring Initializr metadata to pick the latest stable Spring Boot (4.1.2) and confirm Java 25 support, then generated the project.
- Looked up current container image tags and GitHub Action versions through their public APIs instead of assuming them, and pinned them.
- Wrote the hexagonal package skeleton, `openspec/project.md` and the `add-self-service-query` change; fixed the two warnings `openspec validate --strict` reported.
- Wrote the CI pipeline and diagnosed each failure from the job logs.

## Incidents and how they were handled

| Incident | Detection | Resolution |
|---|---|---|
| The project folder sat inside an unrelated client Git repository | AI ran `git rev-parse --show-toplevel` before initializing Git | The developer removed the parent repository; the challenge got its own GitHub repository |
| No Git author identity on the machine | First commit failed | AI did not choose an identity; the developer chose it, configured for this repository only |
| Initializr's `compose.yaml` had no Kafka service and used `latest` tags | AI review of the generated files | Kafka added; all images pinned |
| Dependency review failed: dependency graph disabled | CI log | The developer enabled it in repository settings (AI did not change security settings). Account-level settings only apply to new repositories. |
| GitHub does not parse `build.gradle.kts` dependencies on its own, so the review would have passed while checking nothing | AI analysis of the first failure | Added Gradle dependency graph submission before the review |
| Three high-severity advisories in transitive dependencies of `kafka-clients` | CI dependency review | Pinned `lz4-java` 1.11.4 and `snappy-java` 1.1.10.12 (see [security analysis](../security/security-analysis.md#findings-during-development)) |

## Deviations from the agreed process

- The four scaffold commits were made without a per-diff review because the developer asked to "push everything at once"; they were reviewed in pull request #1 instead.
- CI fixes were committed through the Auto-fix flow, which the developer enabled as standing authorization for CI failures.
- The scaffold (Phase 4) was created before all specs (Phase 3) existed; only the first change is specified.

## Human validation and corrections

- The developer chose Gradle over Maven and asked to keep the challenge plan technology-agnostic.
- The developer defined the branching model (`main`, `develop`, squash into `develop`) and the rule to review every diff before committing.
- The developer asked for all documentation in English and for the relevant conversation content to be stored under `docs/`.
