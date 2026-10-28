## ADDED Requirements

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
