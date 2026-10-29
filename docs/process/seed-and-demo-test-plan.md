# Test plan: seed data and demo replay

This plan checks the normal product flow end to end: subscription registration, event ingestion, signed delivery, failure, replay, and the persisted state behind each step. The demo uses only the public API. It adds no path that exists only for the demo. The seed file is query data (decision D02) and is not part of the delivery flow.

It extends [the local HTTPS delivery runbook](local-https-delivery-runbook.md), which covers the receiver, the tunnel, the signature and the troubleshooting table. This plan adds the parts that runbook does not cover: database state, the seed, Grafana, and idempotency, concurrency and restart checks.

## Status of this document

- **Verified** means the behaviour was read in the code on `docs/local-https-delivery-runbook-v2` (based on `develop` at `ed41543`), or was measured in the runbook's verified runs.
- **Expected** means the result follows from the code or the decision log, but no run has confirmed it yet. Each expected result must be confirmed when the case is executed, and this document updated with the observed value.
- **Not implemented** means a decision exists but the code does not, so the case documents the current behaviour and the gap.

## 0. Prerequisites

Follow Steps 1 to 3 of the HTTPS runbook for the receiver, the tunnel and `WEBHOOK_ALLOWED_HOSTS`. Use the `local` profile so retries are visible within minutes.

Receiver:

- `POST /ok` answers `200`.
- `POST /fail` answers `500`.
- Both read the raw body before parsing and verify `Cobre-Signature` as described in the runbook.

Tokens, as in the runbook:

- `CLIENT001` with `subscriptions:read`, `subscriptions:write`, `notifications:read`, `notifications:replay`.
- `PRODUCER001` with `events:write`.

Database access: use the PostgreSQL connection of the running stack. The host, port and credentials must be read from `compose.yaml` before the run. This document does not state them, because they were not checked in this branch.

## 1. Inspecting persisted state

Use these queries after each step. Replace `<id>` with the notification event id.

```sql
-- Current state of the notification
SELECT id, event_id, subscription_id, client_id, destination_url, delivery_status,
       round, attempts, created_at, delivery_date, delivered_at, last_error, version
FROM notifications
WHERE id = '<id>';

-- Every attempt, across rounds
SELECT notification_id, round, attempt_number, attempted_at, http_status, error_type, latency_ms
FROM delivery_attempts
WHERE notification_id = '<id>'
ORDER BY round, attempt_number;

-- Outbox rows for the notification: one per round, published when published_at is set
SELECT id, notification_id, round, published_at
FROM outbox
WHERE notification_id = '<id>'
ORDER BY round;

-- Row counts for the seed (section 2, case S1)
SELECT
  (SELECT count(*) FROM events)                       AS events,
  (SELECT count(*) FROM notifications)                AS notifications,
  (SELECT count(*) FROM subscriptions)                AS subscriptions,
  (SELECT count(*) FROM outbox)                       AS outbox,
  (SELECT count(*) FROM delivery_attempts)            AS delivery_attempts;
```

The `subscriptions.secret_ciphertext` column holds the encrypted secret. It must never contain a readable `whsec_` value. Check that with a query that selects only `secret_ciphertext IS NULL`, and never print the column itself.

## 2. Cases

Each case lists the action and the expected result. The `Status` column says whether the behaviour is verified, expected, or not implemented.

### 2.1 Seed

| ID | Action | Expected result | Status |
|---|---|---|---|
| S1 | Start the app with the `local` profile | `events` 10; `notifications` 10 (7 `completed`, 3 `failed`); `subscriptions` 3 (`SUB-CLIENT001`, `SUB-CLIENT002`, `SUB-CLIENT003`); `outbox` 0; `delivery_attempts` 0 | Verified in `SampleDataSeeder` (code) |
| S2 | Restart the app | The same counts. No duplicate rows and no changed status | Expected (`ON CONFLICT DO NOTHING`) |
| S3 | `GET /notification_events?client_id=CLIENT003&delivery_status=failed` with a `CLIENT003` token | Returns EVT009 and EVT005 | Expected (criterion O4 and self-service spec) |
| S4 | Seed subscription URLs | `https://webhook.example.com/<client>` with `secret_ciphertext` null | Verified in `SampleDataSeeder` (code) |

### 2.2 Subscription registration

