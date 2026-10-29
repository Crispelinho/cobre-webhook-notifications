# Change: Document replay delivery to the snapshot

## Why

Replay (`add-notification-replay`) sends a failed notification event again through the same outbox and dispatch path as a new event. Three properties of that path were written as unchecked tasks (2.4 and 3.4 of `add-event-ingestion-and-delivery`) and never reached the specs. The code already implements them, and `ReplayDeliveryIntegrationTests` covers the main case, but the specs do not state them, so a future change could break them without a failing requirement.

- The replay goes to the destination snapshot stored with the notification event (D13), not to the subscription's current URL.
- The replay signs with the secret of the subscription identified by `subscription_id`, even when the subscription is inactive (D57, D67).
- Every attempt, a replay included, runs the shared destination policy at send time (D19, D26).

## What Changes

- Adds a requirement to `notification-delivery` that states the three properties above as observable behaviour.
- No production code, migration or API change. The current behaviour is the reference.

## Decisions referenced

- D13: every notification event stores a snapshot of the destination URL.
- D19, D26: send-time destination validation, no redirects.
- D57, D67: replay of an inactive subscription is allowed; inactivity alone does not block an attempt or a replay.

## Impact

- Affected specs: `notification-delivery` (ADDED requirement).
- Affected code: none.
- Depends on: `add-notification-replay`, `add-event-ingestion-and-delivery`.
