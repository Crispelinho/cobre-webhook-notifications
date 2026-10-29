# Discrepancy reconciliation plan, 2026-10-28

Companion to the [discrepancy audit](discrepancy-audit-2026-10-28.md). The audit lists where two sources disagree. This plan records which source wins, which findings were re-checked against the code, what the developer decided, and how each fix will be validated. Decisions in this document do not change the decision log or the specs by themselves; see section 5.

## 1. Re-check of the audit's high-severity items

Checked against `origin/develop` on 2026-10-28.

| Audit ID | Claim | Result |
|---|---|---|
| 1 | `objectives.md` ranks HMAC as *Should* and AES-GCM and DNS pinning as *Could*, while D24 to D26 treat them as in force | Confirmed |
| 2 | `HttpsWebhookSender` uses the plain JDK `HttpClient`, checks only the scheme and does not reference `DestinationPolicy`. The policy is used only by `shared/security` and the subscriptions adapter | Confirmed |
| 3 | The code enforces port 443 (`DestinationPolicy`), a cap of 50 active subscriptions (`SubscriptionService.MAX_ACTIVE_PER_CLIENT`) and a maximum of 50 event types (`EventTypes.MAX_SIZE`) | Confirmed in code: port 443, cap of 50 active subscriptions, at most 50 event types, each matching `^[a-z0-9_]{1,100}$` (`EventTypes`). The spec gap was not re-read |
| 4 | D17 says `@RetryableTopic`; the code uses `RetryTopicConfigurationBuilder` in `DispatchRetryConfiguration` | Confirmed |
| 5 | Seed subscriptions have no encrypted secret | Confirmed: `SampleDataSeeder` inserts seed subscriptions without `secret_ciphertext`, with URL `https://webhook.example.com/<client>` |
| 14 | `security-analysis.md` labels a row "API10 / A06 Vulnerable and Outdated Components". API10:2023 is *Unsafe Consumption of APIs*, and "Vulnerable and Outdated Components" is an OWASP 2021 category | Confirmed |

## 2. Authority order

When two sources disagree, the higher one wins:

1. **Approved decision** in [decision-log.md](../architecture/decision-log.md), with its origin. A decision changes only through a new entry or an explicit superseding entry.
2. **Approved OpenSpec spec** in `openspec/specs/`. It changes only through a change proposal.
3. **Objectives and security analysis.** They must reflect points 1 and 2, never the reverse.
4. **Code.** It is evidence of what runs, not of what is correct. If it contradicts points 1 or 2, the code is the defect, unless a human records an exception.
5. **README and AI-usage log.** They describe the project and are updated last.

An open gap must be written as *partial* in the security analysis. It must not be presented as complete.

## 3. Resolution plan

### A. Documentation only

- Item 1: `objectives.md` corrected in this commit. HMAC moves from *Should* to *Must*, and IP pinning against DNS rebinding moves from *Could* to *Must*. **AES-GCM is still listed as *Could*** while D25 treats it as in force. That correction is pending.
- Item 9: add `subscriptions:read` and `subscriptions:write` to D22 and to the platform-security spec.
- Item 14: relabel the row as OWASP 2021 A06, or map it to API9:2023.
- Item 16: update the README (status banner, endpoint table, roadmap, `demo` profile).
- Item 18: note in D46 that V3 is unused.
- Item 21: add `.env` and `*.pem` to `.gitignore`.

### B. Specification gaps

These need a change proposal, because they add or tighten requirements.

- Item 3: add the cap, the list limit, the port rule and the event type rules to `notification-delivery` and `platform-security`.
- Item 4a: add the `Retry-After` rules from section 4 to `notification-delivery`.
- Item 6: add scenarios for `PATCH {}` (400) and for deleting an inactive subscription (204).
- Item 8: specify that the ciphertext decrypts only with its `subscription_id` as associated data.
- Item 10: specify that `t` is regenerated on every attempt and that the body is byte-identical across attempts.
- Item 11: specify that replay uses the `destination_url` snapshot (D13), re-validates the destination policy at send time and loads the secret by `subscription_id`.

### C. Code changes

These are not authorized yet. Each one starts only after its spec change is approved.

- Item 2: send-time validation with IP pinning (decision 1 in section 4).
- Item 1 and task 4.2: HMAC signing connected to `HttpsWebhookSender` (decision 2).
- Item 3 and item 7: subscription cap, on creation and on reactivation, checked atomically (decision 3).
- Items 12 and 13: `Retry-After` and total timeout (decision 4).
- Item 5: demo secret from external configuration (decision 5).
- Item 7 (processing recovery): lease, atomic claim and generation token (decision 7).
- Item 20: one shared `Clock` bean in `shared/config`.

