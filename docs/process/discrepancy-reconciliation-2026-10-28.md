# Discrepancy reconciliation plan, 2026-10-28

Companion to the [discrepancy audit](discrepancy-audit-2026-10-28.md). The audit lists where two sources disagree. This plan records which source wins, which findings were re-checked against the code, what needs a human decision, and how each fix will be validated. Nothing here changes a decision, spec or code by itself.

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

These need approval, not a code change.

- Item 1: move HMAC, AES-GCM and DNS pinning in `objectives.md` to match D24 to D26. Keep DNS pinning marked as pending until item 2 is closed.
- Item 9: add `subscriptions:read` and `subscriptions:write` to D22 and to the platform-security spec.
- Item 14: relabel the row as OWASP 2021 A06, or map it to API9:2023.
- Item 16: update the README (status banner, endpoint table, roadmap, `demo` profile).
- Item 18: note in D46 that V3 is unused.
- Item 21: add `.env` and `*.pem` to `.gitignore`.

### B. Specification gaps

These need a change proposal, because they add or tighten requirements.

- Item 3: add the cap, the list limit, the port rule and the event type rules to `notification-delivery` and `platform-security`.
- Item 6: add scenarios for `PATCH {}` (400) and for deleting an inactive subscription (204).
- Item 8: specify that the ciphertext decrypts only with its `subscription_id` as associated data.
- Item 10: specify that `t` is regenerated on every attempt and that the body is byte-identical across attempts.
- Item 11: specify that replay uses the `destination_url` snapshot (D13), re-validates the destination policy at send time and loads the secret by `subscription_id`.

### C. Code changes

- Item 2: send-time validation with IP pinning. The connection must go to the validated address while keeping SNI and hostname verification.
- Item 7: check the 50-subscription cap on reactivation as well as on creation.
- Item 12: honour `Retry-After` on 429 (capped), or remove it from D19 and the spec.
- Item 13: implement the 5 s read timeout, or remove it from D20 and the spec.
- Item 20: one shared `Clock` bean in `shared/config`.

### D. Decisions for a human

Nothing in sections A to C should start until these are answered:

1. **Send-time validation and IP pinning (item 2).** Implement both before the panel, or document the control as partial? D26 already requires both. Pinning is listed as *Could* in the objectives. Recommendation: implement send-time validation, and mark pinning as partial if time runs short.
2. **HMAC (item 1 and task 4.2).** D24 makes it a decision and the objectives list it as *Should*. The signer exists, but `HttpsWebhookSender` sends no `Cobre-Signature` header. Implement it before the panel, or present it as a documented gap?
3. **Cap overflow response (item 3).** Return 400, 409 or 429 when a client reaches 50 active subscriptions?
4. **Read timeout and `Retry-After` (items 12 and 13).** Implement or remove?
5. **Unsigned demo seed (item 5).** Allow an unsigned seed subscription as a named exception in D24, or seed an encrypted secret for the demo host?
6. **Delivery semantics statement.** Adopt this wording in the design docs: *webhook delivery is at-least-once; receivers deduplicate with `Cobre-Notification-Id`; a timeout does not prove the receiver did not process the webhook; there is no end-to-end exactly-once guarantee.* The repository does not state this today. Adopt, amend or reject?
7. **Recovery of notifications stuck in `processing`.** Nothing in the repository covers this yet. The proposed design uses a lease with an expiry and an atomic claim, not a plain timeout reset. Approve this direction?

### E. Still unknown

- The audit's "Not verified" section: `Redirect.NEVER` with a 302, the 401 path through the problem+json handlers, and AI-usage entries 01 to 03.

## 4. Validation plan

Each fix needs a test or a check before it merges. Run `./gradlew build` before any merge.

- **Send-time validation (item 2):** a hostname that resolves to a private address at send time is rejected. The connection goes to the validated address. This needs a resolver seam that the test can control.
- **HMAC (item 1):** the existing `WebhookSignerTests` vector stays. Add a sender test that checks the `Cobre-Signature` header and that the body is byte-identical across retries.
- **Caps and port (items 3 and 7):** acceptance tests for the 51st active subscription, for reactivation at the cap, and for port 8443 rejected.
- **Retry topics (item 4):** after the D17 and D18 wording is corrected, the docs match the builder and the jitter behaviour.
- **Documentation:** the audit items marked as fixed are checked by reading both sides again. No row labelled "API10 / A06" remains. Objectives match the decision IDs.

## 5. What this plan does not change

No decision, spec, source file, dependency or branch other than this document's branch was modified to write this plan.
