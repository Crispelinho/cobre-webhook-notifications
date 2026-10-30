# Change: Add observability and audit

## Why

The challenge requires the delivery capability to be observable in near real time, so the monitoring team can detect deviations and answer client complaints quickly. Sensitive operations must also leave an audit trail.

## What Changes

- Delivery metrics exported over OTLP to Grafana LGTM, with a provisioned dashboard and alert rules.
- Structured JSON logs with trace and notification identifiers, never event content or secrets.
- Structured audit log for replays and subscription changes.
- Could: end-to-end tracing from `POST /events` through Kafka to the webhook; per-client rate limiting with Bucket4j.

## Decisions to confirm

- Alert thresholds for the demo: `failed` rate above 5% over 5 minutes; any new dead-letter message; oldest unpublished outbox row older than 60 seconds.
- Rate limit (if implemented): 100 requests per minute per client, `429` with `Retry-After`.

## Impact

- Affected specs: `quality-and-delivery` (new), `platform-security`
- Affected code: `shared.observability`, `shared.audit`, metric points in the dispatch and replay use cases, `compose.yaml` provisioning for Grafana
- Depends on: `add-event-ingestion-and-delivery`, `add-notification-replay`, `add-subscription-management`
