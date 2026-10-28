## ADDED Requirements

### Requirement: Event ingestion

The system SHALL accept `POST /events` with `event_id`, `client_id`, `event_type` and `content`, store the event, and create one `pending` notification event for each active subscription of the same `client_id` that includes the `event_type`. It SHALL respond `202` with the `event_id` and the created notification event IDs.

#### Scenario: Event matches a subscription

- **GIVEN** CLIENT001 has an active subscription to `credit_deposit`
- **WHEN** a producer posts a `credit_deposit` event for CLIENT001
- **THEN** the response is `202` with one notification event ID
- **AND** that notification event is `pending` with the subscription URL as destination

#### Scenario: Event matches no subscription

- **WHEN** a producer posts an event whose type no active subscription of that client includes
- **THEN** the response is `202` with no notification event IDs
- **AND** nothing is delivered

#### Scenario: Subscription of another client is never matched

- **GIVEN** CLIENT002 has an active subscription to `credit_deposit` and CLIENT001 has none
- **WHEN** a producer posts a `credit_deposit` event for CLIENT001
- **THEN** no notification event is created for CLIENT002

#### Scenario: Inactive subscription is not matched

- **GIVEN** a subscription that includes the event type but is inactive
- **WHEN** a matching event is posted
- **THEN** no notification event is created for it

### Requirement: Duplicate events

The system SHALL reject an event whose `event_id` already exists with `409 Conflict`, without creating notification events. Producers treat `409` as "already accepted".

#### Scenario: Producer retries an accepted event

- **GIVEN** event E1 was accepted
- **WHEN** the producer posts E1 again
- **THEN** the response is `409` as `application/problem+json`
- **AND** the number of notification events is unchanged

### Requirement: Event validation

The system SHALL reject with `400` an event that lacks a field, has an `event_type` not matching `^[a-z0-9_]{1,100}$`, an `event_id` or `client_id` longer than 64 characters, or `content` longer than 10,000 characters.

#### Scenario: Invalid event

- **WHEN** a producer posts an event with an empty `client_id`
- **THEN** the response is `400` as `application/problem+json` and nothing is stored

### Requirement: Transactional outbox

The event, its notification events and their outbox rows SHALL be stored in one transaction. A relay SHALL publish pending outbox rows to Kafka and mark them published, so an event accepted while Kafka is unavailable is delivered once Kafka is available.

#### Scenario: Kafka unavailable at ingestion

- **GIVEN** Kafka is unavailable
- **WHEN** a matching event is posted
- **THEN** the response is `202` and the outbox row stays unpublished
- **AND** once Kafka is available the notification event is delivered

### Requirement: Webhook delivery

The system SHALL send each notification event as an HTTPS `POST` with a JSON body (`notification_event_id`, `event_id`, `event_type`, `content`, `created_at`) and a `Cobre-Notification-Id` header, with a 2 s connect timeout, 5 s read timeout and 10 s total timeout, and without following redirects.

#### Scenario: Receiver accepts the webhook

- **GIVEN** a pending notification event whose receiver answers `200`
- **WHEN** it is delivered
- **THEN** it becomes `completed` with `delivery_date` and `delivered_at` set
- **AND** one attempt is recorded with `http_status` 200

#### Scenario: Receiver answers with a redirect

- **WHEN** the receiver answers `302`
- **THEN** the redirect is not followed and the notification event becomes `failed` after one attempt

### Requirement: Retry policy

The system SHALL retry timeouts, connection errors, `5xx`, `408` and `429` (honoring `Retry-After` up to the next backoff step) with exponential backoff and jitter, up to 5 attempts per round. Any other response SHALL fail the notification event immediately.

#### Scenario: Transient errors then success

- **GIVEN** a receiver that answers `503` twice and then `200`
- **WHEN** the notification event is delivered
- **THEN** it becomes `completed` after 3 attempts

#### Scenario: Permanent error

- **WHEN** the receiver answers `400`
- **THEN** the notification event becomes `failed` after exactly 1 attempt

#### Scenario: Retries exhausted

- **GIVEN** a receiver that always answers `500`
- **WHEN** the notification event is delivered
- **THEN** it becomes `failed` after 5 attempts with `delivery_date` set
- **AND** its message reaches the dead-letter topic

#### Scenario: Slow receiver

- **WHEN** the receiver does not answer within the read timeout
- **THEN** the attempt is recorded with `error_type` `timeout` and retried

### Requirement: Idempotent delivery

A dispatch message for a notification event that is already `completed`, or that belongs to an earlier round, SHALL be ignored without sending a request. Recording the same attempt twice SHALL have no effect.

#### Scenario: Redelivered message

- **GIVEN** a notification event that is already `completed`
- **WHEN** its dispatch message is consumed again
- **THEN** no request is sent and no attempt is recorded

### Requirement: Send-time destination safety

Before each request the system SHALL validate the destination with the destination policy of `platform-security` and connect only to the validated address. A destination that fails validation SHALL fail the notification event permanently without sending a request.

#### Scenario: Destination resolves to a private address

- **GIVEN** a subscription host that now resolves to `10.0.0.5`
- **WHEN** a notification event is delivered
- **THEN** no request is sent and the notification event becomes `failed`

### Requirement: Signed webhooks

Each request SHALL carry a `Cobre-Signature` header computed with the subscription secret, as defined in `platform-security`. Subscriptions without a secret (seed data) SHALL be delivered unsigned.

#### Scenario: Receiver verifies the signature

- **GIVEN** a subscription with a known secret
- **WHEN** a notification event is delivered
- **THEN** the `Cobre-Signature` header verifies against the request body with that secret
