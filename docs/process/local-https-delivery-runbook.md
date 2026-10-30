# Runbook: real HTTPS delivery from a local run

This runbook shows how to see a webhook arrive over real HTTPS while the service runs on your machine. It uses the production delivery path unchanged: the destination policy, the pinned address, TLS verification and the HMAC signature. No flag disables any check.

## Why a tunnel is needed

The destination policy ([`DestinationPolicy`](../../src/main/java/com/cobre/notifications/shared/security/DestinationPolicy.java)) accepts a webhook only when all of these hold:

- the scheme is `https`;
- the port is 443 (explicit or implied);
- the host is in `WEBHOOK_ALLOWED_HOSTS`;
- every address the host resolves to is public.

A receiver on `localhost`, `127.0.0.1` or a private address fails the last rule, and a receiver on any other port fails the port rule. A tunnel solves both problems: it gives the receiver a public `https://` name on port 443 with a valid certificate, and it forwards the traffic to a plain HTTP server on your machine.

## Prerequisites

- JDK 25, with `JAVA_HOME` pointing to it (the Gradle toolchain requires it).
- Docker running (the app starts PostgreSQL and Kafka through `compose.yaml`).
- A tunnel client: [ngrok](https://ngrok.com/) or [cloudflared](https://github.com/cloudflare/cloudflared), either one. The steps below were verified with `cloudflared tunnel --url`, which needs no account.
- Free ports. The examples use `8081` for the service (the default in `application.yaml`) and `9000` for the receiver. If `8081` is taken, set `SERVER_PORT` to another value and use it in the `curl` commands.
- Two development tokens, both signed with `./gradlew devToken`:

  ```bash
  ./gradlew devToken --args="CLIENT001 subscriptions:read subscriptions:write notifications:read notifications:replay"
  ```

  ```bash
  ./gradlew devToken --args="PRODUCER001 events:write"
  ```

  The producer token carries `events:write` only. Client tokens never carry that scope, which is what keeps the ingestion endpoint internal (see [`EventController`](../../src/main/java/com/cobre/notifications/events/adapters/in/rest/EventController.java)).

## Step 1: start a receiver

Run any HTTP server on a local port. It must:

1. accept `POST` requests;
2. read the raw body bytes, before any JSON parsing;
3. check the `Cobre-Signature` header (see [Verifying the signature](#verifying-the-signature)).

Use port `9000` in the examples below.

## Step 2: expose the receiver over HTTPS

```bash
ngrok http 9000
```

or

```bash
cloudflared tunnel --url http://localhost:9000
```

Copy the public host that the tool prints (for example `abc123.ngrok-free.app`). Use the **host only**: no scheme, no port, no path. The host changes on every run with free plans, so repeat steps 3 to 5 each time you start a tunnel.

### Windows shortcut: `start-local-webhook-tunnel.bat`

On Windows, `start-local-webhook-tunnel.bat` in the project root does steps 1, 2 and 3 for you. It opens the receiver (`scripts/test-webhook-receiver.py`, port 9200) and a cloudflared quick tunnel to it, reads the host from the tunnel output, and opens a console with `WEBHOOK_ALLOWED_HOSTS` already set. Run it from the project root:

```powershell
.\start-local-webhook-tunnel.bat
```

Then, in the console it opens, start the service with the profile you use (step 3):

```powershell
gradlew.bat bootRun
```

Keep the `Receiver 9200` and `Tunnel cloudflared` windows open for the whole test. Closing either one stops the test. A quick tunnel gives a new host on every run, so run the script again if you restart it.

The script uses port 9200 so it does not collide with the port 9000 of the examples above. Use this host in step 3 instead of `abc123.ngrok-free.app`.

## Step 3: start the service with that host allowed

The `local` profile enables the development JWT issuer and the seed. The seed needs `DEMO_WEBHOOK_SECRET`, or startup fails with a message that names it. `WEBHOOK_ALLOWED_HOSTS` overrides the profile default (`webhook.example.com`):

```bash
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8081
DEMO_WEBHOOK_SECRET=whsec_local_throwaway_value
WEBHOOK_ALLOWED_HOSTS=abc123.ngrok-free.app
DEMO_WEBHOOK_SECRET=whsec_<throwaway value>
./gradlew bootRun
```

`DEMO_WEBHOOK_SECRET` is required with the `local` profile: the seed is enabled there, and `SampleDataSeeder` fails startup when the variable is empty, so the service does not start without it. It is the signing secret of the seed subscriptions; use any throwaway `whsec_` value.

Prefix an entry with `.` only if you want its subdomains too: `.ngrok-free.app` would allow every ngrok host. For a demo, the exact host is the safer choice.

To see retries quickly, add `SPRING_PROFILES_ACTIVE=local,demo`. The `demo` profile uses the delays `PT10S,PT30S,PT1M,PT2M` instead of the 30 s to 1 h backoff.

If `docker compose` cannot bind the fixed host port `9092` for Kafka (PostgreSQL is published without a fixed host port in `compose.yaml`, so its host port is random; find it with `docker compose port postgres 5432`), another Compose stack already holds them. This happens when two checkouts run the stack at the same time. Do not stop the other stack; start your own containers on free ports and point the app at them:

```bash
SPRING_DOCKER_COMPOSE_ENABLED=false
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/notifications
SPRING_DATASOURCE_USERNAME=notifications
SPRING_DATASOURCE_PASSWORD=notifications
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092
```

For Kafka, the container must advertise the port you publish (`KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:19092`); otherwise the client connects to the advertised port and fails.

## Step 4: create a subscription

The signing secret is returned **once**, in this response. Save it.

```bash
curl -s -X POST http://localhost:8081/subscriptions \
  -H "Authorization: Bearer $CLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://abc123.ngrok-free.app/cobre", "event_types": ["payment_created"]}'
```

The response includes `secret` and the subscription id. The URL must have no port, so `https://abc123.ngrok-free.app/cobre` is valid and `https://abc123.ngrok-free.app:8443/cobre` is not.

## Step 5: send an event

```bash
curl -s -X POST http://localhost:8081/events \
  -H "Authorization: Bearer $PRODUCER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event_id": "evt-0001", "client_id": "CLIENT001", "event_type": "payment_created", "content": "{\"amount\": 100}"}'
```

The endpoint answers `202 Accepted`. Delivery happens asynchronously: the outbox relay publishes to Kafka and the dispatch consumer sends the webhook.

Check the state of the delivery with the notifications API (`GET /notification_events`, with a client token that has `notifications:read`).

## What the receiver gets

- Method `POST`, `Content-Type: application/json`.
- `Cobre-Notification-Id`: the notification id.
- `Cobre-Signature`: `t=<unix seconds>,v1=<hex>`.
- Body, exactly as sent:

  ```json
  {
    "notification_event_id": "...",
    "event_id": "evt-0001",
    "event_type": "payment_created",
    "content": "{\"amount\": 100}",
    "created_at": "..."
  }
  ```

A `2xx` status marks the delivery as completed. Any other status, or a timeout, is retried. A round has five attempts (the first one included). With the `demo` profile the configured delays are `PT10S`, `PT30S`, `PT1M` and `PT2M` between attempts. The second run in [Verified run](#verified-run) measured the first three attempts at +0 s, +11 s and +42 s. A later run did not match the configured delays; see [Observed run](#observed-run-2026-10-29). When the attempts run out, the notification ends as `failed`. Only a replay starts a new round (`POST /notification_events/{id}/replay`).

## Verifying the signature

The signed string is `"<t>." + <raw body bytes>`, keyed with the secret as UTF-8 bytes. The `v1` value is the lowercase hex of HMAC-SHA256. This is the algorithm in [`WebhookSigner`](../../src/main/java/com/cobre/notifications/shared/crypto/WebhookSigner.java).

```text
header   = request.headers["Cobre-Signature"]        # "t=1730000000,v1=ab..."
t        = header.part("t")
v1       = header.part("v1")
expected = hex(HMAC_SHA256(key = secret_utf8, message = t + "." + raw_body))
accept   = constant_time_equal(v1, expected)
```

Compare with a constant-time function. Reject the request if `t` is more than a few minutes away from your clock, because a replayed request would otherwise pass.

## Troubleshooting

| Symptom | Likely cause | Check |
|---|---|---|
| `POST /subscriptions` returns 400 with `url is not an allowed destination` | A rule of the destination policy failed. The API message is deliberately generic | The reason is only in the application log (`Subscription destination rejected: <reason>`). The reasons are: `only https is allowed`, `only port 443 is allowed`, `host is not in the allowlist`, `host does not resolve`, `host resolves to a non-public address`. |
| Delivery ends in `INVALID_DESTINATION` (one attempt, no retries) | The host was valid when the subscription was created but not when the webhook was sent, because `WEBHOOK_ALLOWED_HOSTS` no longer contains it | Put the host back in `WEBHOOK_ALLOWED_HOSTS`, restart, and replay the notification. The replay keeps the URL stored with the notification, so the subscription does not change. |
| The tunnel host changed and the old subscription URL is dead | A subscription's URL cannot be changed: `PATCH` accepts only `active` and `event_types` | Create a new subscription with the new host. Deactivate or delete the old one. |
| Seed subscriptions fail with `dns_error` (five attempts, then `failed`) if the name does not resolve, or `invalid_destination` (one attempt) if it resolves to a non-public address | `webhook.example.com` does not resolve, or resolves to a private address | Expected. The seed is for the data model, not for live delivery. |
| Receiver gets no request | The tunnel is not forwarding to port 9000, or the subscription URL has a different host | Open the tunnel's inspector page and check the request log. |
| Signature check fails | The body was parsed and re-serialized before checking, or the secret is not the one returned on creation | Hash the raw bytes. The secret cannot be recovered: create a new subscription if you lost it. |
| Startup fails with `Bind for 0.0.0.0:9092 failed: port is already allocated` | Another Compose stack (for example, the main checkout's) or leftover Testcontainers containers hold the port | Do not stop another project's stack. Use the dedicated containers in [Step 3](#step-3-start-the-service-with-that-host-allowed). Check with `docker ps`. |
| `Port 8081 was already in use` | Another local app runs on the port | Set `SERVER_PORT` to a free port. |
| `429 Too Many Requests` on `/subscriptions` or `/notification_events` | The per-client limit of 100 requests per minute (`app.rate-limit.requests-per-minute`) is exhausted | Wait for the `Retry-After` time, or raise the limit for local runs. `POST /events` is not limited. |
| Receiver gets a rejection for `127.0.0.1` with `host is not in the allowlist` | The allowlist check runs before the public-address check, so a loopback host fails on the allowlist first | Expected. The public-address rule is only reached for an allowlisted host. It is covered by the unit tests with a fake resolver (`DestinationPolicyTests`), not by this manual run. |

## Verified run

Run on 2026-10-29 with `cloudflared` (quick tunnel), the `local` profile on port 8090 (8081 was taken), and the dedicated containers from the previous section:

| Step | Result |
|---|---|
| Public HTTPS POST reaches the local receiver | Received, `/cobre` |
| `POST /subscriptions` with an allowlisted `https://<tunnel>/cobre` URL | `201`, secret returned once |
| `POST /events` with the producer token | `202`, one notification created |
| Webhook delivery | Received; `Cobre-Signature` verified with the algorithm above; body matches the documented fields |
| Notification state in `GET /notification_events` | `completed` |
| Port 8443 on the tunnel host | `400`, generic message; reason `only port 443 is allowed` in the log |
| Host not in the allowlist | `400`, generic message; reason `host is not in the allowlist` in the log |
| `https://127.0.0.1/...` | `400`, generic message; reason `host is not in the allowlist` in the log |

Second run on the same day, with the `local,demo` profiles, a failing receiver path (`/fail` answers `500`) and a second tunnel:

| Step | Result |
|---|---|
| Success subscription and failing subscription, same event | `201` and `201`; event `202` |
| Success subscription | `completed` on the first attempt |
| Failing subscription | Five attempts; the first three at +0 s, +11 s and +42 s (measured). The last two were all received before the run ended; the notification ends `failed` |
| Allowlist without the tunnel host, then a new event | Both notifications `failed` after one attempt with `invalid_destination`; the log says `host is not in the allowlist` |
| Replay of the `invalid_destination` notification, with the host back in the allowlist | New round; `completed` with `200`; the receiver got the same `notification_event_id` |
| Signature check with one byte of the body changed | Rejected; the genuine body still verifies |
| `./gradlew test` on this branch | 413 tests, 0 failures, 0 errors |
| CI on this PR | `Build and test`, `Dependency review` and `Validate OpenSpec` pass |

One request reached the receiver on `/ok` with a 4-byte body that the test did not send. It is probably a probe from the tunnel edge; it was not investigated.

Third run, on `develop` at `ed41543` (the code this branch is based on), with the same profiles and receiver:

| Step | Result |
|---|---|
| `./gradlew test` | 446 tests, 0 failures, 0 errors |
| Success and failing subscriptions, same event | `201`, `201`, event `202` |
| Success subscription | Delivered; signature verified; `completed` |
| Failing subscription | Five attempts; ends `failed` |
| Host removed from the allowlist, new event | Both notifications `failed` after one attempt, `invalid_destination` |
| Replay of the `invalid_destination` notification, host restored | Round 2; `completed` with `200` |
| Changed body byte | Signature rejected |
| 110 requests to `GET /subscriptions` in one minute | 100 × `200`, then 10 × `429` |

The first run of this branch on the older base had shown the same behaviour. This run is the one that matches the code on `develop`.

Not exercised: ngrok (not installed on the test machine), the public-address rule against real DNS, and the exact timestamps of the fourth and fifth attempts in that run (only their count was measured; the [observed run](#observed-run-2026-10-29) has them).

## API reference

Every endpoint needs `Authorization: Bearer <token>`. Each route declares the scope it needs (`SecurityConfig`). Errors use the RFC 9457 `ProblemDetail` format. A token with the wrong scope gets `403` with `WWW-Authenticate: Bearer error="insufficient_scope"`. That is why a producer token on `/subscriptions` answers `403`, not `401`: the signature was accepted (observed).

| Method and path | Scope | Success | Other responses |
|---|---|---|---|
| `POST /events` | `events:write` | `202` | `409` if `event_id` exists; `403` wrong scope |
| `POST /subscriptions` | `subscriptions:write` | `201` | `400` generic destination message; `403` wrong scope |
| `GET /subscriptions` | `subscriptions:read` | `200` | `429` after 100 requests per minute |
| `GET /subscriptions/{subscription_id}` | `subscriptions:read` | `200` | `404` `Subscription not found.` |
| `PATCH /subscriptions/{subscription_id}` | `subscriptions:write` | `200` | `404` |
| `DELETE /subscriptions/{subscription_id}` | `subscriptions:write` | `204` | `404` |
| `GET /notification_events` | `notifications:read` | `200` | `400` invalid filter or paging; `429` |
| `GET /notification_events/{notification_event_id}` | `notifications:read` | `200` | `404` `Notification event not found.` |
| `POST /notification_events/{notification_event_id}/replay` | `notifications:replay` | `202` | `404`; `409` `Only a failed notification event can be replayed.` |

The `404` for a notification of another client has the same text as a missing one, so an id cannot be used to probe other clients.

### `POST /events`

Request:

```json
{"event_id": "DEMO-C-0001", "client_id": "CLIENT001", "event_type": "payment_created", "content": "{\"amount\": 100}"}
```

Response `202`:

```json
{"event_id": "DEMO-C-0001", "notification_event_ids": ["<id>", "<id>"]}
```

The service creates one notification per **active** subscription of `client_id` whose `event_types` contain `event_type`. The observed run got three ids, because an active subscription outside the test also matched (see [Observed run](#observed-run-2026-10-29)). Check the list of active subscriptions before each test.

`event_id` is unique across all clients. A second call with the same id gives `409` and `An event with this event_id already exists.` (observed). The `client_id` names the client the event is about. Authorization rests on the producer scope, so a client token cannot ingest.

### `POST /subscriptions`

Request: `{"url": "https://<host>/<path>", "event_types": ["payment_created"]}`. The URL goes through the destination policy ([Scenario B](#scenario-b-rejected-destinations)).

Response `201`, with a `Location` header `/subscriptions/<subscription_id>`:

```json
{"subscription_id": "<id>", "url": "https://<host>/<path>", "event_types": ["payment_created"], "active": true, "created_at": "<instant>", "secret": "whsec_..."}
```

`secret` is returned **only in this response** (the `CreatedSubscriptionResponse` type). Save it. The later reads do not have it.

### `GET /subscriptions`

Response `200`:

```json
{"data": [{"subscription_id": "<id>", "url": "...", "event_types": ["..."], "active": true, "created_at": "<instant>"}]}
```

There is no pagination: the response holds every subscription of the client. No `secret`. Not verified: whether inactive subscriptions are included.

### `GET /subscriptions/{subscription_id}`

Response `200` with one subscription (same fields as the list, no `secret`). `404` with `Subscription not found.` if the id is unknown.

### `PATCH /subscriptions/{subscription_id}`

Request: `{"active": false}`, or `{"event_types": ["..."]}`, or both. At least one field is needed. The `url` cannot change. Response `200` with the subscription. `404` if the id is unknown.

### `DELETE /subscriptions/{subscription_id}`

Response `204`, no body. It calls the deactivate use case, so the subscription stops receiving events. Use it as the cleanup step. The `active: false` form of `PATCH` does the same job.

### `GET /notification_events`

Query parameters:

| Name | Default | Notes |
|---|---|---|
| `delivery_status` | none | `pending`, `retrying`, `completed` or `failed` |
| `created_from`, `created_to` | none | ISO-8601 instant with a zone, for example `2024-03-15T10:00:00Z`. A bare date such as `2024-03-15` is rejected with `400`. `created_from` is inclusive, `created_to` exclusive. `created_from` after `created_to` is rejected |
| `page` | `0` | Starts at 0 |
| `size` | `20` | Maximum 100 |

Response `200`:

```json
{"data": [{"notification_event_id": "<id>", "event_id": "...", "event_type": "...", "content": "...", "delivery_status": "failed", "created_at": "<instant>", "delivery_date": null, "delivered_at": null}], "page": 0, "size": 20, "total_elements": 0, "total_pages": 0}
```

To read every page, request `page` from 0 to `total_pages - 1`. Only the notifications of the token's client come back.

### `GET /notification_events/{notification_event_id}`

Response `200`: the same fields as the list, plus `attempts`, one object per attempt:

```json
{"round": 1, "attempt_number": 1, "attempted_at": "<instant>", "http_status": 200, "error_type": null, "latency_ms": 120}
```

`http_status` is null when there was no response, and `error_type` is set then (`invalid_destination`, `timeout`, and so on).

### `POST /notification_events/{notification_event_id}/replay`

Response `202`:

```json
{"notification_event_id": "<id>", "round": 2, "delivery_status": "pending"}
```

Only a `failed` notification can be replayed. Any other status gives `409` with `Only a failed notification event can be replayed.`, and the audit log records outcome `REJECTED` (observed five times in the observed run). A replay reuses the URL stored with the notification, so changing the subscription does not change a replay.

## Demo scenarios

This section is the step-by-step guide for the demo. It runs three scenarios on one service instance, in this order:

- **A**: failures without a tunnel (the seed subscription points to a host that cannot receive webhooks).
- **B**: rejected destinations, at subscription time.
- **C**: successes and retries, with a tunnel.

Then it shows what to check in Grafana and how to clean up.

Rules for every scenario:

- The destination policy stays on. No scenario disables or relaxes it. The only settings that change are `WEBHOOK_ALLOWED_HOSTS` and `SPRING_PROFILES_ACTIVE`.
- Use a new `event_id` for every event. The seed event ids `EVT001` to `EVT010` already exist and return `409`.
- Run the scenarios in the order A, B, C. Scenario C sets `WEBHOOK_ALLOWED_HOSTS` to the tunnel host, which removes `webhook.example.com` from the allowlist, so Scenario A and the `webhook.example.com` case in Scenario B only behave as described with the default allowlist.
- Record the baseline (see [Baseline](#baseline)) before each scenario.

Variables used in the commands below (set them in the shell):

```bash
CLIENT_TOKEN=$(./gradlew -q devToken --args="CLIENT001 subscriptions:read subscriptions:write notifications:read notifications:replay")
PRODUCER_TOKEN=$(./gradlew -q devToken --args="PRODUCER001 events:write")
```

Check that both values start with `eyJ` (a JWT header) before you continue. The `-q` flag is not verified on your machine; if the output includes Gradle messages, copy only the token line.

### Where WEBHOOK_ALLOWED_HOSTS comes from

The tunnel host is a per-run value. A quick tunnel from `cloudflared` (and `start-local-webhook-tunnel.bat`) gets a new host on every start, and the old host stops working when its tunnel stops. So the host is never written into a versioned file. `application.yaml` reads it from the environment (`${WEBHOOK_ALLOWED_HOSTS:}`), and the `local` profile only adds the seed host by default.

Set it once per run, in the IntelliJ run configuration of the service:

1. Open **Run | Edit Configurations** and select the service.
2. In **Environment variables**, set `WEBHOOK_ALLOWED_HOSTS=<host printed by the tunnel>`, with no scheme and no port.
3. Keep the other variables (`SPRING_PROFILES_ACTIVE`, `DEMO_WEBHOOK_SECRET`) in the same field.
4. Restart the run configuration. The value is read at startup only.

If the restart does not pick the value up, `POST /subscriptions` answers `400` for the tunnel URL and the service logs `Subscription destination rejected: host is not in the allowlist`. That line is the check: it means the process did not receive the variable.

Do not put a tunnel host in `application-local.yaml` or in any other versioned file. The same goes for the host of a previous run: once its tunnel has stopped, it is only history.

### Preparation

1. Start PostgreSQL, Kafka and Grafana from the checkout root:

   ```bash
   docker compose up -d postgres kafka grafana-lgtm
   docker compose ps
   ```

   Expected: `postgres` is `healthy` and `kafka` and `grafana-lgtm` are `running`. Kafka answers on `9092`, Grafana on `3000`. The PostgreSQL host port is random: `docker compose port postgres 5432`. Spring Boot's Docker Compose support also starts services during `bootRun` (the dependency is `developmentOnly`), so the containers may already be up when the service starts. This is not verified for `grafana-lgtm` in this repository.

2. Start the service with the `local` profile, as in [Step 3](#step-3-start-the-service-with-that-host-allowed), without `WEBHOOK_ALLOWED_HOSTS` for Scenarios A and B:

   ```bash
   SPRING_PROFILES_ACTIVE=local
   SERVER_PORT=8081
   DEMO_WEBHOOK_SECRET=whsec_<throwaway value>
   ./gradlew bootRun
   ```

   Expected in the log: `Sample data loaded: 10 notification events, 3 seed subscriptions` (from `SampleDataSeeder`). The seed contents are in [section 2.1 of the test plan](seed-and-demo-test-plan.md#21-seed).

3. Check health. `/actuator/health` needs no token (`SecurityConfig`):

   ```bash
   curl -s http://localhost:8081/actuator/health
   ```

   Expected: `"status":"UP"`. The response body was not checked by running the service.

4. Open Grafana at `http://localhost:3000` and the dashboard **Notification delivery** (see [Verification in Grafana](#verification-in-grafana)). The Grafana login is not in this repository; this is not verified.

### Baseline

The dead-letter counter also moves for reasons unrelated to the demo, so take a baseline before each scenario and compare against it, not against zero.

1. Count the notifications by status:

   ```sql
   SELECT delivery_status, count(*) FROM notifications GROUP BY delivery_status ORDER BY delivery_status;
   ```

2. Record these Grafana values:
   - **Dead-letter messages (last hour)**: the current value.
   - **Failed attempt ratio (5 min, alert above 5%)**: the current value.
   - **Oldest unpublished outbox row**: should be near zero.
   - The state of the four alert rules in folder **Notification delivery**: normal or firing.

3. Wait at least 2 minutes after any earlier dead-letter message before you take the baseline. The rule **Message reached the dead-letter topic** is `increase(notification_dead_letter_total[2m]) > 0` with `for: 0s`, so it fires for two minutes after any message reaches the topic (`cobre-dead-letter` in `docker/grafana/provisioning/alerting/notification-delivery-alerts.yaml`).

### Scenario A: failures without a tunnel

**Why it fails.** The seed subscription `SUB-CLIENT001` accepts four event types, the ones of CLIENT001's seed events: `credit_card_payment` (`EVT001`), `debit_card_withdrawal` (`EVT002`), `credit_deposit` (`EVT007`) and `debit_subscription` (`EVT010`) (`src/main/resources/seed/notification_events.json`). `SampleDataSeeder` builds that list from the events, one set per client. The list was read from the database of the main checkout on 2026-10-29; check it again if the seed changes. Its URL is `https://webhook.example.com/CLIENT001`. The seeder inserts this row directly, not through `POST /subscriptions`, so the policy never checks it at registration. The policy checks it at delivery time, and the `local` profile allows `webhook.example.com` by default.

**Action.** Keep `WEBHOOK_ALLOWED_HOSTS` unset:

```bash
curl -s -X POST http://localhost:8081/events \
  -H "Authorization: Bearer $PRODUCER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event_id": "DEMO-A-0001", "client_id": "CLIENT001", "event_type": "credit_deposit", "content": "{\"amount\": 250}"}'
```

Expected: `202`, with one notification for `SUB-CLIENT001`.

**What to check in the database.** After a few seconds:

- If the name does not resolve: `notifications` ends `failed` with `last_error` `dns_error` and `attempts` 5, `round` 1. `delivery_attempts` has five rows with `error_type` `dns_error` and `http_status` NULL.
- If the name resolves to a private address: `notifications` ends `failed` with `last_error` `invalid_destination` and `attempts` 1. `delivery_attempts` has one row with `error_type` `invalid_destination`.

Why: `HttpsWebhookSender.send` handles the two cases differently. A `DestinationResolutionException` (the name does not resolve) becomes `TransportError.DNS`, which is `dns_error`, and `DeliveryOutcome.classify` maps it to `RETRYABLE`, so the round uses its five attempts (decision D54, PR #32). A `RejectedDestinationException` (the name resolves to a non-public address) becomes `INVALID_DESTINATION`, which is `PERMANENT`, with one attempt. In both cases no request is sent.

**What to check in the log.** The service logs at WARN `Destination of notification event <id> did not resolve: <reason>` for a DNS failure, or `Destination of notification event <id> rejected: <reason>` for a rejected host. The reason depends on DNS:

- `host does not resolve`: the name has no address record.
- `host resolves to a non-public address`: the name resolves to a private or reserved address. On the machine where this runbook was written, the local resolver answered `192.168.5.1` for `webhook.example.com`, and a public resolver (`8.8.8.8`) returned no address for it on 2026-10-29. Your result may differ. The first ends in `dns_error` (retried five times). The second ends in `invalid_destination` (one attempt).

**What to check on the dashboard and in the alert rules.**

- **Delivery attempts per second by outcome**: a series for `permanent` if the name resolves to a private address. If it does not resolve, the series is `retryable`, five times per round.
- **Failed attempt ratio (5 min)**: rises. With few events, one failure is a large share of the window.
- **Failed notification rate above 5%** (`cobre-failed-rate`): firing after its 1-minute `for` period.
- **Attempt failure rate including retries above 5%** (`cobre-attempt-failure-rate`): firing after its 1-minute `for` period.
- **Message reached the dead-letter topic** (`cobre-dead-letter`): should **not** move for a `PERMANENT` outcome, because `DispatchConsumer` throws only for `RETRY` and a `PERMANENT` outcome does not throw. A host that does not resolve gives `dns_error`, which is `RETRYABLE`. In that case the message goes to `notification.dispatch-dlt` only after the last retryable attempt (the fifth), so the counter moves and the alert fires for two minutes, as in Scenario C.
- **Oldest unpublished outbox row older than 60 s** (`cobre-outbox-age`): should stay normal.

### Scenario B: rejected destinations

Each request registers a subscription with the `CLIENT001` client token. Expected in every case: `400`, a `ProblemDetail` whose `detail` is the same generic text, and no new row in `subscriptions`:

```text
url is not an allowed destination: it must use https, be on an allowlisted host and resolve to public addresses
```

The API never returns the reason. The reason is only in the service log, as `Subscription destination rejected: <reason>` (`PolicyDestinationValidator`, logged at INFO). Read the reason in the `bootRun` console.

The policy checks in this order (`DestinationPolicy.check`), so the first failing rule gives the reason:

1. scheme must be `https`;
2. port must be 443 (explicit or implied);
3. no user info;
4. the host must be in the allowlist;
5. every resolved address must be public.

| ID | Request `url` | HTTP | Log reason | Why this reason |
|---|---|---|---|---|
| B1 | `http://example.org/cobre` | 400 | `only https is allowed` | Rule 1 fails first. |
| B2 | `https://example.org:8443/cobre` | 400 | `only port 443 is allowed` | Rule 2 runs before the allowlist, so the host does not matter. |
| B3 | `https://example.org/cobre` | 400 | `host is not in the allowlist` | `example.org` is not in the default allowlist (`webhook.example.com`). |
| B4 | `https://10.0.0.5/cobre` | 400 | `host is not in the allowlist` | Rule 4 runs before DNS. A private IP literal fails here, not on rule 5. |
| B5 | `https://webhook.example.com/cobre` | 400 | `host resolves to a non-public address` or `host does not resolve` | The host is allowlisted by default, so the policy resolves it. The reason depends on DNS (see Scenario A). |

Example request (B3):

```bash
curl -s -X POST http://localhost:8081/subscriptions \
  -H "Authorization: Bearer $CLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.org/cobre", "event_types": ["payment_created"]}'
```

Each rejected request is audited with outcome `REJECTED` (`SubscriptionService`). The rule that a rejected host with a non-public address cannot be reached through the allowlist is covered by `DestinationPolicyTests`, not by this manual run.

### Scenario C: successes and retries, with a tunnel

**C1. Start the receiver and the tunnel.** From the project root, in PowerShell:

```powershell
.\start-local-webhook-tunnel.bat
```

The script opens two windows, **Receiver 9200** (`scripts/test-webhook-receiver.py`) and **Tunnel cloudflared**, waits up to 60 s for the tunnel URL, and prints `WEBHOOK_ALLOWED_HOSTS=<host>.trycloudflare.com`. It also opens a **Service** console that inherits that variable. The script needs `python` and `cloudflared` on `PATH`; this is not verified on every machine. If it prints `Tunnel URL not found`, read the **Tunnel cloudflared** window.

Copy the host, without `https://`. You need it in the next steps.

**C2. Restart the service with the tunnel host allowed.** Stop the Scenario A/B service with Ctrl+C. In the **Service** console, which already has `WEBHOOK_ALLOWED_HOSTS`, set the profile and the seed secret, then start:

```powershell
set SPRING_PROFILES_ACTIVE=local,demo
set DEMO_WEBHOOK_SECRET=whsec_<the same value as before>
gradlew.bat bootRun
```

The `demo` profile sets the retry delays to `PT10S,PT30S,PT1M,PT2M` (`application-demo.yaml`).

**C3. Create two subscriptions.** Use the host from C1:

```bash
TUNNEL_HOST=<host from C1>
curl -s -X POST http://localhost:8081/subscriptions -H "Authorization: Bearer $CLIENT_TOKEN" -H "Content-Type: application/json" -d "{\"url\": \"https://$TUNNEL_HOST/ok\", \"event_types\": [\"payment_created\"]}"
curl -s -X POST http://localhost:8081/subscriptions -H "Authorization: Bearer $CLIENT_TOKEN" -H "Content-Type: application/json" -d "{\"url\": \"https://$TUNNEL_HOST/fail\", \"event_types\": [\"payment_created\"]}"
```

Expected: `201` for both, each with a `secret` returned once. `payment_created` is not an event type of any seed event, so the seed subscriptions do not receive these events.

The test receiver answers `200` for every path and `500` for paths ending in `/fail`. It prints `<path> <status> <body length>`. It does **not** check the `Cobre-Signature` header, so this script does not demonstrate signature verification. The runbook's [Verifying the signature](#verifying-the-signature) section covers that.

**C4. Send one event.**

```bash
curl -s -X POST http://localhost:8081/events \
  -H "Authorization: Bearer $PRODUCER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event_id": "DEMO-C-0001", "client_id": "CLIENT001", "event_type": "payment_created", "content": "{\"amount\": 100}"}'
```

Expected: `202`, with two notification event ids, one per subscription.

**Expected results.**

| Subscription | Receiver window | Final `delivery_status` | Attempts |
|---|---|---|---|
| `/ok` | `/ok 200 <n>` once | `completed`, `delivered_at` set | 1 (`http_status` 200, `delivery_attempts` round 1) |
| `/fail` | `/fail 500 <n>` five times | `failed`, `last_error` `retries_exhausted` | 5 (`attempt_number` 1 to 5, `http_status` 500) |

For `/fail`, the second run in [Verified run](#verified-run) measured the first three attempts at about +0 s, +11 s and +42 s. A later run measured all five at +0 s, +33 s, +161 s, +590 s and +2 667 s (44 minutes), which does not match the configured delays; see [Observed run](#observed-run-2026-10-29). The sequence does not take four minutes, as an earlier estimate in this section said. Plan for up to 45 minutes, and read the real times from `delivery_attempts.attempted_at`. `last_error` is `retries_exhausted` because a `500` response has no transport error type (`Delivery`).

**Grafana while C runs:**

- **Delivery attempts per second by outcome**: `success` for `/ok`, `retryable` for `/fail` (a `500` is `RETRYABLE` in `DeliveryOutcome.classify`).
- **Attempt failure rate including retries above 5%** (`cobre-attempt-failure-rate`): firing if the window holds only these attempts, because five of six are not `success`. Scenario A's attempt is also in the window if it ran recently.
- **Failed notification rate above 5%** (`cobre-failed-rate`): firing once `/fail` ends as `failed`.
- **Message reached the dead-letter topic**: rises by one. The message goes to the dead-letter topic only after the last retryable attempt, the fifth. The observed run logged `Record: … won't be retried. Sending to DLT with name notification.dispatch-dlt` at ERROR, followed by `round 1 failed: retries exhausted`. Compare with the baseline. `cobre-dead-letter` fires for two minutes after that.

### Verification in Grafana

- **Dashboard**: open **Dashboards**, then **Notification delivery** (uid `cobre-delivery`). The file is `docker/grafana/dashboards/notification-delivery.json`. Metrics leave the service every 10 s (`management.otlp.metrics.export.step`), and the dashboard should show a new attempt within 30 s.
- **Alert rules**: open **Alerting**, then **Alert rules**, and filter by folder **Notification delivery**. There are four rules:

  | UID | Title | Fires when |
  |---|---|---|
  | `cobre-failed-rate` | Failed notification rate above 5% | the ratio of `failed` to all end-to-end outcomes is above 5% for 5 min, for 1 min |
  | `cobre-attempt-failure-rate` | Attempt failure rate including retries above 5% | the ratio of non-`success` attempts is above 5% for 5 min, for 1 min |
  | `cobre-dead-letter` | Message reached the dead-letter topic | `notification_dead_letter_total` increases in the last 2 min |
  | `cobre-outbox-age` | Oldest unpublished outbox row older than 60 s | the outbox relay is behind by more than 60 s |

- The seed does not produce metrics. Only attempts made by the running service are counted.

### Cleanup

1. Deactivate the test subscriptions only. Do not deactivate the seed subscriptions:

   ```bash
   curl -s -X PATCH http://localhost:8081/subscriptions/<subscription id> \
     -H "Authorization: Bearer $CLIENT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"active": false}'
   ```

   `PATCH` accepts `active` and `event_types` (`SubscriptionChanges`). Deactivating does not stop notifications that were already created. Decisions D57 and D67 say that deactivation does not block round-one deliveries; this was not checked in code. Wait until nothing is left to deliver:

   ```sql
   SELECT count(*) FROM notifications WHERE delivery_status IN ('pending', 'retrying');
   ```

2. Stop the window processes: Ctrl+C in the **Service** console, and close the **Receiver 9200** and **Tunnel cloudflared** windows. Closing either of the two stops the test. The tunnel host is different on every run, so the test subscriptions cannot be reused.

3. Optionally stop this checkout's containers with `docker compose stop` from the checkout root. This stops only this checkout's Compose project. Do not stop containers from another checkout.

### Known gaps for the demo

- **DNS failures.** `dns_error` is implemented on `origin/develop` (PR #32, decision D54; D66 in [the decision log](../architecture/decision-log.md) names the error type). A name that does not resolve is retried up to five attempts per round, then ends `failed`. A name that resolves to a non-public address is not retried (`invalid_destination`). The real-DNS case was not exercised in the observed run: on the machine where the observed run took place, `webhook.example.com` resolved to a private address. Check the outcome of `dns_error` in the observed run before you rely on it.
- **Test receiver.** It does not verify signatures (see Scenario C).
- **Attempt timings.** The configured `demo` delays do not match the gaps of the observed run (+33 s, +129 s, +428 s and +2 077 s between attempts). The cause is not verified. Check the profiles the service started with (Spring logs them at startup) before you rely on any timing.
- **Grafana access.** The login is not in this repository.

## Observed run, 2026-10-29

A console log of the service, started from the main checkout (`cobre-webhook-notifications`, branch `develop`) on port 8081, with the tunnel host in `WEBHOOK_ALLOWED_HOSTS`. That checkout had one uncommitted change in `src/main/resources/application.yaml` that this run did not inspect. The pasted log did not include the startup profile line, so whether `demo` was active is not verified. Times are local (UTC−05:00); UTC is five hours ahead.

| Local time | What the log shows | Meaning |
|---|---|---|
| 21:19:49 | `01a18a9b-3031…` rejected with `host is not in the allowlist`, attempt 1 `PERMANENT`. `01a18a9b-3032…` attempt 1 `SUCCESS` | Events of the first test. Not identified in the paste |
| 21:32:58 to 21:46:14 | Five `notification_event.replay` audit lines for `01a18a9b-3032…`, outcome `rejected` | Replays of a `completed` notification, answered `409` |
| 21:47:55 | `subscription.create` for `01a18ab4-ebb1-791c-8c80-55b0813005dd`, `success` | The `/fail` subscription |
| 21:54:09.785 | `01a18aba-a19a-7f23-93c0-c6c648cc44ec` rejected with `host is not in the allowlist`, attempt 1 `PERMANENT` | An active subscription outside the test. It received `DEMO-C-0001` too |
| 21:54:10.163 | `01a18aba-a19b-7751-a981-e9f48ab9afe1`, attempt 1 `SUCCESS` | The `/ok` subscription |
| 21:54:10.264 | `01a18aba-a19b-743c-91e1-bf7a93ad6983`, attempt 1 `RETRYABLE` | The `/fail` subscription |
| 21:54:42.970 | Attempt 2, `RETRYABLE` (+32.7 s) | |
| 21:56:51.653 | Attempt 3, `RETRYABLE` (+128.7 s after attempt 2) | |
| 22:00:10.864 | `replayed: round 2` for `01a18aba-a19a…`, audit `success`. Round 2, attempt 1 `PERMANENT` at 22:00:11.161 | The replay returned `202`. The host was still not allowlisted, so the attempt failed |
| 22:04:00.121 | Attempt 4, `RETRYABLE` (+428.5 s after attempt 3) | |
| 22:11:46 | Two `subscription.deactivate` audit lines, `success` (`01a18823-2854…` and `01a18823-26d4…`) | Cleanup of two subscriptions. Which ones is not confirmed |
| 22:16:28 and 22:16:33 | WARN from `HikariPool-1`: `Failed to validate connection … (This connection has been closed.)` | Cause not explained. No attempt in the log is affected |
| 22:38:37.559 | Attempt 5, `RETRYABLE` (+2 077.4 s after attempt 4). Retry topic `notification.dispatch-retry-3` | Attempt 5 happened, 34 minutes after attempt 4. The gap is not confirmed (see below) |
| 22:38:37.567 | ERROR: the message is sent to `notification.dispatch-dlt`, with a full stack trace | Dead-letter path, after the last attempt |
| 22:38:37.801 | ERROR: `Notification event 01a18aba-a19b-743c-91e1-bf7a93ad6983 round 1 failed: retries exhausted` | `failed` with `retries_exhausted`, as the code path defines it. The database was not queried |

What this run shows:

- **Attempts 1 to 5 all happened.** Attempt 5 came 34 minutes after attempt 4. An earlier estimate of 12 minutes was wrong. The round took 44 minutes and 27 seconds from attempt 1 to attempt 5. The gap before attempt 5 is **not confirmed**: the run did not explain it, and it must not be read as normal behaviour.
- **The gaps do not match the configured delays.** The configuration (`DispatchRetryConfiguration`, `application.yaml`) works as follows. With `app.delivery.retry.delays` set, which the `demo` profile does, each retry topic uses one fixed delay: `PT10S`, `PT30S`, `PT1M` and `PT2M`. Without it, the default is exponential backoff from `PT30S` with multiplier 4, about 30 s, 120 s, 480 s and 1 920 s, and each topic keeps one jitter value of up to `PT5S`, drawn once at startup. The observed gaps were 32.7 s, 128.7 s, 428.5 s and 2 077.4 s. The first fits the default; the second is above its band and the third is below it, so neither profile reproduces the run. The cause is not confirmed. The startup profile line and the retry-topic consumer logs would settle it.
- **Scenario C reaches the dead-letter topic.** A `/fail` round ends in the dead-letter topic after its fifth attempt, so `cobre-dead-letter` should fire. The earlier expectation in this runbook, that it would not move, was wrong for `/fail`.
- **Fan-out is not isolated.** An active subscription that was not part of the test received `DEMO-C-0001`. Check active subscriptions before a demo.

Not verified, and needed to close this run:

- The number of `/fail 500` lines in the receiver window. The paste did not include it.
- The state of the `/fail` notification in the database (`failed`, `retries_exhausted`, five attempts). The log says it, but no query confirmed it.
- Grafana: `notification_dead_letter_total` and the four alert rules after this run.
- The startup profile line of this run.
- Which two subscriptions were deactivated at 22:11:46, and whether one of them is the subscription outside the test.
- The cause of the HikariPool warnings.

## What this runbook does not cover

- Real DNS rebinding. The destination policy is unit-tested with a fake resolver, not against a live DNS change.
- Redirects. The HTTP client does not follow them, so a `3xx` is a failed attempt.
- Integration tests. The automated tests use a test-only seam (`WebhookTestServer`) that routes a validated address to a local WireMock server with a trusted certificate. Production has no equivalent seam, and this runbook does not add one.
