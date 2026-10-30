# System Design

Design for Task 1: event notification delivery and the self-service API. Decisions are referenced by ID from the [decision log](decision-log.md).

## C4: system context

```mermaid
flowchart LR
    producer["Cobre platform producer<br/>(internal service)"]
    client["Client system"]
    receiver["Client webhook receiver"]
    monitoring["Monitoring team"]
    idp["Identity provider<br/>(dev tool locally; Keycloak or Cognito in production)"]
    svc["Notification service"]

    producer -- "POST /events (service JWT)" --> svc
    client -- "Subscriptions, notification queries and replays (client JWT)" --> svc
    svc -- "HTTPS webhook, HMAC-signed" --> receiver
    monitoring -- "Dashboards and alerts" --> svc
    idp -. "Issues JWTs" .-> producer
    idp -. "Issues JWTs" .-> client
    svc -. "Fetches JWKS to verify JWTs" .-> idp
```

## C4: containers

```mermaid
flowchart LR
    subgraph app["notification-service (one deployable, D31)"]
        api["api role<br/>REST: /events, /notification_events, /subscriptions"]
        worker["worker role<br/>outbox relay, Kafka consumers, webhook sender"]
    end
    pg[("PostgreSQL<br/>source of truth + outbox")]
    kafka[["Kafka<br/>dispatch, retry topics, dead-letter topic"]]
    lgtm["Grafana LGTM<br/>metrics, logs, traces"]
    receiver["Client webhook receiver"]

    api -- "events, subscriptions, notification queries" --> pg
    worker -- "outbox reads, attempts and state writes" --> pg
    worker --> kafka
    kafka --> worker
    worker -- HTTPS --> receiver
    api -- OTLP --> lgtm
    worker -- OTLP --> lgtm
```

Both roles are the same deployable (D31): the diagram shows the roles, not two services.

| Container | Responsibility |
|---|---|
| notification-service | REST API and delivery workers; `api` and `worker` roles can be deployed and scaled separately. |
| PostgreSQL | Events, subscriptions, notification events, delivery attempts, outbox. |
| Kafka | `notification.dispatch`, retry topics and the dead-letter topic. |
| Grafana LGTM | Metrics, logs, traces, dashboards and alerts. |

## Components (hexagonal, by capability)

```
events/          domain: Event, SubscriptionMatcher
                 application: IngestEventUseCase
                 adapters: in REST /events · out persistence + outbox
notifications/   domain: NotificationEvent, DeliveryAttempt, DeliveryStatus, DeliveryOutcome, RetryPolicy
                 application: ListNotificationEvents, GetNotificationEvent, ReplayNotificationEvent,
                              DispatchNotificationEvent, MarkExhausted
                 ports out: NotificationRepository, DispatchPublisher, WebhookSender,
                            SignatureService, SecretCipher, DestinationResolver, Clock
                 adapters: in REST, Kafka consumers, outbox relay scheduler, seed loader
                           out persistence, Kafka producer, HTTPS client
subscriptions/   domain: Subscription, DestinationPolicy
                 application: create, list, get, deactivate, patch
                 adapters: in REST · out persistence
shared/          security (JWT, scopes, client identity) · crypto (HMAC, AES-GCM) · observability · audit
```

The domain decides whether an error is retried (D21): `DeliveryOutcome` is `SUCCESS`, `RETRYABLE` or `PERMANENT`. The Kafka adapter only maps `RETRYABLE` to a retry topic. If Kafka is replaced (fallback plan), retry rules do not change.

## C4: components (notifications capability)

```mermaid
flowchart LR
    subgraph inbound["Inbound adapters"]
        rest["REST: notification_events, replay"]
        kin["Kafka consumer: dispatch and dead letter"]
        relay["Scheduler: outbox relay, next attempt"]
    end
    subgraph app["Application"]
        query["ListNotificationEvents, GetNotificationEvent"]
        replay["ReplayNotificationEvent"]
        dispatch["DispatchNotificationEvent, MarkExhausted"]
    end
    subgraph domain["Domain"]
        nev["NotificationEvent, DeliveryAttempt"]
        outcome["DeliveryOutcome: SUCCESS, RETRYABLE, PERMANENT"]
    end
    subgraph outbound["Outbound ports and adapters"]
        repo["NotificationRepository: JDBC"]
        pub["DispatchPublisher: Kafka producer"]
        http["WebhookSender: HTTPS client"]
        dest["DestinationResolver: DestinationPolicy"]
    end
    rest --> query
    rest --> replay
    kin --> dispatch
    relay --> dispatch
    dispatch --> nev
    dispatch --> outcome
    query --> repo
    replay --> repo
    dispatch --> repo
    dispatch --> pub
    dispatch --> http
    http --> dest
```

The domain never imports a framework or an adapter (`HexagonalArchitectureTests`). Only the adapters map `RETRYABLE` to a retry topic.

## Data model

