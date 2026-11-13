# Change: Add event ingestion and delivery

## Why

The core of the challenge: platform events must reach the client's webhook over HTTPS, with an efficient retry strategy and the final outcome stored. Without this change the self-service API only shows sample data.

## What Changes

- `POST /events` for internal producers (scope `events:write`): stores the event, matches active subscriptions of the same client, and creates one notification event per match, all in one transaction with an outbox row.
- Outbox relay that publishes dispatch messages to Kafka (`SELECT … FOR UPDATE SKIP LOCKED`).
- Dispatch consumer that sends the webhook over HTTPS with strict timeouts and no redirects, records every attempt, and applies the outcome.
- Non-blocking retries with retry topics and exponential backoff with jitter; exhausted messages go to a dead-letter topic and the notification event becomes `failed`.
- The domain classifies outcomes (`SUCCESS`, `RETRYABLE`, `PERMANENT`); Kafka only routes them.
- Final batch, after `add-subscription-management` is merged: send-time destination validation (SSRF) and HMAC signing of each webhook.

## Decisions to confirm

- Event `content` limited to 10,000 characters; `event_type` must match `^[a-z0-9_]{1,100}$`.
- `POST /events` returns `202` with the event ID and the IDs of the notification events it created.
- A `3xx` response is a permanent failure (redirects are never followed).

## Impact

- Affected specs: `notification-delivery` (new), `platform-security`
- Affected code: `events` capability (new), `notifications` dispatch use cases and adapters (Kafka, HTTPS, outbox relay), Flyway `V2` (outbox table)
- Depends on: `add-subscription-management` for the final batch only
- Out of scope: replay (`add-notification-replay`), metrics and alerts (`add-observability-and-audit`)
