# Tasks: Add subscription management

## 1. Shared security components

- [x] 1.1 `shared.security.DestinationPolicy` with configurable allowlist and address-range checks, unit tests with a fake resolver
- [x] 1.2 `shared.crypto.SecretCipher` (AES-GCM, key from configuration) and `WebhookSigner` (HMAC-SHA256) with the test vector

## 2. Subscriptions capability

- [x] 2.1 Domain (`Subscription`, event type validation) and use cases: create, list, get, deactivate, patch
- [x] 2.2 Persistence adapter for the existing `subscriptions` table
- [x] 2.3 REST adapter with explicit request DTOs (unknown fields rejected) and the `subscriptions:*` rules

## 3. Acceptance

- [ ] 3.1 Tests for every scenario in both spec files
- [ ] 3.2 `./gradlew build` passes; fresh-context AI review before the pull request

## Constraints for parallel work

- Do not modify `notifications`, `events`, `V1`/`V2` migrations or `NotificationsConfiguration`; `add-event-ingestion-and-delivery` owns them.
- Reserved Flyway version for this change: `V3` (only if a schema change becomes necessary).
- `SecurityConfig` and `application.yaml` are also edited by `add-event-ingestion-and-delivery`; keep edits to added lines (new rules, new properties) so the rebase conflict stays trivial.
