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

## Step 3: start the service with that host allowed

The `local` profile enables the development JWT issuer and the seed. `WEBHOOK_ALLOWED_HOSTS` overrides the profile default (`webhook.example.com`):

```bash
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8081
WEBHOOK_ALLOWED_HOSTS=abc123.ngrok-free.app
./gradlew bootRun
```

Prefix an entry with `.` only if you want its subdomains too: `.ngrok-free.app` would allow every ngrok host. For a demo, the exact host is the safer choice.

To see retries quickly, add `SPRING_PROFILES_ACTIVE=local,demo`. The `demo` profile uses the delays `PT10S,PT30S,PT1M,PT2M` instead of the 30 s to 1 h backoff.

If `docker compose` cannot bind the fixed host ports (`9092` for Kafka, `5432` for PostgreSQL), another Compose stack already holds them. This happens when two checkouts run the stack at the same time. Do not stop the other stack; start your own containers on free ports and point the app at them:

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

A `2xx` status marks the delivery as completed. Any other status, or a timeout, is retried. A round has five attempts (the first one included). With the `demo` profile the scheduled delays are `PT10S`, `PT30S`, `PT1M` and `PT2M` between attempts; the first three attempts were measured at +0 s, +11 s and +42 s. When the attempts run out, the notification ends as `failed`. Only a replay starts a new round (`POST /notification_events/{id}/replay`).

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
| Seed subscriptions fail with `INVALID_DESTINATION` | `webhook.example.com` does not resolve | Expected. The seed is for the data model, not for live delivery. |
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

Not exercised: ngrok (not installed on the test machine), the public-address rule against real DNS, and the exact timestamps of the fourth and fifth attempts (only their count was measured).

## What this runbook does not cover

- Real DNS rebinding. The destination policy is unit-tested with a fake resolver, not against a live DNS change.
- Redirects. The HTTP client does not follow them, so a `3xx` is a failed attempt.
- Integration tests. The automated tests use a test-only seam (`WebhookTestServer`) that routes a validated address to a local WireMock server with a trusted certificate. Production has no equivalent seam, and this runbook does not add one.
