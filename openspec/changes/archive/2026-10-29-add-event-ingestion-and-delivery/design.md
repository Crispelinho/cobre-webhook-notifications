# Design: Add event ingestion and delivery

## Context

Implements flows F1 to F4 of `docs/architecture/system-design.md` and decisions D14 to D21 of the decision log. PostgreSQL is the source of truth; Kafka is the transport.

## Decisions

### Schema (`V2__create_outbox.sql`)

`outbox (id bigint identity, notification_id, round, payload jsonb, created_at, published_at)` with a partial index on unpublished rows. Notification events and their outbox rows are written in the ingestion transaction.

### Topics

| Topic | Purpose |
|---|---|
| `notification.dispatch` | One message per notification event and round; key = `notification_event_id` |
| `notification.dispatch-retry-*` | Created by `@RetryableTopic`, one per backoff step |
| `notification.dispatch-dlt` | Exhausted messages; its handler marks the notification event `failed` |

The message carries only `notification_event_id` and `round`; the consumer loads everything else from the database.

### Outcome classification in the domain

`DeliveryOutcome.classify(httpStatus | error)` returns `SUCCESS`, `RETRYABLE` or `PERMANENT`. The Kafka adapter throws a retryable exception for `RETRYABLE`, records and stops for `PERMANENT`, and the attempt count drives exhaustion. A change of broker keeps these rules (fallback plan).

### Retry schedule per profile

- Demo profile: attempts at 0 s, 10 s, 30 s, 1 min, 2 min.
- Default profile: exponential from 30 s up to 1 h, with jitter.

### HTTPS sender

JDK `HttpClient` with `Redirect.NEVER`, connect timeout 2 s and a 10 s request timeout. A virtual-thread executor keeps blocking calls cheap.

### Wiring after `add-subscription-management`

The last batch replaces the temporary "allow all destinations, no signature" adapters with the destination policy and the HMAC signer from `shared.security` and `shared.crypto`, delivered by `add-subscription-management`.

## Risks

- **Per-partition sequential consumption** limits throughput (see capacity in the system design). Accepted for the MVP with about 48 partitions.
- **Fallback plan:** if retry topics and the dead-letter topic do not work by the agreed checkpoint, a PostgreSQL-backed queue adapter replaces Kafka behind `DispatchPublisher`.
