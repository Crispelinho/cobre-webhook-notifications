# Tasks: Add event ingestion and delivery

## 1. Ingestion

- [x] 1.1 `V2__create_outbox.sql`
- [x] 1.2 `events` capability: `Event`, `SubscriptionMatcher`, `IngestEventUseCase` with unit tests
- [x] 1.3 `POST /events` with validation, `409` on duplicates and the `events:write` rule
- [x] 1.4 Acceptance tests for ingestion, duplicate, validation and producer authorization scenarios

## 2. Outbox and Kafka

- [x] 2.1 Outbox relay with `SKIP LOCKED` and `DispatchPublisher` Kafka adapter
- [x] 2.2 Integration test: event accepted while Kafka is down is delivered later

## 3. Delivery and retries

- [x] 3.1 `DeliveryOutcome` classification and state transitions in the domain, with unit tests
- [x] 3.2 HTTPS `WebhookSender` adapter (timeouts, no redirects) and attempt recording
- [x] 3.3 Dispatch consumer with `@RetryableTopic`, per-profile backoff and dead-letter handler
- [x] 3.4 Idempotent consumption (completed or older round is ignored)
- [x] 3.5 Integration tests with WireMock over HTTPS for every delivery and retry scenario

## 4. After `add-subscription-management` is merged

- [x] 4.1 Send-time destination validation with the shared destination policy
- [x] 4.2 HMAC `Cobre-Signature` header with the subscription secret
- [x] 4.3 Tests for the private-address and signature scenarios

## 5. Verification

- [x] 5.1 `./gradlew build` passes
- [ ] 5.2 Dual blind AI review (delivery and security change) before the pull request

## Archive notes (2026-10-29)

- 5.1: the build passed before merge according to PR #6 (363 tests).
- 5.2: not done. PR #6 and `docs/ai-usage/04` record the dual blind AI review as pending, and there is no record that it ran later.
