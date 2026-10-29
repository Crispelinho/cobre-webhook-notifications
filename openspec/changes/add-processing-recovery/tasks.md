# Tasks: Add processing recovery

## 1. Approval and design

- [ ] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added
- [ ] 1.2 `design.md` for the claim, the fenced writes and the recovery job; Flyway version reserved

## 2. Tests first (D41)

- [ ] 2.1 Concurrency test: two workers consume the same dispatch message; exactly one sends
- [ ] 2.2 Stale-worker test: a worker whose lease expired and was reclaimed tries to write its result; the write has no effect
- [ ] 2.3 Recovery test: a lease expires without an outcome; the notification event is dispatched again and the interrupted attempt stays in the history as `interrupted`
- [ ] 2.4 Replay tests: snapshot destination used after the client registered a new URL, destination rejected at send time, secret loaded by `subscription_id`

## 3. Implementation

- [ ] 3.1 Migration with lease expiry and generation columns
- [ ] 3.2 Atomic claim and generation-conditional writes in the delivery store
- [ ] 3.3 Recovery job for expired leases
- [ ] 3.4 Replay inputs: snapshot URL, send-time policy check, secret by `subscription_id`

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; dual blind AI review (delivery change) before the pull request
