# notification-delivery Specification

## Purpose
How clients register the webhook destinations that receive their notification events, and how the platform delivers to them.

## Requirements

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

#### Scenario: Empty patch

- **WHEN** CLIENT001 patches its subscription with `{}`
- **THEN** the response is `400` and the subscription is unchanged

#### Scenario: Reactivation at the limit

- **GIVEN** CLIENT001 has 50 active subscriptions and one inactive subscription
- **WHEN** CLIENT001 patches the inactive subscription with `active` true
- **THEN** the response is `400`, the subscription stays inactive, and the rejection is audited

#### Scenario: Patching an active subscription at the limit

- **GIVEN** CLIENT001 has 50 active subscriptions
- **WHEN** CLIENT001 patches one of them with `active` true
- **THEN** the response is `200`, because the count does not change

### Requirement: Subscription limits

A client SHALL have at most 50 active subscriptions. Creating a subscription and reactivating one both count against this limit. `event_types` SHALL hold between 1 and 50 entries, each matching `^[a-z0-9_]{1,100}$`. A webhook URL SHALL use port 443, explicit or implicit.

#### Scenario: Fifty-first active subscription

- **GIVEN** CLIENT001 has 50 active subscriptions
- **WHEN** CLIENT001 creates another subscription
- **THEN** the response is `400` with a message that names the limit

#### Scenario: Non-443 port

- **WHEN** CLIENT001 creates a subscription with `https://hooks.example.org:8443/cobre`
- **THEN** the response is `400` and no subscription is stored
