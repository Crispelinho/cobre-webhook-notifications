# Change: Add processing recovery

## Why

A worker can stop in the middle of a delivery attempt (crash, deployment, lost Kafka partition). Today nothing marks that a notification event is being processed, nothing recovers it if the worker never records an outcome, and nothing stops a slow worker from writing its result after another worker has taken over. The developer approved a lease with expiry, an atomic claim and a generation (fencing) token (reconciliation decision 7).

Replay also has an open gap: the spec does not say which destination and which secret a replayed round uses (gap item 11).

## What Changes

- Before sending, a worker atomically claims the notification event for its round and receives a lease with an expiry and a generation token. Only one valid lease exists at a time.
- Every state write of the attempt (attempt row, status, `delivery_date`, `delivered_at`, retry scheduling) is conditional on the generation token. A stale worker's write has no effect.
- A recovery process reclaims notification events whose lease expired without an outcome and dispatches them again. The interrupted attempt is kept in the history with an unknown outcome; it is never recorded as a receiver failure.
- Attempt history is append-only; recovery never deletes or overwrites attempts.
- Replayed rounds use the notification event's `destination_url` snapshot (D13), re-validate the destination policy at send time, and load the secret by the notification event's `subscription_id`.

## Decisions to confirm

Existing decisions relied on: D13 (destination snapshot), D06 (replay: new round, same ID, history kept), D15 (row locking with `SKIP LOCKED`), D16 (no ordering guarantee), D18 (5 attempts per round), D26 (send-time validation), D50 (10-second total per attempt); reconciliation plan section 4, decisions 6 (at-least-once, recorded as D55) and 7 (lease, atomic claim, fencing token, recorded as D56); D58 (lease timestamps come from the application clock).

New decisions:

1. **Lease duration and recovery interval.** Proposed: a lease of 60 seconds (well above the 10-second attempt limit of D50) and a recovery sweep every 30 seconds, both configurable. *Recorded as D56.*
2. **Where the lease lives.** Proposed: columns on the notification event (`lease_expires_at`, `generation`) added by a new Flyway migration; the public `delivery_status` values stay `pending`, `retrying`, `completed` and `failed`. The reconciliation plan speaks of notifications "stuck in `processing`", but no `processing` status exists in the code. Alternative: add a public `processing` status. *Recorded as D56: internal columns, no public status.* The Flyway version must be reserved (D46 reserves V1 to V5; V3 is unused).
3. **Does an interrupted attempt count toward the 5 attempts of the round?** Proposed: yes, recorded with `error_type` `interrupted`, so a worker that crashes on every attempt cannot loop forever. If it was the fifth attempt, the notification event becomes `failed` and can be replayed. Alternative: it does not count, with a separate limit on recoveries. *Recorded as D56: it counts.*
4. **Replay of a notification event whose subscription is now inactive.** Proposed: allowed; the replay goes to the snapshot destination with that subscription's secret, because the client asked for it explicitly. Alternative: reject with `409`. *Recorded as D57: allowed.*
5. **Replay when the secret cannot be loaded** (subscription row missing or secret not decryptable). Proposed: the attempt fails permanently without sending a request, with a distinct `error_type`. *No decision-log entry needed if accepted.*

## Impact

- Affected specs: `notification-delivery`
- Affected code: `notifications` dispatch use case and Kafka consumer, `Delivery` domain (claim, generation), JDBC delivery store (conditional writes), a scheduled recovery job, replay path (`ReplayNotificationEventUseCase`, sender inputs), a new Flyway migration
- Dependencies: touches the delivery path, as does `add-signed-delivery-rules`; implement them in sequence, not in parallel branches. Shares the dispatch consumer with `add-retry-after-cap`. Independent of `add-subscription-limits`.
- Spec sequencing: the base delivery requirements ("Webhook delivery", "Idempotent delivery", "Send-time destination safety") live in the active change `add-event-ingestion-and-delivery`, and replay lives in `add-notification-replay`. Neither is archived yet, so this change uses `ADDED` requirements. Both should be archived first.
