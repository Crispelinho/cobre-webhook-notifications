# 05 - Change 4: subscription management

## Goal

Implement the `add-subscription-management` OpenSpec change: the `/subscriptions` API (create, list, get, deactivate, limited patch) and the shared outbound security components that delivery reuses: `DestinationPolicy` (SSRF), `SecretCipher` (AES-GCM) and `WebhookSigner` (HMAC-SHA256). It was built in a separate session and Git worktree, in parallel with change 2 (D45).

## How AI was used

- Implemented the change in three batches: shared security components, the subscriptions capability, and acceptance tests. The developer reviewed every batch diff before it was committed.
- Kept the parallel-work contract: the `notifications` and `events` packages, the migrations and `NotificationsConfiguration` were not touched, and `SecurityConfig` and `application.yaml` only gained lines, so the rebase against change 2 stays trivial. No schema change was needed, so the reserved `V3` was left unused.
- Respected the architecture test as a design input: domain and application code cannot import `shared`, so subscriptions reach the policy and the cipher through output ports (`DestinationValidator`, `SecretIssuer`) implemented by adapters.
- Computed the HMAC test vector independently (`openssl` and Python) before writing the assertion, instead of copying the implementation's own output.
- Wrote a hand-over note for the change 2 session describing the shared APIs and the test-infrastructure changes it inherits.
- Delegated an adversarial review to a separate AI agent that had not seen the implementation conversation.

## Verification performed

| Check | Result |
|---|---|
| Spec scenarios | Every scenario in both spec deltas has an HTTP acceptance test against PostgreSQL in Testcontainers. DNS is replaced by a fixed map, so allowlisted test hosts resolve to public, loopback, private or metadata addresses on demand. |
| Flaky infrastructure | Two full builds failed because the Grafana LGTM container timed out under load from the parallel session. The second failure was in the new test class, which started a third Spring context and its own containers. Instead of retrying, the class was changed to reuse the cached context of the other acceptance tests. It cleans up its own rows because another test counts seeded subscriptions. |
| Independent review | No critical findings; 9 findings (4 warnings, 5 suggestions); 5 applied, 1 documented for change 2, 3 deferred with reasons. |
| End-to-end run | Not repeated for this change: the fixed Kafka port in `compose.yaml` collides with the parallel session's stack. The MockMvc acceptance tests exercise the full security filter chain and the real database. |
| Final build | 216 tests, 0 failures; `openspec validate --strict` passes. |

## Issues found by the independent reviewer

The implementing session did not catch these; the independent reviewer did:

1. No per-client limit: one client could create unbounded subscriptions, each costing a blocking DNS lookup. Fixed with a cap of 50 active subscriptions per client and a 100-row list limit.
2. The stored ciphertext had no format version and was not bound to its row, so a ciphertext copied to another subscription would still decrypt. Fixed with a version byte and the subscription id as AES-GCM associated data, before change 2 depends on the format.
3. The destination policy accepted any port on an allowlisted host. Fixed: only port 443.
4. The address blocklist had no tests proving that neighbouring public addresses are still accepted. Added boundary tests and the `3fff::/20` documentation range.

## Human validation and corrections

- The developer approved each batch and chose which review findings to apply.
- The developer approved keeping `ClientId` duplicated in `subscriptions` instead of sharing it, because importing it from `notifications` would create a dependency cycle once delivery matches subscriptions.
