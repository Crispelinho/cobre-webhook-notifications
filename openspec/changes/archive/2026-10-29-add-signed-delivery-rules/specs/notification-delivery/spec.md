## ADDED Requirements

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
