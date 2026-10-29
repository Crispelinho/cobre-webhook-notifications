## MODIFIED Requirements

### Requirement: Destination policy

The destination policy SHALL accept a webhook URL only if it uses `https`, its port is 443 (explicit or implied), its host is in the configured allowlist, and every address it resolves to is public. Loopback, private, link-local, unique-local, carrier-grade NAT, multicast and unspecified addresses SHALL be rejected (OWASP API7, SSRF).

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

#### Scenario: Port other than 443

- **GIVEN** `hooks.example.org` is in the allowlist and resolves to a public address
- **WHEN** the policy checks `https://hooks.example.org:8443/cobre`
- **THEN** it is rejected

#### Scenario: Explicit port 443

- **GIVEN** `hooks.example.org` is in the allowlist and resolves to a public address
- **WHEN** the policy checks `https://hooks.example.org:443/cobre`
- **THEN** it is accepted
