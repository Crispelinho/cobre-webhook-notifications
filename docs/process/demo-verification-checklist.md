# Demo verification checklist

Run this list with [the local HTTPS delivery runbook](local-https-delivery-runbook.md#demo-scenarios). Record each observed value in the **Observed** column. Do not replace an "expected" statement in the runbook until a run confirms it. Anything not run stays marked as not verified.

Use a new `event_id` for every ingestion. The seed ids `EVT001` to `EVT010` return `409`.

## 0. Pre-flight

- [ ] The database and Kafka are the ones the service uses. Record the JDBC URL and `spring.kafka.bootstrap-servers` from the run configuration.
- [ ] Flyway applied V1 to V4: `select version from flyway_schema_history order by installed_rank`.
- [ ] The service started with the intended profile (`local` or `local,demo`) and `DEMO_WEBHOOK_SECRET` set. The log shows `Sample data loaded: 10 notification events, 3 seed subscriptions`.
- [ ] `WEBHOOK_ALLOWED_HOSTS` matches the scenario (default for A and B, the tunnel host for C). Record the exact value.
- [ ] `CLIENT_TOKEN` and `PRODUCER_TOKEN` were generated with `./gradlew devToken` from the directory the service reads its `public.pem` from. A `403` (not `401`) on a producer token used on `/subscriptions` shows that the signature was accepted.
- [ ] Baseline recorded: notifications by `delivery_status`, the Grafana values, and the state of the four alert rules.
- [ ] Other active test subscriptions are deactivated, or their effect is accounted for. They receive every matching event.

## 1. Smoke tests

| # | Request | Expected | Observed |
|---|---|---|---|
| S1 | `GET /subscriptions` without a token | `401` | |
| S2 | `GET /subscriptions` with `CLIENT_TOKEN` | `200`, with `data` | |
| S3 | `POST /subscriptions` with `PRODUCER_TOKEN` | `403` | `403` observed |

## 2. Scenario A: failures without a tunnel

| Check | Expected | Observed |
|---|---|---|
| `POST /events` with `credit_deposit`, `CLIENT001`, `DEMO-A-0001` | `202` | |
| Notification for `SUB-CLIENT001` | `failed`, `last_error` `invalid_destination`, `attempts` 1 | |
| `delivery_attempts` | One row, round 1, attempt 1, `http_status` null, `error_type` `invalid_destination` | |
| Service log | `Destination of notification event <id> rejected: <reason>`. The reason is `host does not resolve` or `host resolves to a non-public address` | |
| Grafana | Outcome `permanent`. Alerts `cobre-failed-rate` and `cobre-attempt-failure-rate` firing after 1 min. `cobre-dead-letter` does not move | |

## 3. Scenario B: rejected destinations

Each request returns `400` with the same generic `detail`. The reason is only in the service log. No new row appears in `subscriptions`.

| ID | `url` | Expected log reason | Observed |
|---|---|---|---|
| B1 | `http://example.org/cobre` | `only https is allowed` | |
| B2 | `https://example.org:8443/cobre` | `only port 443 is allowed` | |
| B3 | `https://example.org/cobre` | `host is not in the allowlist` | |
| B4 | `https://10.0.0.5/cobre` | `host is not in the allowlist` | |
| B5 | `https://webhook.example.com/cobre` | `host resolves to a non-public address` or `host does not resolve`, depending on DNS | |

Run B1 to B5 before Scenario C, while `WEBHOOK_ALLOWED_HOSTS` is still the default. Otherwise B5 gives `host is not in the allowlist`.

## 4. Scenario C: successes and retries with a tunnel

- [ ] `start-local-webhook-tunnel.bat` printed the host. Record it: `<host>`.
- [ ] The service restarted with `WEBHOOK_ALLOWED_HOSTS=<host>` and `SPRING_PROFILES_ACTIVE=local,demo`.
- [ ] `POST /subscriptions` for `https://<host>/ok` and `https://<host>/fail`, both with `payment_created`: `201`. Save both secrets.
- [ ] `POST /events` with `payment_created`, `CLIENT001`, `DEMO-C-0001`: `202` with two `notification_event_ids`.

| Check | Expected | Observed |
|---|---|---|
| `/ok` notification | `completed`, one attempt, `http_status` 200 | |
| `/ok` receiver line | `/ok 200 <n>` once | |
| `/fail` notification | `failed`, `last_error` `retries_exhausted`, five attempts with `http_status` 500 | |
| `/fail` receiver lines | `/fail 500 <n>` five times | |
| Timing of `/fail` | Attempts 1 to 3 at about +0 s, +11 s, +42 s (measured earlier). Attempts 4 and 5 follow the `demo` delays (not measured) | |
| Grafana | `success` and `retryable` outcomes. `cobre-attempt-failure-rate` firing. `cobre-dead-letter` does not move | |

**Isolation check.** Send `payment_created` for `CLIENT002`. Expected: no notification for the `CLIENT001` subscriptions. Verify the response body and the database. The runbook does not yet document the `202` body for an event with no matching subscription.

## 5. Replay

| Check | Expected | Observed |
|---|---|---|
| `GET /notification_events/{id}` for a `completed` notification | `200`, full detail with `attempts` | |
| `POST /notification_events/{id}/replay` for a `completed` notification | `409`, `Only a failed notification event can be replayed.`, audit `REJECTED` | `409` observed |
| `POST /notification_events/{id}/replay` for a `failed` notification | `202`, `round` 2, `delivery_status` | |
| `GET /notification_events?delivery_status=failed` | Only failed notifications for the client | |

For the `202` you need a failed notification and a way to make the destination succeed. Either re-run the allowlist and tunnel steps for an `invalid_destination` notification, or accept that the replay of a `/fail` notification only checks the response shape.

## 6. Grafana

- [ ] Dashboard **Notification delivery** (`cobre-delivery`): every panel is shown with the values recorded in the baseline.
- [ ] Folder **Notification delivery**: the four rules `cobre-failed-rate`, `cobre-attempt-failure-rate`, `cobre-dead-letter` and `cobre-outbox-age` are in the expected state after each scenario.

## 7. Cleanup

- [ ] Deactivate only the test subscriptions: `PATCH /subscriptions/<id>` with `{"active": false}`. Do not touch the seed subscriptions.
- [ ] Wait until no notification is `pending` or `retrying`.
- [ ] Stop the service and close the **Receiver 9200** and **Tunnel cloudflared** windows.
- [ ] Stop only this checkout's containers, with `docker compose stop` from the checkout root.

## 8. Record

- [ ] Replace the "expected" wording in the runbook with the observed values, where a run confirmed them.
- [ ] List what was not verified, with its reason.
