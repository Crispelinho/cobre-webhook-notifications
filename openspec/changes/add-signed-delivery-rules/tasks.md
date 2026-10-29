# Tasks: Add signed delivery rules

## 1. Approval

- [ ] 1.1 Developer approves the new decisions in `proposal.md`; decision-log entries added for the body rule and the delivery semantics

## 2. Tests first (D41)

- [ ] 2.1 `SecretCipher` test: a ciphertext decrypts with its own `subscription_id` and fails with another ID or none
- [ ] 2.2 Sender test: two attempts of the same notification event carry different `t` values, each within the tolerance at send time, and identical body bytes
- [ ] 2.3 Sender test: every attempt and every round carries the same `Cobre-Notification-Id`
- [ ] 2.4 Independent verification of the signature on a retried attempt (existing `WebhookSignerTests` vector stays)

## 3. Implementation

- [ ] 3.1 Take `t` from the clock at send time on every attempt
- [ ] 3.2 Deterministic body serialization (or stored body bytes) across attempts and rounds
- [ ] 3.3 Receiver integration documentation: signature verification and deduplication on `Cobre-Notification-Id`

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; dual blind AI review (delivery and security change) before the pull request
