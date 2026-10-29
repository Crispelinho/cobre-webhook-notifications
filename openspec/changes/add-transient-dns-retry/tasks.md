# Tasks: Add transient DNS retry

## 1. Approval

- [x] 1.1 Developer approves retrying every DNS failure at send time; decision-log entry D54 added
- [ ] 1.2 Developer approves the `error_type` value `dns_error`

## 2. Tests first (D41)

- [ ] 2.1 Unit tests for `DestinationPolicy`: a resolution failure raises the resolution exception, and a non-public address still raises `RejectedDestinationException`
- [ ] 2.2 Unit test for the outcome classification: `DNS` is `RETRYABLE`
- [ ] 2.3 Sender tests with a fake resolver: failure then success, never resolves (5 attempts, then `failed`), private address (immediate `failed`)
- [ ] 2.4 Acceptance test: registration of a host that does not resolve keeps its current response

## 3. Implementation

- [ ] 3.1 Resolution exception in `DestinationPolicy`, separate from `RejectedDestinationException`
- [ ] 3.2 `TransportError.DNS` (`dns_error`), classified `RETRYABLE`
- [ ] 3.3 Map the resolution exception in `HttpsWebhookSender`, and keep the registration response unchanged

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; fresh-context AI review before the pull request