| Table | Key columns |
|---|---|
| `events` | `id`, `client_id`, `event_type`, `content`, `created_at` |
| `subscriptions` | `id`, `client_id`, `url`, `event_types[]`, `active`, `secret_ciphertext`, `created_at` |
| `notifications` | `id`, `event_id`, `subscription_id`, `client_id` (denormalized for isolation), `destination_url` (snapshot), `delivery_status`, `round`, `attempts`, `created_at`, `delivery_date`, `delivered_at`, `last_error`, `version` (optimistic locking) |
| `delivery_attempts` | `notification_id`, `round`, `attempt_number`, `http_status`, `error_type`, `latency_ms`, `attempted_at`; unique `(notification_id, round, attempt_number)` |
| `outbox` | `id`, `notification_id`, `round`, `payload`, `created_at`, `published_at` |

## Notification event states

```mermaid
stateDiagram-v2
    [*] --> pending: event matches a subscription
    pending --> completed: 2xx
    pending --> retrying: transient error
    retrying --> completed: 2xx
    retrying --> retrying: transient error (attempt < 5)
    pending --> failed: permanent error
    retrying --> failed: permanent error or attempts exhausted
    failed --> pending: replay (round + 1)
    completed --> [*]
```

## Main flows

### F1. Ingestion

```mermaid
sequenceDiagram
    participant P as Producer
    participant API as api role
    participant DB as PostgreSQL
    P->>API: POST /events (scope events:write)
    API->>DB: BEGIN
    API->>DB: insert event (409 if event_id exists)
    API->>DB: find active subscriptions (same client_id, subscribed event_type)
    API->>DB: insert one notification event per match (pending, URL snapshot)
    API->>DB: insert outbox rows
    API->>DB: COMMIT
    API-->>P: 202 Accepted (event_id, notification count)
```

### F2 and F3. Outbox relay and delivery

```mermaid
sequenceDiagram
    participant R as Outbox relay
    participant DB as PostgreSQL
    participant K as Kafka
    participant C as Dispatch consumer
    participant W as Client webhook
    R->>DB: SELECT … FOR UPDATE SKIP LOCKED
    R->>K: publish to notification.dispatch (key = notification_event_id)
    R->>DB: mark published
    K->>C: dispatch message
    C->>DB: load notification event (skip if completed or older round)
    C->>C: validate destination (HTTPS, allowlist, IP not private)
    C->>W: POST, HMAC-signed, timeouts 2/5/10 s
    C->>DB: store attempt and apply outcome
    Note over C,K: RETRYABLE → retry topic with backoff<br/>PERMANENT → failed<br/>last attempt → failed in the same write, then dead-letter topic + alert (D70)
```

### F4. Dead-letter topic

The last retryable attempt of a round already marked the notification event `failed` and set `delivery_date`, in the same write as the attempt (D70). Its message then goes straight to the dead-letter topic, without another retry hop. The handler increments a metric for every dead-lettered message first, then marks the event `failed` only if it is still open (a fallback, for example when Kafka runs out of retry hops before the attempts are used up). Grafana alerts on any new dead-letter message.

### F5. Replay

1. Load the notification event by ID **and** the token's `client_id`; otherwise `404`.
2. If it is not `failed`, return `409`.
3. Conditional update on `version` (two concurrent replays cannot both win): `round + 1`, `pending`, new outbox row, in one transaction.
4. Return `202`.

### F6. Query

`GET /notification_events?created_from&created_to&delivery_status&page&size` with `client_id` taken only from the token and a page size of at most 100. `GET /notification_events/{id}` returns the detail with its attempts, or `404` if it belongs to another client.

## C4: dynamic view, delivery with retry and dead letter

```mermaid
sequenceDiagram
    participant P as Producer
    participant A as API
    participant DB as PostgreSQL
    participant R as Outbox relay
    participant K as Kafka
    participant W as Worker
    participant C as Client receiver

    P->>A: POST /events
    A->>DB: event, notifications and outbox row (one transaction)
    A-->>P: 202
    R->>DB: lock unpublished outbox rows (SKIP LOCKED)
    R->>K: publish to notification.dispatch
    K->>W: dispatch message
    W->>C: signed POST (attempt 1)
    C-->>W: 5xx or timeout (RETRYABLE)
    W->>K: retry topic with backoff (attempts 2 to 4)
    W->>C: attempt 5
    C-->>W: 5xx (RETRYABLE, last attempt)
    W->>DB: notification failed in the same write (D70)
    W->>K: message to the dead-letter topic
    Note over W,K: a 2xx at any attempt completes the event; a PERMANENT outcome fails it at once
```

## Capacity

