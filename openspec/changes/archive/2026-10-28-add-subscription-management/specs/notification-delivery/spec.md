## ADDED Requirements

### Requirement: Create a subscription

The system SHALL accept `POST /subscriptions` with `url` and `event_types` (scope `subscriptions:write`), validate the URL with the destination policy, and respond `201` with the subscription and a signing `secret` that is returned only in this response. The owner SHALL be the client in the token.

#### Scenario: Valid subscription

- **GIVEN** `hooks.example.org` is in the allowlist and resolves to a public address
- **WHEN** CLIENT001 posts `https://hooks.example.org/cobre` with `["credit_deposit"]`
- **THEN** the response is `201` with an ID, `active` true and a `secret`
- **AND** the subscription belongs to CLIENT001

#### Scenario: Unsafe destination

- **WHEN** a client posts a URL that the destination policy rejects
- **THEN** the response is `400` as `application/problem+json` and nothing is stored

#### Scenario: Owner cannot be chosen

- **WHEN** CLIENT001 posts a subscription body that includes `client_id` CLIENT002
- **THEN** the response is `400` and no subscription is created

### Requirement: Read subscriptions

The system SHALL list and return only the caller's subscriptions (scope `subscriptions:read`) and SHALL never return the secret after creation. A subscription of another client SHALL be reported as not found.

#### Scenario: List own subscriptions

- **WHEN** CLIENT001 requests `GET /subscriptions`
- **THEN** every item belongs to CLIENT001 and no item contains a secret

#### Scenario: Subscription of another client

- **WHEN** CLIENT001 requests a subscription of CLIENT002 by ID
- **THEN** the response is `404`

### Requirement: Deactivate a subscription

`DELETE /subscriptions/{id}` SHALL mark the caller's subscription inactive and respond `204`. Existing notification events keep their history, and new events SHALL no longer match it.

#### Scenario: Deactivated subscription

- **WHEN** CLIENT001 deletes its subscription
- **THEN** the response is `204` and the subscription is listed with `active` false

### Requirement: Update a subscription

`PATCH /subscriptions/{id}` SHALL accept only `active` and `event_types`. Any other field SHALL be rejected with `400`; changing the URL requires deleting and creating a subscription.

#### Scenario: Pause a subscription

- **WHEN** CLIENT001 patches its subscription with `active` false
- **THEN** the response is `200` with `active` false

#### Scenario: Attempt to change the URL

- **WHEN** CLIENT001 patches its subscription with a new `url`
- **THEN** the response is `400` and the subscription is unchanged
