# Discrepancy audit, 2026-10-28

Repo-wide audit of places where two sources of truth disagree: decision log, objectives, security analysis, OpenSpec specs and changes, code, README and AI-usage log. Taken against `origin/develop` after PRs #5 (subscriptions and outbound security), #6 (event ingestion and delivery) and #7 (AI usage entry 04) were merged.

**Status key.** `VERIFIED` means both sides were read. `SUSPECTED` means the conflict is probable but not confirmed. Items marked *reported* were checked by a read-only audit and have not been rechecked line by line.

## High

| ID | Category | Conflict | Suggested resolution | Status |
|---|---|---|---|---|
| 1 | Docs vs decisions | `objectives.md:36-37` ranks HMAC as *Should* and AES-GCM and DNS pinning as *Could*. D24, D25 and D26 and `security-analysis.md` treat them as in force. | Promote them in the objectives to match the decisions. Keep DNS pinning marked as pending until item 2 is closed. | VERIFIED |
| 2 | Code vs docs | D26, `security-analysis.md` and the change 2 spec require validation at send time and a connection only to the validated IP. `HttpsWebhookSender` on the ingestion branch uses the plain JDK `HttpClient`, checks only the scheme, and does not import `DestinationPolicy`. | Implement send-time validation with a resolver or socket factory that pins the IP while keeping SNI and hostname verification. Until then, the security analysis must say the control is partial. | VERIFIED (sender); pinning design *reported* |
| 3 | Spec vs code | The code enforces HTTPS on port 443 only, a cap of 50 active subscriptions per client, a list limit of 100, and 50 event types of 1 to 100 characters. None of this is in `openspec/specs`. The only mention is a README line. | The code is the reference. Add requirements and scenarios to `notification-delivery` and `platform-security`. Decide whether the cap returns 409 or 429 instead of 400. | *reported* |
| 4 | Decision vs code | D17 says `@RetryableTopic`. The code uses `RetryTopicConfigurationBuilder`. Jitter is drawn once per retry topic at startup, not per message as D18 and the change 2 spec imply. | Reword D17, D18 and the tasks to describe programmatic `RetryTopicConfiguration`, with jitter per topic. | D17 VERIFIED; code *reported* |
| 5 | Seed vs security | Seed subscriptions point to `https://webhook.example.com/<client>` with a null `secret_ciphertext`. D24 says every subscription has a secret. The replay scenario that expects EVT003 to be delivered cannot pass with an empty allowlist. | Either seed an encrypted secret and an allowlisted demo host, or limit that scenario to a test subscription. State the unsigned-seed exception in D24. | *reported* |

## Medium

