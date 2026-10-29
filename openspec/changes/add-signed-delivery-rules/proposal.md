# Change: Add signed delivery rules

## Why

Three rules that the signing and delivery code depends on are not in the specs:

- The `platform-security` spec says the secret ciphertext "decrypts back to it with the configured key". The code binds each ciphertext to its `subscription_id` as AES-GCM associated data, so decryption without that ID fails (gap item 8). The spec must say so, or a caller could be written that decrypts without the ID.
- The spec does not say that the signature timestamp `t` is regenerated on every attempt. With a 5-minute tolerance (D24) and backoff steps longer than 5 minutes, reusing the first `t` would make later attempts unverifiable (gap item 10). It also does not say that the body is byte-identical across attempts, which receivers need to deduplicate and verify.
- The delivery semantics approved by the developer (reconciliation decision 6: at-least-once, deduplication on `Cobre-Notification-Id`, a timeout does not prove non-processing) are not stated as a requirement.

## What Changes

- The stored secret ciphertext decrypts only with the configured key and the `subscription_id` it was encrypted for.
- Each attempt signs with a new `t` taken at send time; the body bytes are identical across all attempts of a notification event.
- A documented delivery-semantics requirement: at-least-once while attempts and recovery run, the same `Cobre-Notification-Id` on every attempt, receivers deduplicate on it, and a timeout does not prove that the receiver did not process the request. No end-to-end exactly-once guarantee.

## Decisions to confirm

Existing decisions relied on: D24 (signature format, 5-minute tolerance, `Cobre-Notification-Id`), D25 (per-subscription secret, AES-GCM, key from environment), D13 (destination snapshot), D06 (replay keeps the notification event ID); reconciliation plan section 4, decision 6 (delivery semantics wording).

New decisions:

1. **Byte-identical body across rounds, not only across attempts.** Proposed: yes; a replay sends the same bytes as the first round, so receivers can compare bodies under the same `Cobre-Notification-Id`. This requires deterministic serialization or storing the serialized body. Alternative: identical only within a round. The delta specs are drafted with the proposed option ("every attempt, in every round"); if the alternative is chosen, the "Webhook signature" and "Deduplication identifier" requirements must be narrowed. *Needs a new decision-log entry.*
2. **Delivery semantics as a decision-log entry.** Decision 6 is approved in the reconciliation plan but has no D-entry; by the authority order it should be recorded. *Needs a new decision-log entry.*
3. **Ciphertext format version byte as associated data.** The code already includes a format version in the associated data. Proposed: keep it as an implementation detail, not in the spec. *No decision-log entry needed.*

## Impact

- Affected specs: `platform-security` (webhook secrets, webhook signature), `notification-delivery` (delivery semantics)
- Affected code: `SecretCipher` and its callers (tests only if already bound), `HttpsWebhookSender` and `WebhookBody` (per-attempt `t`, deterministic body), integration documentation for receivers
- Dependencies: touches the delivery path, as does `add-processing-recovery`; implement them in sequence, not in parallel branches. Independent of `add-subscription-limits` and `add-retry-after-cap`.
- Out of scope: the demo signing secret and its provisioning are handled in a separate proposal by the change 4 session. This change does not alter the rule for subscriptions without a secret in the "Signed webhooks" requirement of `add-event-ingestion-and-delivery`.
