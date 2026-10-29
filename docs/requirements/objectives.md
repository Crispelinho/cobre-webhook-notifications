# Objectives, Success Criteria and Scope (ODD)

Approved on 2026-10-28, before any code was written. Every objective has a criterion that a test or the live demo can check.

## Objectives and success criteria

| # | Objective | Success criterion |
|---|---|---|
| O1 | Client isolation | 100% of cross-client (BOLA) tests return `404`. No list ever contains another client's notification events. |
| O2 | Delivery | Locally, a notification event that matches a subscription gets its first attempt within 2 s (p95). An event without a matching subscription is never sent. |
| O3 | Retries | At most 5 attempts per round. In the demo profile a full round takes 4 min or less. A permanent error produces exactly 1 attempt. Every attempt is stored. |
| O4 | Query | Filters return exactly the expected set on the sample data (for example, CLIENT003 + `failed` returns EVT009 and EVT005). p95 below 300 ms locally. `size` above 100 returns `400`. |
| O5 | Replay | Returns `202`. Returns `409` when the notification event is not `failed`. Of two concurrent replays, exactly one succeeds. |
| O6 | Observability | A metric is visible in Grafana within 30 s. Alerts fire within 1 min when the `failed` rate exceeds 5% over 5 min and when a message reaches the dead-letter topic. |
| O7 | Live demo | The presentation host is allowed through an environment variable and a restart, without recompiling. |
| O8 | Security | Tests for OWASP API1, API2 and API7 pass. Replays and subscription changes are written to the audit log. |
| O9 | Scalability | A capacity calculation under explicit load assumptions is documented (see [system design](../architecture/system-design.md#capacity)). |
| O10 | Quality | `./gradlew build` passes. `docker compose up` reaches a healthy state in under 2 min. |
| O11 | AI transparency | One AI usage log entry per phase, including how the output was validated. |

## Load assumptions

No real figures were provided, so these are stated assumptions, not facts:

- 1,000 clients
- Peak of 500 events/s
- About 1.2 matching subscriptions per event

## Scope (MoSCoW)

The presentation was due three days after the start, with two days left when the design was approved. Scope was cut to protect quality.

| Priority | Items |
|---|---|
| **Must** | The three self-service endpoints with filters and replay. JWT client isolation. `POST /events`. HTTPS delivery with HMAC-signed requests, retries and stored final state. Kafka with transactional outbox and dead-letter topic. Basic SSRF protection (HTTPS only, allowlist, private IP blocking, IP pinning against DNS rebinding, no redirects). Tests for three OWASP risks. C4 diagrams and decisions. README. AI usage log. |
| **Should** | Subscription `POST`, `GET` and `DELETE`. Metrics and a Grafana dashboard. Structured audit log. |
| **Could** | Subscription `PATCH`. AES-GCM encryption of webhook secrets. End-to-end tracing. Per-client rate limiting (Bucket4j). |
| **Won't** | Debezium. Cloud deployment. Secret rotation. |

**Fallback plan:** if Kafka retries and the dead-letter topic do not work by midday of day 1, the dispatch queue switches to a PostgreSQL-backed adapter behind the same output port. The domain does not change.
