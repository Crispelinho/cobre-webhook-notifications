## ADDED Requirements

### Requirement: Replay delivers to the stored snapshot

A replayed notification event SHALL be delivered to the destination URL stored with the notification event (its snapshot), not to the current URL of the subscription. The request SHALL be signed with the secret of the subscription identified by its `subscription_id`. The destination policy SHALL run before every attempt of the replayed round, as it does for any other attempt. Inactivity of the subscription SHALL NOT block the replay or the attempt.

#### Scenario: Replay goes to the snapshot after the subscription URL changes

- **GIVEN** a failed notification event whose subscription is now inactive and whose URL has changed
- **WHEN** the event is replayed and its new round is delivered
- **THEN** the request goes to the URL stored with the notification event
- **AND** no request goes to the changed subscription URL

#### Scenario: Replay is signed with the subscription secret

- **GIVEN** a replayed notification event of a subscription with a secret
- **WHEN** the new round is delivered
- **THEN** `Cobre-Signature` is the HMAC of the body with that subscription's secret

#### Scenario: Destination is validated again at send time

- **GIVEN** a replayed notification event whose host now resolves to a non-public address
- **WHEN** the new round is attempted
- **THEN** no request is sent
- **AND** the attempt is recorded as `invalid_destination`
