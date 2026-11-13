## ADDED Requirements

### Requirement: Replay a failed notification event

The system SHALL accept `POST /notification_events/{notification_event_id}/replay` for a `failed` notification event owned by the caller, require the `notifications:replay` scope, and respond `202` with `notification_event_id`, `round` and `delivery_status`. The notification event SHALL become `pending` in a new round of up to 5 attempts, keeping its ID and previous attempts.

#### Scenario: Replay a failed notification event

- **GIVEN** EVT003 of CLIENT002 is `failed` in round 1
- **WHEN** CLIENT002 replays EVT003
- **THEN** the response is `202` with `round` 2 and `delivery_status` `pending`
- **AND** `delivery_date` is null and the round 1 attempts are still listed

#### Scenario: Replayed notification event is delivered

- **GIVEN** a replayed notification event whose receiver now answers `200`
- **WHEN** the new round is delivered
- **THEN** it becomes `completed` and its attempts include both rounds

### Requirement: Replay preconditions

A replay SHALL be rejected with `409` when the notification event is not `failed`, and with `404` when it does not exist or belongs to another client.

#### Scenario: Notification event is not failed

- **WHEN** CLIENT001 replays EVT001, which is `completed`
- **THEN** the response is `409` as `application/problem+json`

#### Scenario: Notification event of another client

- **WHEN** CLIENT001 replays EVT003
- **THEN** the response is `404` with the same body as for a missing notification event

#### Scenario: Missing replay scope

- **WHEN** a client with only `notifications:read` replays a notification event
- **THEN** the response is `403`

### Requirement: Concurrent replays

Of several simultaneous replays of the same notification event, exactly one SHALL start a new round; the others SHALL receive `409`.

#### Scenario: Two replays at the same time

- **GIVEN** EVT005 of CLIENT003 is `failed`
- **WHEN** CLIENT003 sends two replays of EVT005 concurrently
- **THEN** one response is `202`, the other is `409`, and only one new round exists