| ID | Action | Expected result | Status |
|---|---|---|---|
| R1 | `POST /subscriptions` with the tunnel host, `event_types` taken from the seed | `201`. Response contains a `secret` starting with `whsec_` | Verified in the runbook |
| R2 | `GET /subscriptions/<id>` after R1 | The secret is not returned | Expected (decision D11: returned once) |
| R3 | `POST /subscriptions` with port 8443, a host outside the allowlist, or `127.0.0.1` | `400` with a generic message. The reason is only in the log | Verified in the runbook |
| R4 | Create a 51st active subscription for one client | `409` with `code` `SUBSCRIPTION_LIMIT_REACHED` (reconciliation decision 3). The check is atomic, so concurrent requests cannot exceed the limit | Verified by `SubscriptionManagementAcceptanceTests` (#19). Not run in the runbook |
| R5 | Seed subscription `SUB-CLIENT001` counted against the limit | Expected to count as an active subscription for the limit check. Confirm before relying on the number | Expected (inference from `countActive`, not run) |

### 2.3 Ingestion

| ID | Action | Expected result | Status |
|---|---|---|---|
| I1 | `POST /events` with `PRODUCER001` and new `event_id` values for the seed event types | `202`. Response lists `notification_event_ids`. `events` +1; `notifications` +1 per matching subscription in `pending`; `outbox` +1 per notification with `published_at` null | Verified in the runbook (`202`), table effects verified in `JdbcEventStoreAdapter` |
| I2 | `POST /events` with a client token | `403` | Expected (decision D22) |
| I3 | Repeat I1 with the same `event_id` | `409`. No new rows in `events`, `notifications` or `outbox` | Verified in `EventExceptionHandler` and `JdbcEventStoreAdapter` |
| I4 | Use an `event_id` that already exists in the seed (for example `EVT001`) | `409`. The seed events block the demo IDs | Verified (`events` primary key) |
| I5 | Wait a few seconds after I1 | `outbox.published_at` set | Expected (relay, `JdbcOutboxAdapter`) |

### 2.4 Delivery

| ID | Action | Expected result | Status |
|---|---|---|---|
| D1 | Subscription to `/ok`, event I1 | Receiver gets `POST`, `Content-Type: application/json`, `Cobre-Notification-Id`, `Cobre-Signature`, body with the documented fields. Signature verifies. The notification is `completed` with `delivered_at` set and `attempts` 1. `delivery_attempts`: one row, `round` 1, `attempt_number` 1, `http_status` 200 | Verified in the runbook |
| D2 | Subscription to `/fail`, same event type | Five attempts. Final status `failed`, `delivery_date` set, `last_error` `retries_exhausted`. `delivery_attempts`: five rows, `attempt_number` 1 to 5. The first three attempts are at about +0 s, +11 s and +42 s | Verified in the runbook for the three first attempts. The observed run timed all five, and the gaps do not match the configured delays (cause not verified) |
| D3 | Metric counter during D2 | `notification.delivery.attempts` for outcome `retryable` increases by 5 | Expected (`MicrometerDeliveryMetrics`) |
| D4 | Signature check on D1 with one changed byte in the body | Verification fails. The original body verifies | Verified in the runbook |
| D5 | Retry-After on a `429` response from the receiver | Honoured with a cap of 300 seconds (decision D49), scheduled through `next_attempt_at` (decision D68) | Verified by `DeliveryIntegrationTests` (#30 and the cap test). Not run in the runbook |

### 2.5 Replay

| ID | Action | Expected result | Status |
|---|---|---|---|
| P1 | `POST /notification_events/<id>/replay` on the `failed` notification from D2, receiver now `/ok` | `202`. `round` 2. New `outbox` row for round 2. After delivery: `completed`, `delivered_at` set. `delivery_attempts` for round 2 starts at `attempt_number` 1. The receiver gets the same `Cobre-Notification-Id` and a new signature timestamp | Verified in the runbook for the round change and `200` delivery; the `delivery_attempts` numbering is expected |
| P2 | Replay a `completed` notification | `409`. An audit entry with outcome `REJECTED` is written | Expected (objective O5 for `409`; audit entry expected, not checked in code) |
| P3 | Two replays of the same `failed` notification at the same time | One `202`, one `409`. One new `outbox` row | Expected (`JdbcReplayStoreAdapter` version check) |
| P4 | Replay after the host has been removed from the allowlist | One attempt with `invalid_destination`, final status `failed`. Restoring the host and replaying again gives `completed` | Verified in the runbook |
| P5 | Replay of a seed `failed` notification, for example `EVT003` (client `CLIENT002`) | Goes to `https://webhook.example.com/CLIENT002` with no signature. It must not reach the demo receiver. Its result depends on whether that host resolves | Verified in code (snapshot URL, null secret). Real DNS not checked |
| P6 | Replay the same notification many times in sequence | Each accepted replay adds one round. Rounds are never reused | Expected |

### 2.6 Idempotency, concurrency and restart

| ID | Action | Expected result | Status |
|---|---|---|---|
| X1 | Repeat the producer call I1 five times | Only the first is `202`. The rest are `409`. One notification per subscription | Verified in code, expected in run |
| X2 | Two consumers receive the same dispatch message (simulated with a duplicate message) | Only one attempt is recorded. The second write loses on `version` and is not recorded | Verified in `JdbcDeliveryStoreAdapter` (conditional update and unique key) |
| X3 | Stop the app while an attempt is in flight, then start it | The notification stays `processing` until its lease (60 s) expires. The recovery sweep (every 30 s) releases it and the interrupted attempt is recorded as `interrupted`, counting toward the five attempts. A stale worker cannot overwrite the result (generation token). Kafka may redeliver the message, but there is no duplicate `notifications` row | Verified by `JdbcProcessingRecoveryTests` (#20). Not run live |
| X4 | Confirm the notification states | Only `pending`, `retrying`, `completed` and `failed` are stored. Check the table constraint | Verified in `V1__create_notification_schema.sql` |
| X5 | Count rows after the whole run | No duplicate rows in `notifications` for a single `event_id` and subscription | Expected |

### 2.7 Limits and observability

| ID | Action | Expected result | Status |
|---|---|---|---|
| L1 | More than 100 `GET /subscriptions` in one minute | 100 `200`, then `429` with `Retry-After` | Verified in the runbook |
| O1 | Run D1 and D2 | Both appear on the dashboard within 30 s (criterion O6) | Expected (dashboard `notification-delivery.json`) |
| O2 | Panel "Failed attempt ratio (5 min, alert above 5%)" during D2 | The ratio rises. The alert "Attempt failure rate including retries above 5%" fires after its `for` period | Expected (alert rules file) |
| O3 | Panel "Dead-letter messages (last hour)" after a message reaches the dead-letter topic | The count rises. The alert "Message reached the dead-letter topic" fires | Expected (`notification.dead_letter`) |
| O4 | Panel "Oldest unpublished outbox row" while the relay is stopped | The value rises. Above 60 s it is highlighted | Expected |
| O5 | `audit` logger output for R1 and P1 | Entries `subscription.create` and `notification_event.replay` with outcome | Verified in `AuditEntry` and `StructuredAuditLog`; output format not checked |

## 3. Demo script

Run in this order. Use new `event_id` values for every ingestion.

1. Start the stack with `local`, the tunnel and the receiver (runbook Steps 1 to 3).
2. R1: register the subscription. Save the secret.
3. I1: ingest one event for a seed event type with a new `event_id`.
4. D1: show the signed delivery on `/ok` and the `completed` row.
5. Register a second subscription to `/fail` for the same event type, then I1 again with a new `event_id`.
6. D2: show five attempts and the `failed` row.
7. P1: replay the failed notification. Show the round change and the signed delivery.
8. I3: repeat an `event_id` and show the `409` with no new rows.
9. P3: run two replays at the same time and show one `202` and one `409`.
10. O1 to O3: show the dashboard.

## 4. Known gaps

- Reconciliation decisions 3 (subscription cap) and 7 (lease and fencing token), and decision D49 (`Retry-After` cap), are implemented (#19, #20, #30). Cases R4, X3 and D5 describe the implemented behaviour and are covered by tests. None of them has been run against the live stack yet.
- Seed replay (P5) cannot reach the demo receiver by design (decision D13 stores the URL as a snapshot). It is documented, not a demo path.
- Not verified in this branch: the database credentials and port of `compose.yaml`; that `webhook.example.com` does not resolve in real DNS; the cause of the retry gaps (measured in the observed run, not explained by the configured delays); the audit log output format.
- Expected results marked as such must be run and then updated to the observed values before this plan is used as evidence.
