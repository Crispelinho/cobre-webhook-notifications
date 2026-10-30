# cobre-webhook-notifications

Delivers platform events to client webhooks over HTTPS and gives clients a self-service REST API to query and replay their notifications. Built for the Cobre technical challenge.

> **Status:** the five planned changes and the reconciliation changes for the subscription limits, the `Retry-After` cap, processing recovery and signed delivery rules are merged into `develop`. Retrying transient DNS failures is specified and not yet implemented. See the [Roadmap](#roadmap) and the [reconciliation plan](docs/process/discrepancy-reconciliation-2026-10-28.md).

## What it does

- **Delivery:** each event is matched against the client's active subscriptions and sent to their webhook over HTTPS, with retries, a dead-letter topic and the final outcome stored.
- **Self-service API:** clients list their notification events (filtered by creation date and delivery status), read one with its delivery history, and replay one that has definitely failed.
- **Isolation:** a client only ever sees and receives its own notifications. Its identity comes from its access token, never from the request.
- **Observability:** metrics, logs and alerts in near real time for the monitoring team.

| Endpoint | Purpose | Caller |
|---|---|---|
| `GET /notification_events` | List notification events with filters and pagination | Client (`notifications:read`) |
| `GET /notification_events/{notification_event_id}` | Notification event detail with delivery attempts | Client (`notifications:read`) |
| `POST /notification_events/{notification_event_id}/replay` | Re-send a failed notification event (`202`) | Client (`notifications:replay`) |
| `POST /events` | Ingest a platform event (`202`; `409` for a repeated `event_id`) | Producer (`events:write`) |
| `POST /subscriptions` | Register a webhook subscription; the signing secret is returned once | Client (`subscriptions:write`) |
| `GET /subscriptions`, `GET /subscriptions/{subscription_id}` | List and read subscriptions | Client (`subscriptions:read`) |
| `PATCH /subscriptions/{subscription_id}` | Change `active` and `event_types` only | Client (`subscriptions:write`) |
| `DELETE /subscriptions/{subscription_id}` | Deactivate a subscription | Client (`subscriptions:write`) |

## Architecture

Hexagonal architecture, packaged by capability:

```
src/main/java/com/cobre/notifications/
├─ events/          ingestion and subscription matching
├─ notifications/   query, replay and delivery
├─ subscriptions/   webhook destinations
└─ shared/          security, crypto, observability, audit
```

Each capability is split into `domain` (no framework dependencies), `application` (use cases and ports) and `adapters` (REST, Kafka, persistence, HTTPS).

**Delivery flow:** `POST /events` → one PostgreSQL transaction stores the event, its notifications and an outbox row → an outbox relay publishes to Kafka → a consumer sends the signed webhook → transient errors go through retry topics with backoff → exhausted messages land in a dead-letter topic and the notification is marked `failed`.

PostgreSQL is the source of truth. Kafka is the transport. A replay starts a new delivery round from the database.

## Tech stack

Java 25 · Spring Boot 4.1 · Gradle (Kotlin DSL) · PostgreSQL 18 · Apache Kafka 4 · OpenTelemetry with Grafana LGTM · JUnit 5 · Testcontainers

## Getting started

### Requirements

- JDK 25
- Docker (used by `compose.yaml` and by Testcontainers)

### Build and test

```bash
./gradlew build
```

### Local infrastructure

```bash
docker compose up -d
```

| Service | Port |
|---|---|
| PostgreSQL | dynamic (see `docker compose ps`) |
| Kafka | `9092` |
| Grafana | [http://localhost:3000](http://localhost:3000) |

The project includes Spring Boot Docker Compose support, so `./gradlew bootRun` starts these services automatically. To run the full flow against a real HTTPS receiver, follow the [local HTTPS delivery runbook](docs/process/local-https-delivery-runbook.md). Profiles: `local` enables the development token issuer and loads the sample data; `demo` shortens the retry delays so a full round takes minutes. Use them together with `SPRING_PROFILES_ACTIVE=local,demo`.

### Webhook configuration

| Variable | Required | Purpose |
|---|---|---|
| `WEBHOOK_SECRETS_KEY` | Yes, outside the `local` profile | Base64-encoded 256-bit AES key that encrypts webhook signing secrets at rest. The application does not start without it. Generate one with `openssl rand -base64 32`. |
| `WEBHOOK_ALLOWED_HOSTS` | No (empty rejects every URL) | Comma-separated hosts that subscriptions may point to. An entry starting with `.` (for example `.example.org`) also allows that domain's subdomains. Only `https` on port 443 is accepted, and every resolved address must be public. The `local` profile allows `webhook.example.com`, which the sample data uses. |

The `local` profile ships a development-only key; never reuse it elsewhere.

### Receiving webhooks

Every attempt is an HTTPS `POST` with these headers:

| Header | Meaning |
|---|---|
| `Cobre-Notification-Id` | Identifier of the notification event. It is the same on every attempt and in every round, including a replay. |
| `Cobre-Signature` | `t=<unix seconds>,v1=<hex>`, where `v1` is the lowercase hex HMAC-SHA256 of `t + "." + body` using the subscription secret. |

To verify a request:

1. Read `t` and `v1` from `Cobre-Signature`.
2. Reject the request if `t` is more than 5 minutes away from your clock.
3. Compute HMAC-SHA256 of `t + "." + raw body bytes` with your secret and compare it to `v1` in constant time. Use the raw bytes as received, not a re-serialized JSON.

`t` is taken at send time on every attempt, so a retry sent after a long backoff still verifies within the tolerance. The body is byte-identical on every attempt and in every round; only `t` and `v1` change.

Delivery semantics:

- Delivery is **at-least-once** while the configured attempts and recovery operations run. A webhook may reach you more than once. There is no end-to-end exactly-once guarantee.
- Deduplicate on `Cobre-Notification-Id`: process a given identifier once and answer `2xx` for repeats.
- A timeout does not prove that you did not process the request. If you processed it but answered too late, the notification event is retried with the same `Cobre-Notification-Id` and the same body.

## How we work

The project follows a hybrid **ODD + SDD** approach (objective-driven, then specification-driven) with [OpenSpec](https://github.com/Fission-AI/OpenSpec).

1. Every change starts as an OpenSpec proposal with Given/When/Then scenarios in `openspec/changes/`.
2. A human approves the specification before any code is written.
3. Each scenario becomes an automated test.
4. Before each pull request, an independent AI reviewer audits the diff against the specification; a human decides which findings to apply.
5. After merge, the change is archived into `openspec/specs/`.

**Branching:** `main` and `develop`. Feature branches start from `develop`, one per OpenSpec change. Pull requests into `develop` are squash-merged; `develop` is merged into `main` with a merge commit.

**Commits:** [Conventional Commits](https://www.conventionalcommits.org/), for example `feat(notifications): add self-service query endpoints`.

**CI:** every pull request validates the OpenSpec changes, builds and runs the tests, and reviews new dependencies for known vulnerabilities.

```bash
openspec validate --all --strict
```

## Roadmap

| # | OpenSpec change | Status |
|---|---|---|
| 1 | `add-self-service-query` | Merged and archived |
| 2 | `add-event-ingestion-and-delivery` | Merged (#6); spec still in `openspec/changes/` |
| 3 | `add-notification-replay` | Merged (#9); spec still in `openspec/changes/` |
| 4 | `add-subscription-management` | Merged and archived |
| 5 | `add-observability-and-audit` | Merged (#13); spec still in `openspec/changes/` |
| 6 | `add-subscription-limits` | Merged (#19, #29); spec still in `openspec/changes/` |
| 7 | `add-retry-after-cap` | Merged (#30); spec still in `openspec/changes/` |
| 8 | `add-processing-recovery` | Merged (#20); spec still in `openspec/changes/` |
| 9 | `add-signed-delivery-rules` | Merged (tests and receiver documentation; the behaviour already existed); spec still in `openspec/changes/` |
| 10 | `add-transient-dns-retry` | Specified, not implemented |

Changes 6 to 10 come from the [reconciliation plan](docs/process/discrepancy-reconciliation-2026-10-28.md). Each one is specified in `openspec/changes/` before any code.

## Documentation

| Document | Content |
|---|---|
| [Problem statement](docs/challenge/problem_statement.md) | The challenge as provided by Cobre |
| [Challenge plan](docs/challenge/CHALLENGE_PLAN.md) | Phases, governance and capabilities |
| [Sample data](docs/challenge/notification_events.json) | Notification events used as seed data |
| [Objectives and scope](docs/requirements/objectives.md) | ODD objectives, success criteria, load assumptions and MoSCoW scope |
| [Requirements analysis](docs/requirements/analysis.md) | Sample data observations and how each ambiguity was resolved |
| [System design](docs/architecture/system-design.md) | C4 diagrams, components, data model, flows, capacity and failure modes |
| [Decision log](docs/architecture/decision-log.md) | Every design decision with alternatives, rationale and origin |
| [Security analysis](docs/security/security-analysis.md) | Threat model, OWASP API Top 10 mitigations and supply-chain findings |
| [Workflow](docs/process/workflow.md) | SDD lifecycle, branching, commits, CI and AI review |
| [Local HTTPS delivery runbook](docs/process/local-https-delivery-runbook.md) | Receiving a signed webhook over real HTTPS from a local run, with verified results |
| [Seed and demo test plan](docs/process/seed-and-demo-test-plan.md) | Test cases for seed data, delivery, failure, replay and observability |
| [Discrepancy audit](docs/process/discrepancy-audit-2026-10-28.md) | Differences found between decisions, specs and code |
| [Discrepancy reconciliation](docs/process/discrepancy-reconciliation-2026-10-28.md) | How each difference is resolved, and the pending work |
| [OpenSpec project context](openspec/project.md) | Conventions, domain vocabulary and assumptions |
| [AI usage log](docs/ai-usage/README.md) | How AI was used and validated in each phase |
