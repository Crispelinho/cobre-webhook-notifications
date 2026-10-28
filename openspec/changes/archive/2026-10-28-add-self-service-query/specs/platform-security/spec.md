## ADDED Requirements

### Requirement: Client authentication

The self-service API SHALL require a JWT bearer token signed with RS256. The system SHALL validate the signature, expiry (`exp`), issuer (`iss`) and audience (`aud`), and SHALL reject tokens without a `client_id` claim.

#### Scenario: Missing token

- **WHEN** a request to `GET /notification_events` has no `Authorization` header
- **THEN** the response is `401`

#### Scenario: Expired token

- **WHEN** a request carries a token whose `exp` is in the past
- **THEN** the response is `401`

#### Scenario: Token signed with an unknown key

- **WHEN** a request carries a token signed with a key other than the configured one
- **THEN** the response is `401`

#### Scenario: Token without client identity

- **WHEN** a request carries a valid token without a `client_id` claim
- **THEN** the response is `401`

### Requirement: Scope-based authorization

Reading notification events SHALL require the `notifications:read` scope.

#### Scenario: Missing read scope

- **WHEN** a client with a valid token but without `notifications:read` requests `GET /notification_events`
- **THEN** the response is `403`

### Requirement: Client isolation

The system SHALL scope every self-service query to the `client_id` claim of the access token, and SHALL ignore any client identity supplied in the path, query or body. A resource owned by another client SHALL be reported as not found (OWASP API1:2023, Broken Object Level Authorization).

#### Scenario: Cross-client access is indistinguishable from a missing resource

- **GIVEN** EVT003 belongs to CLIENT002
- **WHEN** CLIENT001 requests `GET /notification_events/EVT003` and `GET /notification_events/DOES-NOT-EXIST`
- **THEN** both responses are `404` with the same body structure

### Requirement: Development token issuer

The project SHALL provide a development-only tool that issues signed test tokens for a given client and scopes. The tool and its private key SHALL NOT be part of the application artifact, and the private key SHALL NOT be committed.

#### Scenario: Issue a token for local testing

- **WHEN** a developer runs the token tool for CLIENT001 with scope `notifications:read`
- **THEN** it prints a token that the running application accepts for CLIENT001
