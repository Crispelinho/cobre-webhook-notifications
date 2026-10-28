# Decision Log

All decisions taken during refinement and design (2026-10-28). Each one was approved by a human. "Origin" shows who proposed it: **Human** (the developer), **AI** (proposed by the assistant and approved), or **Human over AI** (the developer chose differently from the AI recommendation).

## Domain and API

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D01 | Events enter through `POST /events`, are stored and matched against active subscriptions. | Seed only; real broker consumer. | Controllable demo; a Kafka consumer adapter is a documented evolution. | Human |
| D02 | `notification_events.json` is seed data for querying, not the ingestion mechanism. | Treat records as pending deliveries. | Keeps the original state; failed records are immediate replay candidates. | Human |
| D03 | Add `created_at` (filter field). `delivery_date` = final outcome of the current round; `delivered_at` = first 2xx. | `delivery_date` = first 2xx, transforming the sample. | Keeps the sample unchanged and matches "storing the final information related to the delivery". | AI |
| D04 | One notification event per matching subscription (1:N). | One notification per event (1:1). | More realistic; replay targets one destination. | Human over AI |
| D05 | `failed` = permanent error or exhausted retries; only transient errors are retried. | — | Direct reading of "definitely failed". | Human |
| D06 | Replay is asynchronous (`202`), only from `failed`, keeps ID and history, starts a new round of up to 5 attempts. | Synchronous replay. | Delivery may take minutes; the API must not block. | Human |
| D07 | `POST /events` with an existing `event_id` returns `409 Conflict` and creates no notifications. Producers treat `409` as "already accepted". | `200` with the original result. | Explicit signal of a duplicate. | Human over AI |
| D08 | Offset pagination, default 20, maximum 100. | Cursor (keyset) pagination. | Simpler; the cap limits cost. Keyset is a documented evolution. | AI |
| D09 | New IDs are UUIDv7; seeded records keep `EVT001`… | UUIDv4. | Time-ordered IDs keep index locality. | AI |
| D10 | JSON in `snake_case`; errors as RFC 9457 `application/problem+json`; timestamps ISO-8601 UTC. | camelCase; custom error body. | Matches the sample and the `/notification_events` path; standard error format. | AI |

## Subscriptions

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D11 | Seed subscriptions plus a minimal CRUD: `POST` (returns the secret once), `GET` list, `GET` by ID, `DELETE` (logical deactivation). | Seed only. | Lets the presentation URL be registered at runtime and validated against SSRF rules. | Human + AI |
| D12 | `PATCH` limited to `active` and `event_types`; any other field returns `400`. Changing the URL means delete and create. | Full `PATCH`; no `PATCH`. | The URL is the destination's identity; prevents mass assignment. | AI |
| D13 | Every notification event stores a snapshot of the destination URL. | Reference the subscription only. | History stays immutable when a subscription changes. | AI |

## Messaging and delivery

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D14 | Kafka for dispatch, retry topics and dead-letter topic; PostgreSQL as source of truth with a transactional outbox. | RabbitMQ; SQS on LocalStack; PostgreSQL-only queue. | Aligns with the stack Cobre publishes in job postings (secondary source, not confirmed by Cobre). The outbox avoids the dual-write problem. | Human over AI |
| D15 | Outbox relay polls with `SELECT … FOR UPDATE SKIP LOCKED`. | Debezium CDC. | No extra infrastructure; safe with several replicas. Debezium is the evolution. | AI |
| D16 | Kafka partition key = `notification_event_id`; no ordering guarantee (documented). | `subscription_id` (per-destination order). | Even load; a slow client cannot block others on its partition. | AI |
| D17 | Non-blocking retries with `@RetryableTopic`; the dead-letter handler marks the notification event `failed`. Replay starts from the database, never from the dead-letter topic. | Blocking retries. | One unhealthy receiver does not delay others. | AI |
| D18 | 5 attempts per round, exponential backoff with jitter, per profile (demo: 0 s, 10 s, 30 s, 1 min, 2 min). | Fixed delays. | Visible in a live demo; realistic in production profile. | AI |
| D19 | Retried: timeouts, connection errors, `5xx`, `408`, `429` (honoring a capped `Retry-After`). Immediate `failed`: other `4xx`, TLS or certificate errors, SSRF-blocked destinations. | Retry everything. | Retrying permanent errors wastes capacity. | AI |
| D20 | HTTP timeouts: connect 2 s, read 5 s, total 10 s. | Library defaults. | A slow receiver must not hold consumer threads. | AI |
| D21 | The domain classifies outcomes (`SUCCESS`, `RETRYABLE`, `PERMANENT`); the Kafka adapter only maps them to retry or no retry. | Retry rules inside Kafka configuration. | Business rules survive a broker change (fallback plan). | AI |

