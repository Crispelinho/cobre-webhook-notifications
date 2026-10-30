# Demo test plan and verification checklist

Run this plan with [the local HTTPS delivery runbook](local-https-delivery-runbook.md#demo-scenarios). Its IDs (A, B, C, R, G, CL) cover the demo. The seed, registration, ingestion, idempotency and limit cases are in [the seed and demo test plan](seed-and-demo-test-plan.md) (IDs S, R, I, X, L, O, D, P) and are not repeated here.

How to fill it in:

- **Expected** comes from the runbook and the code. It is not evidence until a run confirms it.
- **Observed** holds a value only when a run recorded it. Write the source next to it (for example, "log, 2026-10-29 local time"). Never write tokens, secrets or `whsec_` values.
- **Status** is `Observed`, `Partly observed` or `Not run`. Anything not run stays unverified.

Use a new `event_id` for every ingestion. The seed ids `EVT001` to `EVT010` return `409`.

## 0. Pre-flight

- [ ] The database and Kafka are the ones the service uses. Record the JDBC URL and `spring.kafka.bootstrap-servers` from the run configuration.
- [ ] Flyway applied V1 to V4: `select version from flyway_schema_history order by installed_rank`.
- [ ] The service started with the intended profile (`local` or `local,demo`) and `DEMO_WEBHOOK_SECRET` set. The log shows `Sample data loaded: 10 notification events, 3 seed subscriptions`. Record the profile line Spring logs at startup, because the retry timings depend on it (see [Observed run](local-https-delivery-runbook.md#observed-run-2026-10-29)).
- [ ] `WEBHOOK_ALLOWED_HOSTS` matches the scenario: the default for A and B, the tunnel host for C. Record the exact value.
- [ ] `CLIENT_TOKEN` and `PRODUCER_TOKEN` were generated with `./gradlew devToken` from the directory the service reads its `public.pem` from. A `403` (not `401`) on a producer token used on `/subscriptions` shows that the signature was accepted.
- [ ] Baseline recorded: notifications by `delivery_status`, the Grafana values, and the state of the four alert rules.
- [ ] Other active test subscriptions are deactivated, or their effect is accounted for. They receive every matching event. In the observed run, an active subscription outside the test received `DEMO-C-0001`.

## 1. Smoke tests

| ID | Request | Expected | Observed | Status |
|---|---|---|---|---|
| S1 | `GET /subscriptions` without a token | `401` | | Not run |
| S2 | `GET /subscriptions` with `CLIENT_TOKEN` | `200`, with `data` | | Not run |
| S3 | `POST /subscriptions` with `PRODUCER_TOKEN` | `403` | `403` | Observed |

## 2. Scenario A: failures without a tunnel

| ID | Check | Expected | Observed | Status |
|---|---|---|---|---|
| A1 | `POST /events` with `credit_deposit`, `CLIENT001`, `DEMO-A-0001` | `202` | | Not run |
| A2 | Notification for `SUB-CLIENT001` | `failed`, `last_error` `invalid_destination`, `attempts` 1 | | Not run |
| A3 | `delivery_attempts` | One row: round 1, attempt 1, `http_status` null, `error_type` `invalid_destination` | | Not run |
| A4 | Service log | `Destination of notification event <id> rejected: <reason>`. The reason is `host does not resolve` or `host resolves to a non-public address` | | Not run |
| A5 | Grafana | Outcome `permanent`. `cobre-failed-rate` and `cobre-attempt-failure-rate` firing after 1 min. `cobre-dead-letter` does not move | | Not run |

## 3. Scenario B: rejected destinations

Each request returns `400` with the same generic `detail`. The reason is only in the service log. No new row appears in `subscriptions`.

| ID | `url` | Expected log reason | Observed | Status |
|---|---|---|---|---|
| B1 | `http://example.org/cobre` | `only https is allowed` | | Not run |
| B2 | `https://example.org:8443/cobre` | `only port 443 is allowed` | | Not run |
| B3 | `https://example.org/cobre` | `host is not in the allowlist` | | Not run |
| B4 | `https://10.0.0.5/cobre` | `host is not in the allowlist` | | Not run |
| B5 | `https://webhook.example.com/cobre` | `host resolves to a non-public address` or `host does not resolve`, depending on DNS | | Not run |

Run B1 to B5 before Scenario C, while `WEBHOOK_ALLOWED_HOSTS` is still the default. Otherwise B5 gives `host is not in the allowlist`.

## 4. Scenario C: successes and retries with a tunnel

| ID | Check | Expected | Observed | Status |
|---|---|---|---|---|
| C1 | `start-local-webhook-tunnel.bat` prints the host | `WEBHOOK_ALLOWED_HOSTS=<host>.trycloudflare.com` | Printed in the console | Observed |
| C2 | Service restarted with the tunnel host and `local,demo` | Starts, with the tunnel host as the allowlist | Subscriptions were accepted with the tunnel host (C3). Profile line not recorded | Partly observed |
| C3 | `POST /subscriptions` for `/ok` and `/fail`, both `payment_created` | `201` each, secret returned once | `201` for both. The `/fail` creation is audited as `subscription.create`, `success`, at 21:47:55 | Observed |
| C4 | `POST /events` with `payment_created`, `CLIENT001`, `DEMO-C-0001` | `202` with one notification id per active matching subscription (two, if no other subscription of CLIENT001 is active) | `202` with three ids. One subscription outside the test also matched | Observed |
| C5 | `/ok` notification | `completed`, one attempt, `http_status` 200 | Log: attempt 1 `SUCCESS` at 21:54:10.163. Database not queried | Partly observed |
| C6 | `/ok` receiver line | `/ok 200 <n>` once | Not recorded: the receiver window was not pasted | Not run |
| C7 | `/fail` attempts | Five attempts, each `RETRYABLE` with `http_status` 500 | Five `RETRYABLE` lines, at 21:54:10, 21:54:42, 21:56:51, 22:04:00 and 22:38:37 | Observed |
| C8 | `/fail` receiver lines | `/fail 500 <n>` five times | Not recorded | Not run |
| C9 | `/fail` final status | `failed`, `last_error` `retries_exhausted` | Log: `round 1 failed: retries exhausted` at 22:38:37.801. Database not queried | Partly observed |
| C10 | Dead-letter topic | `Sending to DLT` once, after attempt 5. `cobre-dead-letter` fires for 2 min | Logged at ERROR at 22:38:37.567. Grafana not checked | Partly observed |
| C11 | Timing of `/fail` | Gaps follow the configured `demo` delays: `PT10S`, `PT30S`, `PT1M`, `PT2M` | Gaps of 32.7 s, 128.7 s, 428.5 s and 2 077.4 s. They do not match. Cause not verified | Observed (mismatch) |
| C12 | Isolation: `payment_created` for `CLIENT002` | No notification for the `CLIENT001` subscriptions. The `202` body for an event with no match is not documented | | Not run |
| C13 | Grafana during C | `success` and `retryable` outcomes. `cobre-attempt-failure-rate` firing. `cobre-failed-rate` firing once `/fail` ends | Not checked | Not run |

## 5. Replay

| ID | Check | Expected | Observed | Status |
|---|---|---|---|---|
| R1 | `GET /notification_events/{id}` for a `completed` notification | `200`, full detail with `attempts` | | Not run |
| R2 | `POST /notification_events/{id}/replay` for a `completed` notification | `409`, `Only a failed notification event can be replayed.`, audit `REJECTED` | `409`. Five `rejected` audit lines between 21:32:58 and 21:46:14 | Observed |
| R3 | Replay of a `failed` notification whose host is not allowlisted (`01a18aba-a19a…`) | `202`, `round` 2, `delivery_status` `pending`. The attempt then fails with `invalid_destination` | `202`, round 2 (audit `success` at 22:00:10). Attempt 1 `PERMANENT` at 22:00:11 | Observed |
| R4 | Replay of a `failed` notification after the host is restored to the allowlist | `202`, round 2, then `completed` | | Not run |
| R5 | `GET /notification_events?delivery_status=failed` | Only failed notifications of the client, paginated | | Not run |

## 6. Grafana

| ID | Check | Expected | Observed | Status |
|---|---|---|---|---|
| G1 | Dashboard **Notification delivery** (`cobre-delivery`) | Shows the attempts of the run within 30 s | | Not run |
| G2 | Four rules in folder **Notification delivery** | `cobre-failed-rate`, `cobre-attempt-failure-rate`, `cobre-dead-letter` and `cobre-outbox-age` are in the expected state after each scenario | | Not run |

## 7. Cleanup

| ID | Check | Expected | Observed | Status |
|---|---|---|---|---|
| CL1 | Deactivate the test subscriptions with `PATCH` `{"active": false}` or `DELETE` (both deactivate) | Only test subscriptions are deactivated. The seed is untouched | Two `subscription.deactivate` audit lines, `success`, at 22:11:46. Which subscriptions is not confirmed | Partly observed |
| CL2 | Wait until no notification is `pending` or `retrying` | `0` | | Not run |
| CL3 | Stop the service and close the **Receiver 9200** and **Tunnel cloudflared** windows | Stopped | | Not run |
| CL4 | `docker compose stop` from this checkout | Only this checkout's containers stop | | Not run |

## 8. Open items from the observed run

- [ ] Count of `/fail 500` lines in the receiver window (C8).
- [ ] State of the `/fail` notification in the database after attempt 5 (C9): status, `last_error`, and five rows in `delivery_attempts`.
- [ ] Grafana after the run: `notification_dead_letter_total` and the four rules (C10, G1, G2).
- [ ] The startup profile line of the run (C2, C11).
- [ ] Which two subscriptions were deactivated at 22:11:46, and whether the subscription outside the test is one of them (CL1).
- [ ] Cause of the `HikariPool` warnings at 22:16. No attempt in the log is affected.

## 9. Record

- [ ] Replace the "expected" wording in the runbook with observed values, where a run confirmed them.
- [ ] List what was not verified, with its reason.
