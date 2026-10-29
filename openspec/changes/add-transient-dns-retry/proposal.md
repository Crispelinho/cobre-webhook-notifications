# Change: Add transient DNS retry

## Why

At send time, `DestinationPolicy` turns any DNS resolution failure into `RejectedDestinationException("host does not resolve")`. `HttpsWebhookSender` records it as `invalid_destination`, and `DeliveryOutcome` classifies that as `PERMANENT`, so a DNS outage of a few seconds fails the notification event immediately and forces a manual replay. D19 lists "SSRF-blocked destinations" as an immediate `failed`, but a host that does not resolve is not an SSRF block. No spec or decision covered resolution failures until D54.

## What Changes

- A DNS resolution failure at send time (`UnknownHostException` or an empty result) is a retryable attempt with its own `error_type` `dns_error`, whatever the cause (NXDOMAIN, SERVFAIL or timeout).
- A host that resolves to a non-public address, a host outside the allowlist, a non-HTTPS URL or a port other than 443 stays an immediate `failed` with `invalid_destination`.
- The limit of 5 attempts per round does not change, so a host that never resolves ends `failed` after 5 attempts.
- Validation at registration (`POST /subscriptions`) is unchanged.

## Decisions to confirm

Existing decisions relied on: D54 (every DNS failure at send time is retryable; SSRF stays permanent), D19 (retryable versus immediate `failed`), D21 (the domain classifies outcomes), D26 (send-time validation and IP pinning), D18 (5 attempts per round).

New decisions:

1. **`error_type` value.** Proposed: `dns_error`, next to `timeout` and `connection_error`. *No decision-log entry needed if accepted.*

## Impact

- Affected specs: `notification-delivery`
- Affected code: `DestinationPolicy` (a distinct exception for resolution failures, so it is not a `RejectedDestinationException`), `HttpsWebhookSender` (map it to the new transport error), `TransportError` (`DNS`), `DeliveryOutcome` (`DNS` is `RETRYABLE`), and the subscription REST adapter if registration must keep answering `400` for a host that does not resolve
- Dependencies: touches the sender and the outcome classification, as do `add-retry-after-cap` and `add-signed-delivery-rules`; implement them in sequence, not in parallel branches.
- Spec sequencing: the base "Send-time destination safety" requirement lives in the active change `add-event-ingestion-and-delivery`, which is not archived yet, so this change uses an `ADDED` requirement.
