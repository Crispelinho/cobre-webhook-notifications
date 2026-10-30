## ADDED Requirements

### Requirement: Retry-After handling

When a delivery attempt receives `429` or `503` with a valid `Retry-After` header, as delta-seconds or as a future HTTP-date, the system SHALL schedule the next attempt of the round after exactly that delay, in place of the backoff step, capped at a configured maximum. The cap SHALL default to 300 seconds and SHALL be configurable per environment. A value above the cap SHALL schedule the next attempt at the cap, never earlier.

#### Scenario: Valid delay is honoured

- **GIVEN** the cap is 300 seconds
- **WHEN** the receiver answers `429` with `Retry-After: 120`
- **THEN** the attempt is recorded as retryable
- **AND** the next attempt is scheduled 120 seconds later, in place of the backoff step

#### Scenario: Delay shorter than the backoff step

- **GIVEN** the next backoff step is 60 seconds
- **WHEN** the receiver answers `429` with `Retry-After: 5`
- **THEN** the next attempt is scheduled 5 seconds later

#### Scenario: HTTP-date is honoured

- **WHEN** the receiver answers `503` with a `Retry-After` HTTP-date 60 seconds in the future
- **THEN** the next attempt is not sent before that date

#### Scenario: Delay above the cap

- **GIVEN** the cap is 300 seconds
- **WHEN** the receiver answers `503` with `Retry-After: 3600`
- **THEN** the next attempt is scheduled 300 seconds later, not earlier and not later

#### Scenario: Cap configured per environment

- **GIVEN** an environment that sets the cap to 60 seconds
- **WHEN** the receiver answers `429` with `Retry-After: 120`
- **THEN** the next attempt is scheduled 60 seconds later

### Requirement: Retry-After fallback

A missing, invalid, past or zero `Retry-After` on `429` or `503` SHALL use the configured backoff step. `Retry-After` on any other status SHALL be ignored. `Retry-After` SHALL NOT change the limit of 5 attempts per round.

#### Scenario: Past HTTP-date

- **WHEN** the receiver answers `429` with a `Retry-After` HTTP-date in the past
- **THEN** the next attempt uses the configured backoff step

#### Scenario: Zero delay

- **WHEN** the receiver answers `503` with `Retry-After: 0`
- **THEN** the next attempt uses the configured backoff step, not an immediate retry

#### Scenario: Invalid value

- **WHEN** the receiver answers `503` with `Retry-After: soon`
- **THEN** the next attempt uses the configured backoff step

#### Scenario: Missing header

- **WHEN** the receiver answers `429` without `Retry-After`
- **THEN** the next attempt uses the configured backoff step

#### Scenario: Header on another status is ignored

- **WHEN** the receiver answers `500` with `Retry-After: 120`
- **THEN** the next attempt uses the configured backoff step

#### Scenario: Attempt limit is unchanged

- **GIVEN** a receiver that always answers `429` with `Retry-After: 10`
- **WHEN** the notification event is delivered
- **THEN** it becomes `failed` after exactly 5 attempts in the round
