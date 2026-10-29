## MODIFIED Requirements

### Requirement: Webhook secrets

Each subscription SHALL get a random 256-bit secret, returned only when the subscription is created and stored encrypted with AES-GCM under a key from configuration. The ciphertext SHALL be bound to its subscription by using the `subscription_id` as associated data, so it decrypts only with the configured key and that `subscription_id`. Secrets SHALL never be logged or returned by read endpoints.

#### Scenario: Secret stored encrypted

- **WHEN** a subscription is created
- **THEN** the stored `secret_ciphertext` differs from the returned secret and decrypts back to it with the configured key and the subscription's `subscription_id`

#### Scenario: Ciphertext copied to another subscription

- **GIVEN** the `secret_ciphertext` of subscription S1
- **WHEN** it is decrypted with the configured key and the `subscription_id` of S2
- **THEN** decryption fails and no secret is returned

#### Scenario: Decryption without the subscription ID

- **WHEN** the `secret_ciphertext` of S1 is decrypted with the configured key but without associated data
- **THEN** decryption fails

### Requirement: Webhook signature

The signer SHALL produce `Cobre-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">` with the subscription secret, so receivers can verify the sender and reject requests older than 5 minutes. The timestamp `t` SHALL be taken at send time on every attempt, and the body SHALL be byte-identical across all attempts of a notification event.

#### Scenario: Known test vector

- **GIVEN** secret `test-secret`, timestamp `1700000000` and body `{"a":1}`
- **WHEN** the signer signs the body
- **THEN** `v1` equals HMAC-SHA256(`test-secret`, `1700000000.{"a":1}`) in lowercase hex

#### Scenario: Retried attempt gets a new timestamp

- **GIVEN** a notification event whose first attempt failed with `503`
- **WHEN** its next attempt is sent 8 minutes later
- **THEN** its `t` is the send time of that attempt, not of the first one
- **AND** its signature verifies within the 5-minute tolerance

#### Scenario: Body is identical across attempts

- **GIVEN** a notification event delivered in several attempts
- **WHEN** the request bodies of those attempts are compared
- **THEN** they are byte-identical
