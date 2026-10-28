# cobre-webhook-notifications

Delivers platform events to client webhooks over HTTPS and gives clients a self-service REST API to query and replay their notifications. Built for the Cobre technical challenge.

> **Status:** project scaffold. The first change (`add-self-service-query`) is specified and pending implementation. See [Roadmap](#roadmap).

## What it does

- **Delivery:** each event is matched against the client's active subscriptions and sent to their webhook over HTTPS, with retries, a dead-letter topic and the final outcome stored.
- **Self-service API:** clients list their notification events (filtered by creation date and delivery status), read one with its delivery history, and replay one that has definitely failed.
- **Isolation:** a client only ever sees and receives its own notifications. Its identity comes from its access token, never from the request.
- **Observability:** metrics, logs and alerts in near real time for the monitoring team.

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /notification_events` | List notification events with filters and pagination | Specified |
| `GET /notification_events/{notification_event_id}` | Notification event detail with delivery attempts | Specified |
| `POST /notification_events/{notification_event_id}/replay` | Re-send a failed notification event | Planned |
| `POST /events` | Ingest a platform event (internal, service credentials) | Planned |
| `/subscriptions` | Manage webhook subscriptions | Planned |

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

The project includes Spring Boot Docker Compose support, so `./gradlew bootRun` is expected to start these services automatically. Running the application end to end is verified in the first implemented change.

### Webhook configuration

| Variable | Required | Purpose |
|---|---|---|
| `WEBHOOK_SECRETS_KEY` | Yes, outside the `local` profile | Base64-encoded 256-bit AES key that encrypts webhook signing secrets at rest. The application does not start without it. Generate one with `openssl rand -base64 32`. |
| `WEBHOOK_ALLOWED_HOSTS` | No (empty rejects every URL) | Comma-separated hosts that subscriptions may point to. An entry starting with `.` (for example `.example.org`) also allows that domain's subdomains. Only `https` on port 443 is accepted, and every resolved address must be public. |

The `local` profile ships a development-only key; never reuse it elsewhere.

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
| 1 | `add-self-service-query` | Specified |
| 2 | `add-event-ingestion-and-delivery` | Planned |
| 3 | `add-notification-replay` | Planned |
| 4 | `add-subscription-management` | Planned |
| 5 | `add-observability-and-audit` | Planned |

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
| [OpenSpec project context](openspec/project.md) | Conventions, domain vocabulary and assumptions |
| [AI usage log](docs/ai-usage/README.md) | How AI was used and validated in each phase |
