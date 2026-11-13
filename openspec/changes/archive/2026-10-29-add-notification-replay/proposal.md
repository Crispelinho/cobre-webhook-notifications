# Change: Add notification replay

## Why

The challenge asks clients to re-send a notification when delivery has definitely failed, without contacting support.

## What Changes

- `POST /notification_events/{notification_event_id}/replay` (scope `notifications:replay`), only for `failed` notification events owned by the caller.
- A replay keeps the notification event ID and its history and starts a new round of up to 5 attempts, through the same outbox and dispatch path as new events.
- Optimistic locking guarantees that two concurrent replays cannot both start a round.

## Decisions to confirm

- The response is `202` with `notification_event_id`, `round` and `delivery_status` (`pending`).
- A replay clears `delivery_date` (the current round has no final outcome yet) and keeps `delivered_at`.

## Impact

- Affected specs: `self-service-api`
- Affected code: `notifications` replay use case, REST adapter, persistence (conditional update), security rule for the new scope
- Depends on: `add-event-ingestion-and-delivery` (outbox and dispatch)
