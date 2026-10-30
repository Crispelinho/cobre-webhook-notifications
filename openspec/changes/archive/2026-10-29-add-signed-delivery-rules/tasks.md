# Tasks: Add signed delivery rules

## 1. Approval

- [x] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added for the body rule and the delivery semantics

## 2. Tests first (D41)

- [x] 2.1 `SecretCipher` test: a ciphertext decrypts with its own `subscription_id` and fails with another ID or none
- [x] 2.2 Sender test: two attempts of the same notification event carry different `t` values, each within the tolerance at send time, and identical body bytes
- [x] 2.3 Sender test: every attempt and every round carries the same `Cobre-Notification-Id`
- [x] 2.4 Independent verification of the signature on a retried attempt (existing `WebhookSignerTests` vector stays)

## 3. Implementation

- [x] 3.1 Take `t` from the clock at send time on every attempt
- [x] 3.2 Deterministic body serialization (or stored body bytes) across attempts and rounds
- [x] 3.3 Receiver integration documentation: signature verification and deduplication on `Cobre-Notification-Id`

## 4. Verification

- [x] 4.1 `./gradlew build` passes; dual blind AI review (delivery and security change) before the pull request

## Archive notes (2026-10-29)

- Implemented by PR #31 as tests and receiver documentation; the behaviour already existed.
- 4.1: the build passed before merge according to PR #31 (528 tests). A dual blind AI review of the delivery and security path ran after merge, on `develop` (983f324, 2026-10-30), and covered this change. Round 1 had no critical findings and one real warning (the last retryable attempt of a round left the event `retrying`), fixed in #53 (D70). Rounds 2 to 4 reviewed that fix; round 4 had no critical or real findings. Theoretical findings and suggestions are listed in the #53 description.
