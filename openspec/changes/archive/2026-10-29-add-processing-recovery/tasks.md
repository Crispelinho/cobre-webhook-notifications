# Tasks: Add processing recovery

## 1. Approval and design

- [x] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added
- [ ] 1.2 `design.md` for the claim, the fenced writes and the recovery job; Flyway version reserved

## 2. Tests first (D41)

- [x] 2.1 Concurrency test: two workers consume the same dispatch message; exactly one sends
- [x] 2.2 Stale-worker test: a worker whose lease expired and was reclaimed tries to write its result; the write has no effect
- [x] 2.3 Recovery test: a lease expires without an outcome; the notification event is dispatched again and the interrupted attempt stays in the history as `interrupted`
- [x] 2.4 Replay tests: snapshot destination used after the client registered a new URL, destination rejected at send time, secret loaded by `subscription_id`

## 3. Implementation

- [x] 3.1 Migration with lease expiry and generation columns
- [x] 3.2 Atomic claim and generation-conditional writes in the delivery store
- [x] 3.3 Recovery job for expired leases
- [x] 3.4 Replay inputs: snapshot URL, send-time policy check, secret by `subscription_id`

## 4. Verification

- [x] 4.1 `./gradlew build` passes; dual blind AI review (delivery change) before the pull request

## Archive notes (2026-10-29)

- Implemented by PR #20. The replay inputs (2.4, 3.4) were covered by earlier replay code and by `ReplayDeliveryIntegrationTests` (#26).
- 1.2: not done. There is no `design.md`. The migration took V3 (`V3__add_processing_lease.sql`, D65).
- 4.1: the build passed before merge according to PR #20 (467 tests). A dual blind AI review of the delivery and security path ran after merge, on `develop` (983f324, 2026-10-30), and covered this change. Round 1 had no critical findings and one real warning (the last retryable attempt of a round left the event `retrying`), fixed in #53 (D70). Rounds 2 to 4 reviewed that fix; round 4 had no critical or real findings. Theoretical findings and suggestions are listed in the #53 description.
- Gap: the scenario "Lease is not visible in the API" has no test, and "Destination no longer allowed at replay time" is tested at unit level only.
