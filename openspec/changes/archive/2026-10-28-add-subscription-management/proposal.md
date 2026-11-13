# Change: Add subscription management

## Why

Clients need to register where their notifications go. It is also how the presentation URL is registered at runtime and validated against SSRF rules. This change also delivers the shared outbound security components that delivery uses: the destination policy, webhook secrets and HMAC signing.

## What Changes

- `/subscriptions` for clients: `POST` (returns the signing secret once), `GET` list, `GET` by ID, `DELETE` (logical deactivation) and `PATCH` limited to `active` and `event_types`.
- `shared.security.DestinationPolicy`: HTTPS only, host allowlist from configuration, DNS resolution checked against non-public address ranges. Used at registration here and at send time by `add-event-ingestion-and-delivery`.
- `shared.crypto`: 32-byte random secrets encrypted at rest with AES-GCM, and an HMAC-SHA256 signer for the `Cobre-Signature` header.

## Decisions to confirm

- New scopes: `subscriptions:read` and `subscriptions:write`.
- Allowlist variable `WEBHOOK_ALLOWED_HOSTS`: comma-separated exact hosts; an entry starting with `.` also allows its subdomains.
- Encryption key variable `WEBHOOK_SECRETS_KEY`: base64-encoded 256-bit key; the application does not start without it outside the `local` profile.
- `event_types` must be non-empty, with each value matching `^[a-z0-9_]{1,100}$`.

## Impact

- Affected specs: `notification-delivery`, `platform-security`
- Affected code: `subscriptions` capability, `shared.security`, `shared.crypto`; no schema change (the table exists since `V1`)
- Runs in parallel with `add-event-ingestion-and-delivery`; it does not touch `notifications` or `events`
