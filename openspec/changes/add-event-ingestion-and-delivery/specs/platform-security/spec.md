## ADDED Requirements

### Requirement: Producer authorization

`POST /events` SHALL require the `events:write` scope, which is only granted to internal producers. Client tokens SHALL NOT be able to create events, so a client can never create notifications for another client.

#### Scenario: Client token on the ingestion endpoint

- **WHEN** a request with a client token (scope `notifications:read`) posts an event
- **THEN** the response is `403` and nothing is stored

#### Scenario: Producer token

- **WHEN** a request with scope `events:write` posts a valid event
- **THEN** the response is `202`
