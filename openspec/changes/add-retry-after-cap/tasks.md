# Tasks: Add Retry-After cap

## 1. Approval and design

- [ ] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added for the delay rule and the per-message delay mechanism
- [ ] 1.2 Resolve the contradicting `Retry-After` clause in `add-event-ingestion-and-delivery` before archive

## 2. Tests first (D41)

- [x] 2.1 Unit tests for `Retry-After` parsing: delta-seconds, HTTP-date, value above the cap, past HTTP-date, `0`, value below the backoff step, invalid value, missing header
- [x] 2.2 Unit tests for the outcome classification with a delay on `429` and `503`, and no delay on other statuses
- [ ] 2.3 Integration tests with WireMock over HTTPS: the next attempt is not earlier than the honoured or capped delay; the fifth attempt still ends the round

## 3. Implementation

- [x] 3.1 Cap property with default 300 seconds and validation (positive duration)
- [x] 3.2 Read `Retry-After` in the sender and carry the delay in `AttemptResult`
- [x] 3.3 Apply the delay in the dispatch path with the approved mechanism

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; fresh-context AI review before the pull request
