# Tasks: Add Retry-After cap

## 1. Approval and design

- [x] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added for the delay rule and the per-message delay mechanism
- [x] 1.2 Resolve the contradicting `Retry-After` clause in `add-event-ingestion-and-delivery` before archive

## 2. Tests first (D41)

- [x] 2.1 Unit tests for `Retry-After` parsing: delta-seconds, HTTP-date, value above the cap, past HTTP-date, `0`, value below the backoff step, invalid value, missing header
- [x] 2.2 Unit tests for the outcome classification with a delay on `429` and `503`, and no delay on other statuses
- [x] 2.3 Integration tests with WireMock over HTTPS: the next attempt is not earlier than the honoured or capped delay; the fifth attempt still ends the round

## 3. Implementation

- [x] 3.1 Cap property with default 300 seconds and validation (positive duration)
- [x] 3.2 Read `Retry-After` in the sender and carry the delay in `AttemptResult`
- [x] 3.3 Apply the delay in the dispatch path with the approved mechanism

## 4. Verification

- [x] 4.1 `./gradlew build` passes; fresh-context AI review before the pull request

## Archive notes (2026-10-30)

- Implemented by PR #30, with the `Retry-After: 0` and past-date fix in #35 and the end-to-end tests in #40 and #43.
- 1.1: decisions D53, D63 and D68 are in the decision log.
- 1.2: the contradicting clause of `add-event-ingestion-and-delivery` was corrected before that change was archived (#33).
- 4.1: the build passed in CI on #30, #35, #40 and #43. A dual blind AI review of the delivery path on `develop` (983f324, 2026-10-30) covered this change. Round 1 found one real warning (the last retryable attempt of a round left the event `retrying`), fixed in #53. Rounds 2 to 4 reviewed that fix; round 4 had no critical or real findings. Full `./gradlew clean build` on #53: 548 tests passed; `DeliveryIntegrationTests` did not start because the PostgreSQL container failed to launch, and passed 16 of 16 when rerun.
