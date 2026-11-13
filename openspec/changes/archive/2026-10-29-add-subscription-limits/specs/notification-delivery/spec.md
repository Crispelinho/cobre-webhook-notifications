## ADDED Requirements

### Requirement: Active subscription limit

A client SHALL have at most 50 active subscriptions. Creating a subscription, or reactivating an inactive one with `PATCH` `active` true, when the client already has 50 active subscriptions SHALL be rejected with `409 Conflict` as `application/problem+json` with `code` equal to `SUBSCRIPTION_LIMIT_REACHED`, and nothing SHALL be stored or changed. The check SHALL be atomic, so concurrent requests cannot leave a client with more than 50 active subscriptions. Inactive subscriptions SHALL NOT count.

#### Scenario: 51st subscription on creation

- **GIVEN** CLIENT001 has 50 active subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `409` as `application/problem+json` with `code` `SUBSCRIPTION_LIMIT_REACHED`
- **AND** no subscription is created and no secret is returned

#### Scenario: Reactivation at the limit

- **GIVEN** CLIENT001 has 50 active subscriptions and one inactive subscription
- **WHEN** CLIENT001 patches the inactive subscription with `active` true
- **THEN** the response is `409` with `code` `SUBSCRIPTION_LIMIT_REACHED`
- **AND** the subscription stays inactive

#### Scenario: Concurrent requests at the limit

- **GIVEN** CLIENT001 has 49 active subscriptions
- **WHEN** CLIENT001 sends several creations and reactivations concurrently
- **THEN** exactly one of them succeeds and the others receive `409`
- **AND** CLIENT001 has exactly 50 active subscriptions

#### Scenario: Inactive subscriptions do not count

- **GIVEN** CLIENT001 has 49 active subscriptions and 10 inactive subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `201`

#### Scenario: Limit is per client

- **GIVEN** CLIENT002 has 50 active subscriptions
- **WHEN** CLIENT001 posts a valid subscription
- **THEN** the response is `201`

### Requirement: Subscription event types

`event_types` SHALL contain at least 1 and at most 50 entries, each matching `^[a-z0-9_]{1,100}$`, on creation and on `PATCH`. A request that breaks these rules SHALL be rejected with `400` as `application/problem+json` and nothing SHALL be stored or changed.

#### Scenario: Empty event types

- **WHEN** CLIENT001 posts a subscription with `event_types` `[]`
- **THEN** the response is `400` and no subscription is created

#### Scenario: Too many event types

- **WHEN** CLIENT001 posts a subscription with 51 distinct valid event types
- **THEN** the response is `400` and no subscription is created

#### Scenario: Invalid event type

- **WHEN** CLIENT001 patches its subscription with `event_types` `["Credit-Deposit"]`
- **THEN** the response is `400` and the subscription is unchanged

## MODIFIED Requirements

### Requirement: Read subscriptions

The system SHALL list and return only the caller's subscriptions (scope `subscriptions:read`) and SHALL never return the secret after creation. A subscription of another client SHALL be reported as not found. The list SHALL contain at most 100 subscriptions, the most recently created first, active and inactive alike.

#### Scenario: List own subscriptions

- **WHEN** CLIENT001 requests `GET /subscriptions`
- **THEN** every item belongs to CLIENT001 and no item contains a secret

#### Scenario: Subscription of another client

- **WHEN** CLIENT001 requests a subscription of CLIENT002 by ID
- **THEN** the response is `404`

#### Scenario: List limit

- **GIVEN** CLIENT001 has 120 subscriptions, active and inactive
- **WHEN** CLIENT001 requests `GET /subscriptions`
- **THEN** the response contains the 100 most recently created subscriptions, newest first

### Requirement: Deactivate a subscription

`DELETE /subscriptions/{id}` SHALL mark the caller's subscription inactive and respond `204`. Existing notification events keep their history, and new events SHALL no longer match it. Deleting a subscription that is already inactive SHALL also respond `204` and change nothing.

#### Scenario: Deactivated subscription

- **WHEN** CLIENT001 deletes its subscription
- **THEN** the response is `204` and the subscription is listed with `active` false

#### Scenario: Subscription already inactive

- **GIVEN** a subscription of CLIENT001 that is inactive
- **WHEN** CLIENT001 deletes it
- **THEN** the response is `204` and the subscription is still listed with `active` false

### Requirement: Update a subscription

`PATCH /subscriptions/{id}` SHALL accept only `active` and `event_types`. Any other field SHALL be rejected with `400`; changing the URL requires deleting and creating a subscription. A body with neither `active` nor `event_types` SHALL be rejected with `400`.

#### Scenario: Pause a subscription

- **WHEN** CLIENT001 patches its subscription with `active` false
- **THEN** the response is `200` with `active` false

#### Scenario: Attempt to change the URL

- **WHEN** CLIENT001 patches its subscription with a new `url`
- **THEN** the response is `400` and the subscription is unchanged

#### Scenario: Empty patch

- **WHEN** CLIENT001 patches its subscription with `{}`
- **THEN** the response is `400` as `application/problem+json` and the subscription is unchanged
