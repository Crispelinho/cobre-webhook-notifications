# platform-security Specification

## Purpose
Protects the platform when it is consumed over the internet: token-based authentication, scope-based authorization, strict isolation between clients, safe outbound webhook calls and auditing, mapped to the OWASP API Security Top 10 (2023).

## Requirements

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

### Requirement: Destination policy

The destination policy SHALL accept a webhook URL only if it uses `https`, its host is in the configured allowlist, and every address it resolves to is public. Loopback, private, link-local, unique-local, carrier-grade NAT, multicast and unspecified addresses SHALL be rejected (OWASP API7, SSRF).

#### Scenario: Plain HTTP

- **WHEN** the policy checks `http://hooks.example.org/cobre`
- **THEN** it is rejected

#### Scenario: Host outside the allowlist

- **WHEN** the policy checks `https://not-allowed.example.net/`
- **THEN** it is rejected

#### Scenario: Host resolving to an internal address

- **GIVEN** an allowlisted host that resolves to `127.0.0.1`, `10.0.0.5` or `169.254.169.254`
- **WHEN** the policy checks it
- **THEN** it is rejected

#### Scenario: Presentation host

- **GIVEN** `WEBHOOK_ALLOWED_HOSTS` contains the presentation host, which resolves to a public address
- **WHEN** the policy checks an HTTPS URL on that host
- **THEN** it is accepted without recompiling the application

### Requirement: Webhook secrets

Each subscription SHALL get a random 256-bit secret, returned only when the subscription is created and stored encrypted with AES-GCM under a key from configuration. Secrets SHALL never be logged or returned by read endpoints.

#### Scenario: Secret stored encrypted

- **WHEN** a subscription is created
- **THEN** the stored `secret_ciphertext` differs from the returned secret and decrypts back to it with the configured key

### Requirement: Webhook signature

The signer SHALL produce `Cobre-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">` with the subscription secret, so receivers can verify the sender and reject requests older than 5 minutes.

#### Scenario: Known test vector

- **GIVEN** secret `test-secret`, timestamp `1700000000` and body `{"a":1}`
- **WHEN** the signer signs the body
- **THEN** `v1` equals HMAC-SHA256(`test-secret`, `1700000000.{"a":1}`) in lowercase hex