Under the [load assumptions](../requirements/objectives.md#load-assumptions):

- **Deliveries:** 500 events/s × 1.2 subscriptions = about **600 deliveries/s**.
- **Concurrent deliveries (Little's law):** assuming 90% of receivers answer in 0.3 s and 10% in 5 s, the mean is 0.77 s, so 600 × 0.77 ≈ **460 deliveries in flight**.
- **Known limit:** a Kafka consumer processes each partition sequentially. At 0.77 s per message a partition handles about 1.3 deliveries/s, so about 460 partitions would be needed. **This does not scale as is.**
  - **MVP:** single-record consumption with virtual threads and about 48 partitions; enough for the demo.
  - **Evolution:** Confluent Parallel Consumer (per-key parallelism inside a partition) or a batch listener fanned out to virtual threads.
- **PostgreSQL:** about 2,500 writes/s (events, notifications, outbox, attempts); manageable on one managed instance. Evolution: outbox cleanup, monthly partitioning of `delivery_attempts`, 90-day retention (to be confirmed).

## Failure modes

| Failure | Behavior |
|---|---|
| Kafka down | `POST /events` keeps accepting events; the outbox accumulates them and the relay retries. An alert fires on outbox age. No data loss. |
| PostgreSQL down | The API returns `503`. Consumers pause with backoff; Kafka retains messages. |
| Client receiver down | Retries, then `failed` after the fifth attempt and a dead-letter message; the client can replay. Other clients are unaffected. |
| Application crashes mid-delivery | The offset is not committed and the message is redelivered. The idempotency check avoids double recording, but **the webhook may be sent twice**; receivers deduplicate with `Cobre-Notification-Id`. |
| Relay crashes after publishing, before marking | Duplicate publish, absorbed by the idempotent consumer. |

## Known limitations

Found by the dual blind review of the delivery path (2026-10-30, see #53) and accepted for the challenge. None changes the status contract or loses a notification event.

| Limitation | Effect | Possible fix |
|---|---|---|
| The webhook HTTP client uses the Apache pool defaults (5 connections per route, 25 in total) and no connection-request timeout. | Time spent waiting for a pooled connection counts toward the 10-second attempt limit (D50), so a busy receiver could see attempts recorded as `timeout` without a request being sent. Today the dispatch consumer runs one listener thread, which masks it. | Size the pool for the consumer concurrency and set a short connection-request timeout that is not charged to the attempt. |
| Published `outbox` rows and `delivery_attempts` rows are never purged. | Both tables grow without bound. The relay index is partial, so relay scans stay cheap, but storage and vacuum cost grow. | A retention job, for example deleting outbox rows published more than 7 days ago and archiving old attempts. |
| A duplicate dispatch message for a `retrying` event is claimed and sent at once (reported by one reviewer, not reproduced). | Under at-least-once delivery (D55), a duplicate can skip the backoff step and use one of the 5 attempts. A `Retry-After` wait is protected, because `next_attempt_at` blocks early sends. | Store a not-before time for backoff steps too, and refuse a claim before it. |
| `notification.delivery.end_to_end.duration` is measured from the notification event's `created_at`, also for a replayed round. | After a replay, the "creation to final outcome" panel includes the time before the replay, which skews its p95. The failed-rate alert counts outcomes only and is unaffected. | Measure a replayed round from its replay time, or tag replayed rounds and leave them out of the panel. |

## Sensitive data

`content` is never logged, always travels over TLS and is returned only to its owner. Encryption at rest is delegated to the database in production (for example, RDS with KMS); there is none locally.

## Scalability and evolution

- Stateless replicas; Kafka partitions and the consumer group provide parallelism; `SKIP LOCKED` allows several relays.
- Non-blocking retries isolate unhealthy receivers; strict timeouts protect worker capacity.
- Target production platform: AWS (container service, managed Kafka such as MSK or Confluent Cloud, RDS).
- Evolutions: per-destination circuit breaker, Debezium CDC for the outbox, keyset pagination, Redis-backed rate limiting, a Kafka consumer adapter for platform events, mTLS for internal producers.

## Target AWS deployment

> **Target design, not deployed.** The challenge runs locally with Docker Compose (D34). This diagram shows how the same containers would map to AWS; no cloud resources exist.

```mermaid
flowchart LR
    producer["Cobre platform producers"]
    client["Client systems"]
    receiver["Client webhook receivers"]
    idp["Identity provider<br/>(Keycloak or Cognito)"]

    subgraph aws["AWS (target, not deployed)"]
        alb["Load balancer<br/>HTTPS"]
        subgraph ecs["Container service"]
            api["api role<br/>(own deployment)"]
            worker["worker role<br/>(own deployment)"]
        end
        rds[("RDS PostgreSQL<br/>source of truth + outbox<br/>encrypted with KMS")]
        kafka[["Managed Kafka<br/>(MSK or Confluent Cloud)<br/>dispatch, retry topics, dead-letter topic"]]
    end
    grafana["Grafana Cloud or similar<br/>metrics, logs, traces, alerts"]

    producer -- "POST /events (service JWT)" --> alb
    client -- "Self-service API (client JWT)" --> alb
    alb --> api
    api --> rds
    worker --> rds
    worker <--> kafka
    worker -- "HTTPS webhook, HMAC-signed" --> receiver
    api -- OTLP --> grafana
    worker -- OTLP --> grafana
    idp -. "Issues JWTs" .-> producer
    idp -. "Issues JWTs" .-> client
    api -. "Validates JWTs (JWKS)" .-> idp
```

The `api` and `worker` roles run from the same image (D31) as separate deployments, so each can be scaled independently. Only the `worker` role makes outbound webhook calls, which keeps the SSRF-relevant egress in one place.
