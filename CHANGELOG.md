# Changelog

Notable changes to cobre-webhook-notifications, grouped by capability. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project has no tagged release yet, so everything is under Unreleased. Numbers are the pull requests merged into `develop`; decisions (D-numbers) are in the [decision log](docs/architecture/decision-log.md).

## [Unreleased]

### Self-service API

- Added: list notification events with filters by creation date and delivery status, with pagination, and read one with its delivery history (#3).
- Added: replay a notification event that has definitely failed; it starts a new delivery round from the database (#9). Replay is allowed even if the subscription is now inactive (D57, D67).
- Added: OpenAPI documentation and Swagger UI (#36).
- Fixed: `POST /events` returns `400` only for validation failures (#24).

### Subscriptions

- Added: subscription management (`POST`, `GET`, `PATCH`, `DELETE /subscriptions`), with the signing secret returned once (#5).
- Changed: at most 50 active subscriptions per client, enforced atomically and refused with `409` (#19, D51). Event type bounds and a list capped at 100 are covered by tests (#29, D52).

### Event ingestion and delivery

- Added: event ingestion with matching against active subscriptions, a transactional outbox and webhook delivery over Kafka with retry topics and a dead-letter topic (#6).
- Added: delivery only to validated destinations (HTTPS, port 443, allowlisted hosts with public addresses), with an HMAC-SHA256 `Cobre-Signature` and the `Cobre-Notification-Id` deduplication header (#10). Receiver verification is documented in the README (#31).
- Added: recovery of deliveries interrupted during processing, with a lease and a fencing generation (#20).
- Added: `Retry-After` on `429` and `503` is honoured up to a 300-second cap through a database-scheduled next attempt (#30, D53, D63, D68). `Retry-After: 0` and past dates use the configured backoff step (#35).
- Changed: the last retryable attempt of a round marks the notification event `failed` in the same write, and its message goes straight to the dead-letter topic; clients no longer see a transient `retrying` with no attempts left (#53, D70).
- Changed: a subscription without a secret is never delivered unsigned; the attempt fails with `missing_secret` (#26, D67). Deactivating a subscription only stops matching new events.
- Fixed: DNS failures at send time are retryable and recorded as `dns_error` (#32, D54, D66); the destination lookup has its own deadline (#38), and an unexpected resolver failure is a DNS failure (#45).
- Tests: `Retry-After`, the cap and replay refusals end to end over HTTPS (#40, #43, #49).

### Observability and audit

- Added: delivery metrics over OTLP, a Grafana dashboard and alerts, structured JSON logs, an audit log and per-client rate limiting (#13).
- Fixed: the dashboard sits in the alerts folder and the alerts are split (#16); the failure alert counts failed notifications, not permanent attempts (#23).

### Platform and demo

- Added: project scaffold, OpenSpec and CI (#1).
- Added: the demo webhook secret comes from external configuration (`DEMO_WEBHOOK_SECRET`), never from the repository (#25, #37).

### Documentation and process

- Architecture: target AWS deployment diagram and D48 (#2); a proposal for how webhook destinations are owned (#48).
- Decisions: reconciliation plan and D49-D50 (#11); D51-D58 (#21); D63-D67, with D62 superseded (#27); D68 (#34).
- OpenSpec: changes proposed (#4, #18) and archived into `openspec/specs/` (#4, #33, #44, #50).
- Audit: discrepancy audit of decisions, specs and code (#8), and README and documentation corrections (#22).
- Runbook and demo: real HTTPS delivery from a local run (#14, #15, #28, #39, #41, #46, #51), and a seed and demo test plan (#17).
- AI usage log: entry for change 2 (#7). DNS failures documented as retryable in `DeliveryOutcome` (#42).
- Fixed: replay integration test compilation (#12).
