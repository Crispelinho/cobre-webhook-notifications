# Tasks: Add subscription limits

## 1. Approval

- [x] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added for the cap, the list limit and the event type and port rules

## 2. Tests first (D41)

- [x] 2.1 Acceptance tests for the 51st active subscription on creation and on reactivation (`409`, `code: SUBSCRIPTION_LIMIT_REACHED`)
- [x] 2.2 Concurrency test: parallel creations and reactivations at 49 active subscriptions end with exactly 50 active
- [x] 2.3 Tests for event type rules (empty, 51 entries, invalid pattern, on creation and on `PATCH`)
- [x] 2.4 Tests for the list limit, `PATCH {}`, deleting an inactive subscription and port 8443 rejected

## 3. Implementation

- [x] 3.1 Atomic cap check in the repository, used by creation and reactivation
- [x] 3.2 `SubscriptionLimitReachedException` mapped to `409` problem+json with `code`
- [x] 3.3 Remove the "not an exact quota" comment and behaviour from `SubscriptionService`

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; fresh-context AI review before the pull request