## Security

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D22 | One mechanism (JWT, OAuth2 Resource Server, RS256) with separate scopes and audience: clients get `notifications:read`/`notifications:replay` and a `client_id` claim; producers get `events:write` and an internal audience. A client token on `POST /events` returns `403`. | mTLS for producers; static API key. | Simple, testable; mTLS is the production evolution. | AI |
| D23 | Locally, tokens come from a development-only tool; Keycloak or Cognito is the production issuer. | Keycloak in Docker Compose. | Less infrastructure that can fail during the demo. | AI |
| D24 | Webhooks signed with HMAC-SHA256: `Cobre-Signature: t=<ts>,v1=<hex>` over `t + "." + body`, 5-minute tolerance, plus `Cobre-Notification-Id` for deduplication. | HTTPS only, HMAC as future work. | Lets receivers verify the sender; low cost. The developer first left HMAC out of the MVP and accepted it after the trade-off was discussed. | AI |
| D25 | Per-subscription secret, shown once, encrypted at rest with AES-GCM (key from environment). | Plain text with documented risk. | HMAC needs the secret in clear to sign, so hashing is not possible. | AI |
| D26 | SSRF: HTTPS only, host allowlist by environment variable, validation at registration and at send time, IP pinning against DNS rebinding, private/loopback/link-local blocked, no redirects, URL never taken from events. | Allow one fixed URL. | Works with per-subscription URLs and the presentation host. | AI |
| D27 | OWASP API Security Top 10 (2023) as primary reference. API1, API2 and API7 are implemented and tested; API3, API4 and API8 are documented. | OWASP Top 10 (2021) only. | The project exposes an API. | AI |
| D28 | Structured audit log for replays and subscription changes. | Audit table. | Cheap and enough for the scope. | AI |
| D29 | Per-client rate limiting with Bucket4j in memory (Could); Redis as evolution. | Documented only. | Mitigates API4 if time allows. | AI |
| D30 | Vulnerable transitive dependencies are pinned with Gradle constraints to the latest patch release of the same line, with the advisory in `because(...)`. Constraints are removed when the upstream library ships a fix. | Upgrade to a new minor version. | Smallest change that fixes the vulnerability. | AI |

## Platform

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D31 | One deployable with `api` and `worker` roles enabled by profile; they can scale as separate deployments. | Two services. | Less overhead in two days; still scales independently. | AI |
| D32 | Java 25, Spring Boot 4.1.2 (latest stable at creation), Gradle with Kotlin DSL. | Maven; Java 21. | Developer's choice; Boot 4.1 supports Java 25. | Human |
| D33 | Observability with OpenTelemetry exported to `grafana/otel-lgtm` (metrics, logs, traces in one container). | Prometheus + Grafana; logs only. | One container covers the three signals for the demo. | AI |
| D34 | Local only with Docker Compose; AWS deployment described in the design. | Cloud deployment. | Time and demo risk. | AI |
| D35 | Container image versions are pinned (no `latest`). | `latest` tags. | Reproducible builds. | AI |

## Process

| ID | Decision | Alternatives considered | Rationale | Origin |
|---|---|---|---|---|
| D36 | `CHALLENGE_PLAN.md` stays technology-agnostic; technology choices live in this log. | Technologies in the plan. | The plan is governance, not design. | Human |
| D37 | All documentation in English. The initial prompt is kept verbatim in Spanish as evidence. | Spanish docs. | Consistent artifacts; evidence must not be altered. | Human |
| D38 | OpenSpec specs follow the five capabilities of the challenge plan. | Seven finer-grained specs. | Direct traceability to the plan. | AI |
| D39 | Branches: `main` and `develop`; one feature branch per OpenSpec change, from `develop`; squash merge into `develop`; merge commit from `develop` into `main`; feature branch deleted after merge. | GitHub Flow without `develop`. | Developer's choice. | Human |
| D40 | Conventional Commits, no AI attribution; the developer reviews every diff before it is committed. | — | Developer's rule. | Human |
| D41 | Test-first at scenario level: every Given/When/Then scenario becomes a test before implementation. | Strict class-level TDD. | Fits two days; no scenario is left untested. | AI |
| D42 | Unit tests for the domain; Testcontainers (PostgreSQL, Kafka) and WireMock over HTTPS for adapters; acceptance tests from OpenSpec scenarios. | — | Each layer tested with the right tool. | AI |
| D43 | CI on every pull request: OpenSpec validation (`--strict`), Gradle build with tests, dependency graph submission and dependency review (fails on high severity). | Build only. | Quality does not depend on anyone's word. | Human + AI |
| D44 | Implementation in the main session; a fresh-context AI reviewer audits each change before its pull request; dual blind review for the delivery and security change. | Delegate implementation to sub-agents. | All design context lives in the main session; the reviewer's value is independence. | AI |
| D45 | Parallel work in separate sessions and Git worktrees: implementation critical path (changes 1 → 2 → 3) in the main session; documentation and presentation in a parallel session; subscription management (change 4) in parallel once change 1 is merged. At most three fronts at once. | One session for everything. | Human review is the bottleneck, not the AI. | AI |
| D46 | Flyway migration numbers reserved per change: V1 query schema, V2 ingestion and outbox, V3 subscription management, V4 replay, V5 observability and audit. | First come, first served. | Parallel branches do not collide. | AI |
| D47 | The `add-self-service-query` specs are approved for implementation. | — | SDD human gate. | Human |
| D48 | The panel deliverable is a slide deck. | Written document. | The statement allows either format; developer's choice. | Human |
