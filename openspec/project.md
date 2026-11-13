# Project Context

## Purpose

Deliver platform events to client webhooks over HTTPS and let clients query and replay their notifications through a self-service REST API. Built for the Cobre technical challenge (see `docs/challenge/`).

## Tech Stack

- Java 25, Spring Boot 4.1, Gradle (Kotlin DSL)
- PostgreSQL (source of truth, transactional outbox), Flyway migrations
- Apache Kafka (dispatch, retry topics, dead-letter topic)
- OAuth2 Resource Server (JWT, RS256)
- OpenTelemetry exported to Grafana LGTM
- Tests: JUnit 5, Testcontainers (PostgreSQL, Kafka), WireMock (HTTPS webhook receiver)

## Capabilities

The specs follow the capabilities defined in `docs/challenge/CHALLENGE_PLAN.md`:

| Spec | Scope |
|---|---|
| `platform-design` | Architecture, data model, flows, capacity assumptions, failure modes |
| `self-service-api` | Listing with filters and pagination, detail, replay, client isolation |
| `notification-delivery` | Event ingestion, subscriptions, HTTPS delivery, retries, DLT, HMAC, idempotency |
| `platform-security` | Authentication, scopes, SSRF policy, rate limiting, audit, OWASP mitigations |
| `quality-and-delivery` | Testing, observability, documentation, local environment |

## Conventions

- **Architecture**: hexagonal, packaged by capability (`events`, `notifications`, `subscriptions`), each with `domain`, `application` and `adapters`. `domain` has no framework dependencies. Use cases are exposed as input ports; infrastructure is reached through output ports.
- **Language**: code, specs and documentation in English.
- **API**: JSON fields in `snake_case`, matching the provided sample data and the `/notification_events` path. Timestamps in ISO-8601 UTC. Errors as RFC 9457 `application/problem+json`.
- **Identity**: a client's identity comes only from the `client_id` claim of its access token, never from the path, query or body.
- **Not found vs. forbidden**: a resource owned by another client returns `404`, so resource existence is not disclosed.
- **IDs**: new IDs are UUIDv7. Seeded notifications keep their sample IDs (`EVT001`...).
- **Commits**: Conventional Commits, on feature branches only.

## Domain Vocabulary

| Term | Meaning |
|---|---|
| Event | A fact produced by the platform for one client (`event_type`, `content`) |
| Subscription | A client's webhook destination and the event types it wants |
| Notification event | One delivery of one event to one subscription; has its own status and history |
| Delivery attempt | One HTTPS call for a notification event |
| Round | A sequence of up to 5 attempts; a replay starts a new round |
| `delivery_status` | `pending`, `retrying`, `completed`, `failed` |
| `delivery_date` | Timestamp of the final outcome of the current round (`completed` or `failed`); null while pending |
| `delivered_at` | Timestamp of the first successful (2xx) attempt; null if none |

## Assumptions

- Load: 1,000 clients, peak 500 events/s, about 1.2 matching subscriptions per event.
- Sample data: `created_at` is not present in `notification_events.json`; seeded records use `created_at = delivery_date`.
