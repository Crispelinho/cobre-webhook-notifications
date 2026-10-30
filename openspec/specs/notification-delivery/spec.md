# notification-delivery Specification

## Purpose
How clients register the webhook destinations that receive their notification events, and how the platform delivers to them.

## Requirements

### Requirement: Create a subscription

The system SHALL accept `POST /subscriptions` with `url` and `event_types` (scope `subscriptions:write`), validate the URL with the destination policy, and respond `201` with the subscription and a signing `secret` that is returned only in this response. The owner SHALL be the client in the token.

#### Scenario: Valid subscription

- **GIVEN** `hooks.example.org` is in the allowlist and resolves to a public address
- **WHEN** CLIENT001 posts `https://hooks.example.org/cobre` with `["credit_deposit"]`
- **THEN** the response is `201` with an ID, `active` true and a `secret`
- **AND** the subscription belongs to CLIENT001

#### Scenario: Unsafe destination

- **WHEN** a client posts a URL that the destination policy rejects
- **THEN** the response is `400` as `application/problem+json` and nothing is stored

#### Scenario: Owner cannot be chosen

- **WHEN** CLIENT001 posts a subscription body that includes `client_id` CLIENT002
- **THEN** the response is `400` and no subscription is created

### Requirement: Read subscriptions

The system SHALL list and return only the caller's subscriptions (scope `subscriptions:read`) and SHALL never return the secret after creation. A subscription of another client SHALL be reported as not found. The list SHALL contain at most 100 subscriptions, the most recently created first, active and inactive alike.

#### Scenario: List own subscriptions

- **WHEN** CLIENT001 requests `GET /subscriptions`
- **THEN** every item belongs to CLIENT001 and no item contains a secret

#### Scenario: Subscription of another client

- **WHEN** CLIENT001 requests a subscription of CLIENT002 by ID
- **THEN** the response is `404`

#### Scenario: List limit

- **GIVEN** CLIENT001 has 120 subscriptions, active and inactive
- **WHEN** CLIENT001 requests `GET /subscriptions`
- **THEN** the response contains the 100 most recently created subscriptions, newest first

### Requirement: Deactivate a subscription

`DELETE /subscriptions/{id}` SHALL mark the caller's subscription inactive and respond `204`. Existing notification events keep their history, and new events SHALL no longer match it. Deleting a subscription that is already inactive SHALL also respond `204` and change nothing.

#### Scenario: Deactivated subscription

- **WHEN** CLIENT001 deletes its subscription
- **THEN** the response is `204` and the subscription is listed with `active` false

#### Scenario: Subscription already inactive

- **GIVEN** a subscription of CLIENT001 that is inactive
- **WHEN** CLIENT001 deletes it
- **THEN** the response is `204` and the subscription is still listed with `active` false

### Requirement: Update a subscription

`PATCH /subscriptions/{id}` SHALL accept only `active` and `event_types`. Any other field SHALL be rejected with `400`; changing the URL requires deleting and creating a subscription. A body with neither `active` nor `event_types` SHALL be rejected with `400`.

#### Scenario: Pause a subscription

- **WHEN** CLIENT001 patches its subscription with `active` false
- **THEN** the response is `200` with `active` false

#### Scenario: Attempt to change the URL

- **WHEN** CLIENT001 patches its subscription with a new `url`
- **THEN** the response is `400` and the subscription is unchanged

#### Scenario: Empty patch

- **WHEN** CLIENT001 patches its subscription with `{}`
- **THEN** the response is `400` as `application/problem+json` and the subscription is unchanged

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

The system SHALL retry timeouts, connection errors, `5xx`, `408` and `429` (`Retry-After` on `429` and `503` as defined in "Retry-After handling") with exponential backoff and jitter, up to 5 attempts per round. Any other response SHALL fail the notification event immediately.

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

- **WHEN** the receiver does not answer within the 10-second limit per attempt (D50)
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

Each request SHALL carry a `Cobre-Signature` header computed with the subscription secret, as defined in `platform-security`. A subscription without a secret SHALL NOT be delivered unsigned: the attempt SHALL fail permanently with `error_type` `missing_secret` and no request SHALL be sent.

#### Scenario: Receiver verifies the signature

- **GIVEN** a subscription with a known secret
- **WHEN** a notification event is delivered
- **THEN** the `Cobre-Signature` header verifies against the request body with that secret

#### Scenario: Subscription without a secret

- **GIVEN** a subscription that has no secret
- **WHEN** a notification event is delivered
- **THEN** no request is sent
- **AND** the notification event becomes `failed` after one attempt with `error_type` `missing_secret`

