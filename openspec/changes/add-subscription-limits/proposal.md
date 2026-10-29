# Change: Add subscription limits

## Why

The code already bounds what one client can register: at most 50 active subscriptions, at most 50 event types per subscription, a list of at most 100 subscriptions, and destinations on port 443 only. None of this is in `openspec/specs` (reconciliation plan, gap item 3). The cap is also checked on creation only and without atomicity, so reactivation with `PATCH active:true` and concurrent creations can exceed it (audit items 3 and 7). Two existing behaviours have no scenario: `PATCH {}` and deleting an inactive subscription (gap item 6). The developer approved the cap behaviour on 2026-10-29 (reconciliation decision 3).

## What Changes

- Cap of 50 active subscriptions per client, checked atomically on creation and on reactivation. Overflow returns `409 Conflict` as `application/problem+json` with `code: SUBSCRIPTION_LIMIT_REACHED`. Concurrent requests cannot exceed the cap.
- Event type rules for subscriptions: 1 to 50 entries, each matching `^[a-z0-9_]{1,100}$`, on creation and on `PATCH`.
- `GET /subscriptions` returns at most 100 subscriptions, newest first.
- The destination policy accepts only port 443 (explicit or implied by `https`).
- Scenarios for `PATCH {}` (`400`) and for deleting an inactive subscription (`204`).

## Decisions to confirm

Existing decisions relied on: D11 (subscription CRUD), D12 (`PATCH` limited to `active` and `event_types`), D26 (destination policy), D27 (OWASP API4), D10 (problem+json errors); reconciliation plan section 4, decision 3 (cap of 50, `409` with `SUBSCRIPTION_LIMIT_REACHED`, atomic, creation and reactivation).

New decisions:

1. **Decision-log entry for the cap.** Decision 3 is approved in the reconciliation plan but has no D-entry. By the authority order (plan section 2) it should be recorded. *Needs a new decision-log entry.*
2. **Atomicity mechanism.** Proposed: serialize cap checks per client inside the write transaction (for example a per-client advisory lock, or a conditional insert/update that counts active rows in the same statement). Alternative: a per-client counter row updated with a conditional `UPDATE`. The spec only requires that concurrency cannot exceed the cap. *Design choice; no decision-log entry unless the developer wants one.*
3. **List limit of 100 without pagination.** The code returns the 100 newest subscriptions and no paging metadata. Alternative: offset pagination as in D08. *Needs a new decision-log entry* (it is a public API contract).
4. **Maximum of 50 event types per subscription** and port 443 only. Both codify current code. Port 443 tightens D26. *Needs a new decision-log entry* (one entry can cover both).
5. **Duplicate event types** are dropped, keeping the first occurrence (current code). Alternative: reject with `400`. *No decision-log entry needed if kept as is.*
6. **Reactivating an already active subscription** (`PATCH active:true` on an active one) does not count against the cap and succeeds even when the client is at the cap.

## Impact

- Affected specs: `notification-delivery` (subscription requirements), `platform-security` (destination policy)
- Affected code: `subscriptions` capability only: `SubscriptionService` (cap on create and reactivate), `JdbcSubscriptionRepository` (atomic check), `SubscriptionExceptionHandler` (`409` with `code`), `EventTypes`, `DestinationPolicy` (port rule already present; tests only)
- Dependencies: none. This change touches subscriptions only and does not overlap with the delivery path changes (`add-retry-after-cap`, `add-processing-recovery`, `add-signed-delivery-rules`).
