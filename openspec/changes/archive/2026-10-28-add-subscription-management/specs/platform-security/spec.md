## ADDED Requirements

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
