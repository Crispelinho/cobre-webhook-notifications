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
| **API2: Broken Authentication** | A07 Identification and Authentication Failures | Forged, expired or wrongly scoped tokens; a client token used on the internal endpoint. | RS256 signature, `exp`, `iss` and `aud` validation; `client_id` claim required; scopes per endpoint. | Missing, expired, wrongly signed and claim-less tokens return `401` without revealing the reason; a missing scope returns `403`; endpoints without an explicit rule are denied. A client token on `POST /events` returns `403`: the endpoint requires `SCOPE_events:write` (`SecurityConfig`). |
| **API7: Server Side Request Forgery** | A10 SSRF | A webhook URL such as `https://169.254.169.254/` or `https://localhost/admin` turns the service into a proxy into the internal network. | HTTPS only; host allowlist from configuration; DNS resolution checked against private, loopback and link-local ranges at registration and at send time; connection to the validated IP; no redirects; URLs never taken from events. | Subscriptions to `http://…`, `https://127.0.0.1` or a host outside the allowlist are rejected; a redirect response is not followed. |

## Risks documented

| OWASP API 2023 | Mitigation |
|---|---|
| **API3: Broken Object Property Level Authorization** (mass assignment) | Explicit request DTOs; `PATCH` accepts only `active` and `event_types`; `client_id` is never read from the body. |
| **API4: Unrestricted Resource Consumption** | Page size capped at 100; HTTP timeouts on webhooks; per-client rate limiting with Bucket4j (Could). |
| **API8: Security Misconfiguration** | Actuator exposes only `health` and `info`; errors as problem details without stack traces; container images pinned. |
| **A06:2021 Vulnerable and Outdated Components** (supply chain; OWASP Top 10 2021, not in API Top 10 2023) | Dependency graph submission and dependency review on every pull request; vulnerable transitive dependencies pinned to patched versions (decision D30). |

## Webhook authenticity

Receivers verify that a webhook comes from Cobre with an HMAC-SHA256 signature over the timestamp and body (`Cobre-Signature: t=<ts>,v1=<hex>`). Requests older than 5 minutes are rejected, which prevents replay of captured requests. Each subscription has its own secret, shown once at creation and encrypted at rest with AES-GCM.

## Findings during development

The CI dependency review blocked the first pull request with three high-severity advisories in transitive dependencies of `kafka-clients` 4.2.2. None of them were introduced by our code; all were caught before reaching `develop`.

| Package | Advisory | Fix |
|---|---|---|
| `at.yawk.lz4:lz4-java` 1.11.2 | [GHSA-mcr4-qmvw-px4g](https://github.com/advisories/GHSA-mcr4-qmvw-px4g) (CVE-2026-106451): native library extraction to a shared temporary directory | Constrained to 1.11.4 |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | [GHSA-q233-g92q-qxp6](https://github.com/advisories/GHSA-q233-g92q-qxp6) (CVE-2026-108106): unbounded memory allocation | Constrained to 1.1.10.12 |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | [GHSA-gwq9-9ffj-mvrv](https://github.com/advisories/GHSA-gwq9-9ffj-mvrv) (CVE-2026-93451): heap buffer overflow | Constrained to 1.1.10.12 |