## 4. Resolved decisions

The developer approved these resolutions on 2026-10-29.

1. **Send-time SSRF validation and IP pinning (item 2).** Implement both. D26 prevails over the objectives. Acceptance: a DNS rebinding test, blocking of private destinations and redirects, and a test that the connection uses the validated IP.

2. **HMAC (item 1 and task 4.2).** Implement before the panel. Connect `WebhookSigner` to `HttpsWebhookSender` and send `Cobre-Signature`. Define and test the signature format and how a receiver verifies it, in the integration documentation.

3. **Subscription cap overflow (item 3).** Return `409 Conflict` with `code: SUBSCRIPTION_LIMIT_REACHED`. The check is atomic, so concurrent requests cannot exceed 50 active subscriptions. It applies on creation and on reactivation.

4. **`Retry-After` and total timeout (items 12 and 13).**
   - **4a. `Retry-After`.** Honour it, with a cap of **300 seconds**, configurable per environment. A valid value from 0 to 300 seconds is honoured. A value above the cap schedules the retry at the cap, never earlier. A missing or invalid value falls back to the configured backoff. Past HTTP dates and invalid values fall back to backoff. The five-attempts-per-round limit stays. The cap value is new: D19 says "capped" without a number, so it needs a decision-log entry (section 5).
   - **4b. Total timeout.** Verify first how the JDK `HttpClient` applies `HttpRequest.timeout` to response bodies. Guarantee 10 seconds in total per attempt. If the JDK does not guarantee it, implement effective cancellation and test it against a receiver that sends its body slowly.

5. **Demo secret (item 5).** Provision the demo secret from external configuration. Keep it out of SQL and out of the repository. Use a development-only secret. Confirm that the demo host can verify the signature. No unsigned exception.

6. **Delivery semantics.** Adopt this wording in the design docs:

   > Delivery follows at-least-once semantics while the configured attempts and recovery operations run. A webhook may reach the receiver more than once. Receivers must deduplicate with `Cobre-Notification-Id`. A timeout does not prove the receiver did not process the request. There is no end-to-end exactly-once guarantee.

7. **Recovery of notifications stuck in `processing`.** Approved: a lease with an expiry, an atomic claim and a generation token (fencing token). Writes are conditional on the token. The attempt history is kept. A stale worker cannot overwrite another worker's result. An interrupted send is not assumed to have failed at the receiver.

## 5. Pending before implementation

- **Decision log.** D19 needs the 300-second cap and D20 needs the decision on read and total timeouts. Both are decisions, so they need a new or superseding entry, not an edit of the existing text. This needs approval.
- **Specs.** Section 3B needs a change proposal before any requirement changes.
- **AES-GCM.** The objectives still list it as *Could* while D25 treats it as in force. Same correction as HMAC, pending the developer's decision.
- **Implementation.** Not authorized yet.

## 6. Validation plan

Each fix needs a test or a check before it merges. Run `./gradlew clean build` in a clean context before any merge.

- **Send-time validation (decision 1):** a hostname that resolves to a private address at send time is rejected. The connection goes to the validated address. This needs a resolver seam that the test can control. Test DNS rebinding and redirects.
- **HMAC (decision 2):** the existing `WebhookSignerTests` vector stays. Add a sender test that checks the `Cobre-Signature` header and that the body is byte-identical across retries. Test verification with an independent implementation.
- **Caps and port (decision 3, items 3 and 7):** acceptance tests for the 51st active subscription, for reactivation at the cap, for concurrent creation at the cap, and for port 8443 rejected.
- **`Retry-After` (decision 4a):** tests for a valid value, a value above 300 seconds, a value in the past, an HTTP date, an invalid value and a missing header.
- **Total timeout (decision 4b):** a test with a receiver that sends its body slowly, which must end within 10 seconds.
- **Processing recovery (decision 7):** a concurrency test where a stale worker tries to write after another worker has claimed the notification.
- **Retry topics (item 4):** after the D17 and D18 wording is corrected, the docs match the builder and the jitter behaviour.
- **Documentation:** no row labelled "API10 / A06" remains. Objectives match the decision IDs.

## 7. What this commit changes

This commit changes `docs/requirements/objectives.md` and this document only. No decision, spec, source file or dependency is modified.
