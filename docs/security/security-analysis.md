# Security Analysis

Task 3: the API can be consumed publicly over the internet. This document identifies OWASP risks and their mitigations. Reference: OWASP API Security Top 10 (2023), mapped to the OWASP Top 10 (2021) (decision D27).

## Threat model

| Element | Content |
|---|---|
| **Assets** | Financial notification content; webhook signing secrets; client and service access tokens; the internal network reachable from the service. |
| **Actors** | A malicious client; an attacker with a stolen token; someone who registers a webhook URL pointing at internal infrastructure; a misconfigured internal producer. |
| **Entry points** | The public self-service API; the internal `POST /events`; outbound webhook calls; third-party dependencies (supply chain). |

## Risks implemented and tested

| OWASP API 2023 | OWASP 2021 | Threat | Mitigation | Test |
|---|---|---|---|---|
| **API1: Broken Object Level Authorization** | A01 Broken Access Control | CLIENT001 reads or replays EVT003 (owned by CLIENT002) by guessing IDs. | `client_id` comes only from the token. Every query port requires `ClientId`. A foreign resource returns the same `404` as a missing one. | CLIENT001 requests EVT003 and a non-existent ID: both `404` with the same body. `?client_id=CLIENT002` is ignored. |
| **API2: Broken Authentication** | A07 Identification and Authentication Failures | Forged, expired or wrongly scoped tokens; a client token used on the internal endpoint. | RS256 signature, `exp`, `iss` and `aud` validation; `client_id` claim required; scopes per endpoint. | Missing, expired, wrongly signed and claim-less tokens return `401` without revealing the reason; a missing scope returns `403`; endpoints without an explicit rule are denied. A client token on `POST /events` returning `403` is planned with the ingestion change. |
| **API3: Broken Object Property Level Authorization** (mass assignment) | A01 Broken Access Control | A client changes fields it must not change, such as `url` or `client_id`. | Explicit request DTOs; `PATCH` accepts only `active` and `event_types`; `client_id` is never read from the body. | Patching `url` returns `400` and leaves the subscription unchanged (`attemptToChangeTheUrl`); `{}`, `null` `active` and an extra `secret` field return `400` (`invalidPatches`). |
| **API4: Unrestricted Resource Consumption** | A04 Insecure Design | A client exhausts database rows, DNS lookups or the webhook workers. | Page size capped at 100; at most 50 active subscriptions per client, reactivation included; per-client rate limit of 100 requests per minute, counted in memory per instance (D29); HTTP timeouts and a 10 s total deadline per webhook attempt. | Rate limit: `200` up to the limit, then `429` (`RateLimitAcceptanceTests`). Cap: `activeSubscriptionsPerClientAreCapped` and `reactivationCountsAgainstTheCap`. With N replicas the effective rate limit can be up to N times higher. |
| **API7: Server Side Request Forgery** | A10 SSRF | A webhook URL such as `https://169.254.169.254/` or `https://localhost/admin` turns the service into a proxy into the internal network. | HTTPS only; host allowlist from configuration; DNS resolution checked against private, loopback and link-local ranges at registration and at send time; connection to the validated IP; no redirects; URLs never taken from events. | Subscriptions to `http://…`, `https://127.0.0.1` or a host outside the allowlist are rejected; a redirect response is not followed. |

## Risks documented

| OWASP API 2023 | Mitigation |
|---|---|
| **API8: Security Misconfiguration** | Actuator exposes only `health` and `info`; errors as problem details without stack traces; container images pinned. |
| **A06:2021 Vulnerable and Outdated Components** (supply chain; no direct OWASP API 2023 entry) | Dependency graph submission and dependency review on every pull request; vulnerable transitive dependencies pinned to patched versions (decision D30). |

## Webhook authenticity

Receivers verify that a webhook comes from Cobre with an HMAC-SHA256 signature over the timestamp and body (`Cobre-Signature: t=<ts>,v1=<hex>`). Receivers should reject requests whose timestamp is more than 5 minutes old, which limits the replay of captured requests (D24). The runbook shows the check step by step: [local HTTPS delivery runbook](../process/local-https-delivery-runbook.md). Each subscription has its own secret, shown once at creation and encrypted at rest with AES-GCM.

## Findings during development

The CI dependency review blocked the first pull request with three high-severity advisories in transitive dependencies of `kafka-clients` 4.2.2. None of them were introduced by our code; all were caught before reaching `develop`.

| Package | Advisory | Fix |
|---|---|---|
| `at.yawk.lz4:lz4-java` 1.11.2 | [GHSA-mcr4-qmvw-px4g](https://github.com/advisories/GHSA-mcr4-qmvw-px4g) (CVE-2026-106451): native library extraction to a shared temporary directory | Constrained to 1.11.4 |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | [GHSA-q233-g92q-qxp6](https://github.com/advisories/GHSA-q233-g92q-qxp6) (CVE-2026-108106): unbounded memory allocation | Constrained to 1.1.10.12 |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | [GHSA-gwq9-9ffj-mvrv](https://github.com/advisories/GHSA-gwq9-9ffj-mvrv) (CVE-2026-93451): heap buffer overflow | Constrained to 1.1.10.12 |
