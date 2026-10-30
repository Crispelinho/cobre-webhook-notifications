## ADDED Requirements

### Requirement: Audit log

Replays and subscription creation, update and deactivation SHALL each write one structured audit entry with the acting client, the action, the resource ID, the outcome and the trace ID.

#### Scenario: Replay is audited

- **WHEN** CLIENT002 replays EVT003
- **THEN** one audit entry records CLIENT002, `notification_event.replay`, EVT003 and the outcome

#### Scenario: Rejected operation is audited

- **WHEN** CLIENT001 tries to replay a `completed` notification event
- **THEN** an audit entry records the attempt with outcome `rejected`

### Requirement: Per-client rate limiting

If rate limiting is enabled, each client SHALL be limited to 100 requests per minute on the self-service API; excess requests SHALL receive `429` as `application/problem+json` with a `Retry-After` header (OWASP API4).

#### Scenario: Client exceeds its limit

- **GIVEN** rate limiting is enabled
- **WHEN** CLIENT001 sends its 101st request within one minute
- **THEN** the response is `429` with `Retry-After`
- **AND** CLIENT002 is not affected
