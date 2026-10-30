# self-service-api Specification

## Purpose
Lets clients query their own notification events through a REST API: list them with filters and pagination, read one with its delivery attempts, and replay one whose delivery definitely failed. Clients only ever see their own data.

## Requirements

### Requirement: List notification events

The system SHALL return the notification events that belong to the authenticated client, sorted by `created_at` descending and then by `notification_event_id` descending. Each item SHALL contain `notification_event_id`, `event_id`, `event_type`, `content`, `delivery_status`, `created_at`, `delivery_date` and `delivered_at`.

#### Scenario: Client sees only its own notification events

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT001 requests `GET /notification_events`
- **THEN** the response is `200`
- **AND** it contains exactly EVT010, EVT007, EVT002 and EVT001, in that order

#### Scenario: Client identity in the request is ignored

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT001 requests `GET /notification_events?client_id=CLIENT002`
- **THEN** the response contains only CLIENT001 notification events

### Requirement: Filter notification events

The system SHALL accept the optional filters `created_from` (inclusive) and `created_to` (exclusive) as ISO-8601 UTC timestamps on `created_at`, and `delivery_status` as an exact match on `pending`, `retrying`, `completed` or `failed`.

#### Scenario: Filter by delivery status

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT003 requests `GET /notification_events?delivery_status=failed`
- **THEN** the response contains exactly EVT009 and EVT005

#### Scenario: Filter by creation date range

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT001 requests `GET /notification_events?created_from=2024-03-15T10:00:00Z&created_to=2024-03-15T16:00:00Z`
- **THEN** the response contains exactly EVT007 and EVT002

#### Scenario: No matching notification events

- **WHEN** CLIENT002 requests `GET /notification_events?delivery_status=retrying`
- **THEN** the response is `200` with an empty list and `total_elements` equal to 0

#### Scenario: Invalid filter values

- **WHEN** a client sends an unknown `delivery_status`, a malformed timestamp, or `created_from` later than `created_to`
- **THEN** the response is `400` as `application/problem+json`

### Requirement: Paginate notification events

The system SHALL paginate the list with `page` (0 or greater, default `0`) and `size` (1 to 100, default `20`), and the response SHALL include `page`, `size`, `total_elements` and `total_pages`.

#### Scenario: Default page

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT001 requests `GET /notification_events`
- **THEN** `page` is 0, `size` is 20, `total_elements` is 4 and `total_pages` is 1

#### Scenario: Page size above the limit

- **WHEN** a client requests `GET /notification_events?size=101`
- **THEN** the response is `400` as `application/problem+json`

### Requirement: Get notification event detail

The system SHALL return a single notification event that belongs to the authenticated client, including its delivery attempts ordered by round and attempt number. Each attempt SHALL contain `round`, `attempt_number`, `attempted_at`, `http_status`, `error_type` and `latency_ms`.

#### Scenario: Client reads its own notification event

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT002 requests `GET /notification_events/EVT003`
- **THEN** the response is `200` with `delivery_status` equal to `failed`

#### Scenario: Notification event of another client

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT001 requests `GET /notification_events/EVT003`
- **THEN** the response is `404`
- **AND** the body does not reveal that the notification event exists

#### Scenario: Unknown notification event

- **WHEN** a client requests `GET /notification_events/DOES-NOT-EXIST`
- **THEN** the response is `404`

### Requirement: Sample data seeding

The system SHALL load `notification_events.json` at startup when seeding is enabled, creating one event and one notification event per record with the sample ID, and one active seed subscription per client. Loading SHALL be idempotent.

#### Scenario: Seed loads once

- **GIVEN** seeding is enabled
- **WHEN** the application starts twice
- **THEN** there are exactly 10 notification events and 3 seed subscriptions

### Requirement: Seed date mapping

Because the sample has no creation date, a seeded notification event SHALL use the sample `delivery_date` as `created_at`. `delivered_at` SHALL equal `delivery_date` for `completed` records and SHALL be null for `failed` records.

#### Scenario: Failed seed record has no successful delivery

- **GIVEN** the sample data is loaded
- **WHEN** CLIENT003 requests `GET /notification_events/EVT005`
- **THEN** `created_at` and `delivery_date` are `2024-03-15T13:45:10Z`
- **AND** `delivered_at` is null

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
