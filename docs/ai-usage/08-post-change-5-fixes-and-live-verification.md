# 08 - Fixes after change 5, reconciliation and live verification

## Goal

Close the gaps found after change 5: the decisions reconciled on 2026-10-28, the Retry-After and DNS behaviour, the replay and cap tests, the local Kafka broker, and the objectives that need a live run (O2, O3, O4, O6, O10). This entry covers the work from 2026-10-28 to 2026-10-30. It has no screenshots yet.

## How AI was used

- **Reconciliation (2026-10-28).** The discrepancy audit compared the decisions, the OpenSpec specs and the code. Each mismatch became a decision (D49 to D68 in `decision-log.md`). The human approved every decision before it was implemented.
- **Fixes, one PR per change.** Retry-After with a database-scheduled next attempt (#30), DNS failures retried at send time (#32), the lease and fencing recovery (#20), the 409 subscription cap (#19), and the bounded destination lookup (#38).
- **Tests added for the approved behaviour.** Replay refusals end to end, the Retry-After cap measured from the receiver's own request log, and the registration of an unresolved host (#40, #43, #49).
- **Live verification (2026-10-30).** The AI ran the local stack, measured the objectives and recorded the results in `docs/process/live-verification-2026-10-30.md`. Results that were not met are recorded as not met.
- **Documentation corrections.** The seed and demo test plan still described the lease, the cap and Retry-After as not implemented. The AI corrected those statements and pointed each one to its test.

## How the output was validated

- Every claim about the code was checked against `git` and the source before it went into a document. Two wrong claims were caught this way. One said Retry-After was not implemented; it was merged in #30. One said the `demo` profile had been removed from `develop`; it had not.
- Tests: the full suite (`./gradlew test`) passed with 551 tests and 0 failures on the cap branch. Two runs failed on Testcontainers startup timeouts, not on assertions. The failing class passed when run alone, and the rerun was clean.
- Mutation check: with `Retry-After` ignored in the sender, the three tests that depend on it failed. The production file was restored afterwards.
- Live measurements: each number came from a run on an isolated compose project with free ports. Results that depended on a public tunnel are labelled as such.

## Results and limits

| Objective | Result |
|---|---|
| O10 compose healthy in under 2 min | Met: 36 s on one run |
| O4 p95 below 300 ms | Met: p95 47 ms over 90 requests |
| O6 metrics and alerts | Metrics visible in Prometheus. Alerts loaded and seen firing. Exact time to firing not measured |
| O2 first attempt within 2 s | Not met over a public tunnel: p95 8.6 s. Not measured on localhost because the SSRF policy rejects it |
| O3 full round in 4 min or less | Not verified live. Retries stalled because of the Kafka coordinator problem below |

Kafka coordinator churn: the default `offsets.topic.num.partitions` (50) on a single broker produced hundreds of `not the correct coordinator` errors in a few minutes. Testcontainers uses one partition. With one partition on the local broker, the errors stopped (0 errors, 12 rejoins). The change is in `compose.yaml` for local development only.

## What was not done

- No live run of the dead-letter topic.
- No live run of a full retry round with the fixed broker.
- Screenshots were not included in this entry.
