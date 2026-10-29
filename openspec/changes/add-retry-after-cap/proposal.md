# Change: Add Retry-After cap

## Why

D19 says a `429` is retried "honoring a capped `Retry-After`", but nothing in the sender or the attempt result reads the header (audit item 12), and no spec fixes the cap (gap item 4a). D49 now sets the rule: honour `Retry-After` on `429` and `503` up to 300 seconds, configurable per environment. Without a requirement, the behaviour cannot be tested and a receiver could stall a delivery or be retried earlier than it asked.

## What Changes

- A `429` or `503` response with a valid `Retry-After` (delta-seconds or HTTP-date) schedules the next attempt after that delay, capped at a configured maximum (default 300 seconds).
- A value above the cap schedules the next attempt at the cap, never earlier.
- A missing, invalid or past `Retry-After` falls back to the configured backoff step.
- `Retry-After` on any other status is ignored.
- The limit of 5 attempts per round does not change; `Retry-After` never adds attempts.
- The outcome classification carries an optional retry delay next to `RETRYABLE`; the Kafka adapter only applies it (D21).

## Decisions to confirm

Existing decisions relied on: D49 (300-second cap, configurable, above the cap means at the cap, invalid or missing means backoff), D19 (retryable statuses, superseded on the cap by D49), D18 (5 attempts per round, backoff per profile), D17 and D21 (non-blocking retry topics; domain classifies, adapter routes), D16 (no partition may be blocked by one receiver).

New decisions:

1. **Delay for a valid value below the cap.** Proposed: the delay is exactly the `Retry-After` value (it replaces the backoff step for that attempt). Alternative: the larger of the `Retry-After` value and the backoff step. *Needs a new decision-log entry* (D49 does not say which).
2. **Per-message delay on Kafka retry topics.** Retry topics built with `RetryTopicConfigurationBuilder` have a fixed delay per topic, so they cannot express an arbitrary per-message delay of up to 300 seconds. Options: (a) a not-before timestamp carried with the message, with the consumer re-delaying until it is due (must not pause the whole partition, D16); (b) record `next_attempt_at` in PostgreSQL and let a scheduler republish due attempts through the outbox; (c) a dedicated delay topic per bucket. *Needs a design decision and a new decision-log entry* (it changes how D17 is implemented).
3. **`Retry-After: 0`** is a valid value and allows an immediate retry. Alternative: treat values below the first backoff step as the backoff step.
4. **Configuration key and per-profile values.** Proposed: `cobre.delivery.retry-after-cap`, default `PT300S` in every profile, overridable by environment variable. *No decision-log entry needed* (D49 already says configurable per environment).

## Implementation status

Not implemented. The delivery code does not read `Retry-After` today: `HttpsWebhookSender` returns only the response status, and the retry topics use fixed delays. Until this change is implemented, the "Retry policy" requirement of `add-event-ingestion-and-delivery` is not met for `429` and `503`. This proposal documents the required behaviour; it does not claim that the behaviour exists.

## Impact

- Affected specs: `notification-delivery`
- Affected code: `DeliveryOutcome` classification (optional delay with `RETRYABLE`), `AttemptResult`, `HttpsWebhookSender` (read the header), `DispatchRetryConfiguration` and the dispatch consumer (apply the delay), configuration properties
- Dependencies: touches `DispatchRetryConfiguration` and the delivery outcome classification only. It does not depend on the other three proposals. It edits the same dispatch area as `add-processing-recovery`, so the two should not be implemented in parallel branches without coordination.
- Spec sequencing: the base "Retry policy" requirement lives in the active change `add-event-ingestion-and-delivery` (not yet archived) and says "honoring `Retry-After` up to the next backoff step", which contradicts D49. This change adds a separate requirement. Before both are archived, that clause must be removed from `add-event-ingestion-and-delivery`, or a follow-up `MODIFIED` delta must be written once it is archived.