| ID | Category | Conflict | Suggested resolution | Status |
|---|---|---|---|---|
| 6 | Spec vs code | `PATCH {}` returns 400, but the spec has no scenario for it. Deleting an already inactive subscription returns 204, also without a scenario. | Add both scenarios. | *reported* |
| 7 | Code bug | Reactivating a subscription with `PATCH active:true` does not check the 50-subscription cap. Creation does. | Check the cap on reactivation, or document the gap as a known limit. | *reported* |
| 8 | Spec vs code | `platform-security` says the secret ciphertext "decrypts back with the configured key". The code uses AES-GCM with a version byte and the subscription id as associated data, so decryption fails without the id. | Tighten the spec. Change 2 must pass `subscription_id` when decrypting. | *reported* |
| 9 | Docs vs decisions | D22 and `platform-security` list only `notifications:read`, `notifications:replay` and `events:write`. The subscription scopes exist in code and in the notification-delivery spec. | Add `subscriptions:read` and `subscriptions:write` to D22 and to the platform-security spec. | *reported* |
| 10 | Spec gap | The `WebhookSigner` takes the timestamp as an argument. The 5-minute tolerance in D24 is shorter than the default backoff (30 s, 2 min, 8 min, 32 min). Nothing says `t` is regenerated on each attempt. | Add to the signed-webhooks requirement that `t` is regenerated on every attempt and the body is byte-identical across attempts. | *reported* |
| 11 | Spec gap | The replay spec does not say which destination a replay uses: the `destination_url` snapshot (D13) or the current subscription. It also does not cover a notification whose subscription is inactive, deleted or without a secret. | Specify that replay uses the snapshot, re-validates the destination policy at send time, and loads the secret by `subscription_id`. | SUSPECTED |
| 12 | Decision vs code | D19 says `Retry-After` on 429 is honored, capped. Nothing in the sender or the attempt result reads it. | Implement it, or remove it from D19 and the spec. | *reported* |
| 13 | Decision vs code | D20 says read timeout 5 s, total 10 s. The code sets connect 2 s and request 10 s. There is no separate read timeout. | Remove the read timeout from D20 and the spec, or implement it. | D20 VERIFIED; code *reported* |
| 14 | Docs | `security-analysis.md:28` labels a row "API10 / A06 Vulnerable and Outdated Components". API10:2023 is *Unsafe Consumption of APIs*. A06 is the 2021 list. | Relabel it as A06:2021 or map it to API9. | VERIFIED |
| 15 | Docs | `security-analysis.md` lists API3 as documented and API4 as only *Could* (Bucket4j). The subscription cap and the list limit already implement part of API4, and API3 is implemented and tested. | Move API3 to implemented. Add the subscription cap under API4 in D27 and objective O8. | *reported* |
| 16 | Docs | The README on `develop` is stale. The status banner says "first change pending", and the endpoint table and roadmap show merged changes as specified or planned. The `demo` profile and port 8081 are not documented. | Update the README. | *reported* |

## Low

| ID | Category | Conflict | Suggested resolution | Status |
|---|---|---|---|---|
| 17 | Decision | D18 gives the default backoff as "exponential with jitter" with no values. The design says "30 s up to 1 h". The code uses 30 s, multiplier 4, max 1 h, jitter 5 s. With 5 attempts the 1 h cap is never reached. | Document the real schedule. | VERIFIED (D18); code *reported* |
| 18 | Decision | D46 reserves V3 for subscription management. The subscription change needed no migration, and entry 05 says V3 was left unused. D46 was not updated. | Note "V3 unused" in D46. | *reported* |
| 19 | Code vs docs | `ClientId` is duplicated in `notifications.domain` and `subscriptions.domain`. `events.domain` has its own `Subscription` record. Entry 05 approves the duplication. | Record the decision in D31 or `project.md`. Plan a shared kernel after the merges. | *reported* |
| 20 | Code | Each capability builds its own `Clock.systemUTC()`. No shared `Clock` bean exists, so tests cannot control time consistently. | Add one `Clock` bean in `shared/config`. | *reported* |
| 21 | Config | `.gitignore` covers `.local/` but not `.env` or `*.pem`. | Add `.env` and `*.pem`. | *reported* |

## Resolved since the first review

- **Flyway collision.** Ingestion uses only V2, subscriptions added no migration, and V3 is free.
- **Duplicate edits to `application.yaml` and `SecurityConfig`.** Both changes are on `develop` once.
- **Outbox columns.** The data model in `system-design.md` matches V2.
- **Actuator exposure.** Only health and info are exposed, and `permitAll` covers only those two paths.
- **Testcontainers scope.** The LGTM container lives in its own configuration. Each run gets a random secrets key.
- **Change 4 task checkboxes.** They match the code.
- **AI-usage entry 04.** Added in PR #7. Screenshots are still missing (see the problem statement, which asks for prompts, screenshots and similar).

## Not verified

- Whether `Redirect.NEVER` with a 302 is covered by a test.
- Whether the unauthenticated 401 path reaches the problem+json handlers on the ingestion branch.
- The contents of AI-usage entries 01 to 03.
