## ADDED Requirements

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

### Requirement: Replay destination and secret

A replayed round SHALL send to the `destination_url` snapshot stored on the notification event, not to the URL of any other subscription. Before each request of the round, the destination SHALL be validated again with the destination policy, and a rejected destination SHALL fail the attempt permanently without sending a request. The signing secret SHALL be loaded by the notification event's `subscription_id`.

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