### Requirement: Deactivation keeps existing deliveries

Deactivating a subscription SHALL only stop matching new events. Notification events that already exist SHALL keep their delivery and retries; the inactive state alone SHALL NOT block an attempt or a replay.

#### Scenario: Subscription deactivated during retries

- **GIVEN** a notification event in `retrying` whose subscription is then deactivated
- **WHEN** its next attempt is due
- **THEN** the request is sent as usual

### Requirement: Active subscription limit

A client SHALL have at most 50 active subscriptions. Creating a subscription, or reactivating an inactive one with `PATCH` `active` true, when the client already has 50 active subscriptions SHALL be rejected with `409 Conflict` as `application/problem+json` with `code` equal to `SUBSCRIPTION_LIMIT_REACHED`, and nothing SHALL be stored or changed. The check SHALL be atomic, so concurrent requests cannot leave a client with more than 50 active subscriptions. Inactive subscriptions SHALL NOT count.

#### Scenario: 51st subscription on creation

- **GIVEN** CLIENT001 has 50 active subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `409` as `application/problem+json` with `code` `SUBSCRIPTION_LIMIT_REACHED`
- **AND** no subscription is created and no secret is returned

#### Scenario: Reactivation at the limit

- **GIVEN** CLIENT001 has 50 active subscriptions and one inactive subscription
- **WHEN** CLIENT001 patches the inactive subscription with `active` true
- **THEN** the response is `409` with `code` `SUBSCRIPTION_LIMIT_REACHED`
- **AND** the subscription stays inactive

#### Scenario: Concurrent requests at the limit

- **GIVEN** CLIENT001 has 49 active subscriptions
- **WHEN** CLIENT001 sends several creations and reactivations concurrently
- **THEN** exactly one of them succeeds and the others receive `409`
- **AND** CLIENT001 has exactly 50 active subscriptions

#### Scenario: Inactive subscriptions do not count

- **GIVEN** CLIENT001 has 49 active subscriptions and 10 inactive subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `201`

#### Scenario: Limit is per client

- **GIVEN** CLIENT002 has 50 active subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `201`

### Requirement: Subscription event types

`event_types` SHALL contain at least 1 and at most 50 entries, each matching `^[a-z0-9_]{1,100}$`, on creation and on `PATCH`. A request that breaks these rules SHALL be rejected with `400` as `application/problem+json` and nothing SHALL be stored or changed.

#### Scenario: Empty event types

- **WHEN** CLIENT001 posts a subscription with `event_types` `[]`
- **THEN** the response is `400` and no subscription is created

#### Scenario: Too many event types

- **WHEN** CLIENT001 posts a subscription with 51 distinct valid event types
- **THEN** the response is `400` and no subscription is created

#### Scenario: Invalid event type

- **WHEN** CLIENT001 patches its subscription with `event_types` `["Credit-Deposit"]`
- **THEN** the response is `400` and the subscription is unchanged

### Requirement: Delivery claim

Before sending an attempt, a worker SHALL atomically claim the notification event for its current round. A successful claim SHALL set a lease with an expiry and increase a generation token, which the worker keeps for that attempt. A notification event with an unexpired lease SHALL NOT be claimed by another worker, and a worker that cannot claim SHALL NOT send a request.

#### Scenario: Two workers receive the same message

- **GIVEN** a `pending` notification event
- **WHEN** two workers consume its dispatch message at the same time
- **THEN** exactly one worker claims it and sends one request
- **AND** the other worker sends nothing and records nothing

### Requirement: Fenced state writes

Every write that records the result of an attempt (the attempt row, `delivery_status`, `delivery_date`, `delivered_at` and the scheduling of the next attempt) SHALL be conditional on the generation token of the claim. A write with a token that is no longer current SHALL have no effect, so a stale worker cannot overwrite the result of another worker.

#### Scenario: Stale worker writes late

- **GIVEN** worker A claimed a notification event and its lease expired before it recorded a result
- **AND** worker B claimed it again and recorded `completed`
- **WHEN** worker A tries to record a failed attempt
- **THEN** the write has no effect
- **AND** the notification event stays `completed` with the attempt recorded by worker B

### Requirement: Recovery of interrupted deliveries

The system SHALL periodically find notification events whose lease expired without a recorded outcome and dispatch them again in the same round. The interrupted attempt SHALL be kept in the history with an unknown outcome (`error_type` `interrupted`) and SHALL NOT be recorded as a failure of the receiver. Recovery SHALL NOT delete or modify previously recorded attempts.

