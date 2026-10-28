# Tasks: Add event ingestion and delivery

## 1. Ingestion

- [x] 1.1 `V2__create_outbox.sql`
- [x] 1.2 `events` capability: `Event`, `SubscriptionMatcher`, `IngestEventUseCase` with unit tests
- [x] 1.3 `POST /events` with validation, `409` on duplicates and the `events:write` rule
- [x] 1.4 Acceptance tests for ingestion, duplicate, validation and producer authorization scenarios

## 2. Outbox and Kafka

- [ ] 2.1 Outbox relay with `SKIP LOCKED` and `DispatchPublisher` Kafka adapter
- [ ] 2.2 Integration test: event accepted while Kafka is down is delivered later

## 3. Delivery and retries

- [ ] 3.1 `DeliveryOutcome` classification and state transitions in the domain, with unit tests
- [ ] 3.2 HTTPS `WebhookSender` adapter (timeouts, no redirects) and attempt recording
- [ ] 3.3 Dispatch consumer with `@RetryableTopic`, per-profile backoff and dead-letter handler
- [ ] 3.4 Idempotent consumption (completed or older round is ignored)
- [ ] 3.5 Integration tests with WireMock over HTTPS for every delivery and retry scenario

## 4. After `add-subscription-management` is merged

- [ ] 4.1 Send-time destination validation with the shared destination policy
- [ ] 4.2 HMAC `Cobre-Signature` header with the subscription secret
- [ ] 4.3 Tests for the private-address and signature scenarios

## 5. Verification

- [ ] 5.1 `./gradlew build` passes
- [ ] 5.2 Dual blind AI review (delivery and security change) before the pull request
