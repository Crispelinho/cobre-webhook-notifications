## ADDED Requirements

### Requirement: DNS failure at send time

When the destination host of a delivery attempt cannot be resolved at send time, the system SHALL record the attempt as retryable with `error_type` `dns_error` and SHALL NOT send a request. This SHALL apply to every resolution failure, whatever its cause. A host that resolves only to non-public addresses SHALL still fail the notification event immediately with `error_type` `invalid_destination`. The limit of 5 attempts per round SHALL still apply.

#### Scenario: Temporary resolution failure

- **GIVEN** a `pending` notification event whose destination host cannot be resolved on the first attempt
- **AND** the host resolves to a public, allowed address on the second attempt
- **WHEN** the notification event is delivered
- **THEN** the first attempt is recorded with `error_type` `dns_error` and no `http_status`
- **AND** the second attempt sends the request and the notification event becomes `completed`

#### Scenario: Host never resolves

- **GIVEN** a destination host that never resolves
- **WHEN** the notification event is delivered
- **THEN** it becomes `failed` after exactly 5 attempts in the round, each with `error_type` `dns_error`

#### Scenario: Host resolves to a private address

- **GIVEN** a destination host that resolves to `10.0.0.5`
- **WHEN** the notification event is delivered
- **THEN** no request is sent
- **AND** the notification event becomes `failed` after one attempt with `error_type` `invalid_destination`
