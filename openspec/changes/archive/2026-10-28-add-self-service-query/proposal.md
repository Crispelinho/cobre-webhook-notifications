# Change: Add self-service notification query

## Why

Clients need to see their notification events and the outcome of each delivery without contacting support. This is the first visible slice of the challenge and sets up the data model, authentication and client isolation that every later change builds on.

## What Changes

- Load the sample data (`notification_events.json`) as seed notifications, with one seed subscription per client.
- Add `GET /notification_events` with filters by creation date range and `delivery_status`, and offset pagination (max 100 per page).
- Add `GET /notification_events/{notification_event_id}` with the delivery attempt history.
- Protect the API with JWT bearer tokens (RS256) and take the client identity only from the token.
- Add a development-only tool that issues signed test tokens.
- Create the full persistence schema (events, subscriptions, notifications, delivery attempts) so later changes only add behavior.

## Impact

- Affected specs: `self-service-api` (new), `platform-security` (new)
- Affected code: `notifications` capability (domain, application, REST and persistence adapters), `shared.security`, Flyway migrations, seed loader, `tools/dev-token`
- Out of scope: event ingestion, delivery, replay, subscription management (later changes)