#### Scenario: Worker stops during a send

- **GIVEN** a worker claimed a notification event and stopped before recording a result
- **WHEN** the lease expires and recovery runs
- **THEN** the notification event is dispatched again with the same `Cobre-Notification-Id`
- **AND** its history keeps the interrupted attempt with `error_type` `interrupted` and no `http_status`

#### Scenario: Unexpired lease is not recovered

- **GIVEN** a worker holds an unexpired lease on a notification event
- **WHEN** recovery runs
- **THEN** the notification event is not dispatched again

#### Scenario: History is kept

- **GIVEN** a notification event with two recorded attempts in round 1
- **WHEN** its third attempt is interrupted and recovered
- **THEN** the two earlier attempts are unchanged

### Requirement: Recovery timing and attempt count

The lease SHALL default to 60 seconds and the recovery sweep SHALL run every 30 seconds by default; both SHALL be configurable. The lease SHALL NOT be exposed as a public `delivery_status` value. An interrupted attempt SHALL count toward the 5 attempts of the round.

#### Scenario: Lease is not visible in the API

- **GIVEN** a worker holds a lease on a `pending` notification event
- **WHEN** the client reads the notification event
- **THEN** its `delivery_status` is `pending`

#### Scenario: Interrupted fifth attempt

- **GIVEN** a notification event with four recorded attempts in its round
- **WHEN** its fifth attempt is interrupted and the lease expires
- **THEN** the notification event becomes `failed` without sending another request
- **AND** it can be replayed

### Requirement: Replay destination and secret

A replayed round SHALL send to the `destination_url` snapshot stored on the notification event, not to the URL of any other subscription. Before each request of the round, the destination SHALL be validated again with the destination policy, and a rejected destination SHALL fail the attempt permanently without sending a request. The signing secret SHALL be loaded by the notification event's `subscription_id`. A replay SHALL be allowed when that subscription is now inactive.

#### Scenario: Client registered a new URL after the first round

- **GIVEN** a `failed` notification event whose snapshot is `https://hooks.example.org/a`
- **AND** its client later replaced that subscription with one for `https://hooks.example.org/b`
- **WHEN** the client replays the notification event
- **THEN** the request is sent to `https://hooks.example.org/a`

#### Scenario: Destination no longer allowed at replay time

- **GIVEN** a `failed` notification event whose snapshot host is no longer in the allowlist
- **WHEN** the client replays it
- **THEN** no request is sent and the notification event becomes `failed` in the new round

#### Scenario: Secret of the original subscription

- **GIVEN** a `failed` notification event of subscription S1 with a known secret
- **WHEN** the client replays it
- **THEN** the `Cobre-Signature` header verifies with the secret of S1

#### Scenario: Subscription deactivated after the first round

- **GIVEN** a `failed` notification event of subscription S1
- **AND** S1 was deactivated afterwards
- **WHEN** the client replays the notification event
- **THEN** the replay is accepted and the request is sent to the snapshot destination, signed with the secret of S1

### Requirement: Delivery semantics

Delivery SHALL follow at-least-once semantics while the configured attempts and recovery operations run, and the system SHALL NOT claim an end-to-end exactly-once guarantee. A webhook MAY reach the receiver more than once. A timeout SHALL NOT be treated as proof that the receiver did not process the request. The receiver integration documentation SHALL state these rules.

#### Scenario: Timeout after the receiver processed the request

- **GIVEN** a receiver that processes a webhook but answers after the attempt time limit
- **WHEN** the attempt times out
- **THEN** the notification event is retried, not recorded as delivered or as rejected by the receiver

#### Scenario: Documentation states the guarantee

- **WHEN** a receiver reads the integration documentation
- **THEN** it states at-least-once delivery, deduplication on `Cobre-Notification-Id`, and that a timeout does not prove non-processing

### Requirement: Deduplication identifier

Every attempt of a notification event, in every round, SHALL carry the same `Cobre-Notification-Id` and the same body, so receivers can deduplicate on that header.

#### Scenario: Retried request after a timeout

- **GIVEN** an attempt that timed out after the receiver processed it
- **WHEN** the notification event is retried
- **THEN** the retried request carries the same `Cobre-Notification-Id` and the same body
- **AND** a receiver that deduplicates on `Cobre-Notification-Id` processes the notification once

#### Scenario: Replay keeps the notification ID

- **GIVEN** a `failed` notification event that is replayed
- **WHEN** the new round is delivered
- **THEN** its requests carry the same `Cobre-Notification-Id` as the first round
