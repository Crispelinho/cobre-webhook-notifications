# Demo runner

Black-box checks for the demo scenarios. The runner talks to the service only through its public API, reads the
receiver's request log, and deactivates the subscriptions it created. It never changes the destination policy.

Three scenarios ship in `scenarios.json`:

| ID | What it checks | Needs the tunnel |
|---|---|---|
| `ok` | Signed delivery to a `200` receiver: `completed`, one attempt | yes |
| `fail-retries-exhausted` | `500` receiver: `failed` after five attempts, all `500` | yes |
| `host-not-allowed` | A host outside the allowlist is refused at registration with a generic `400` | no |

Each scenario gets its own path and its own `event_type`, so the events of one never reach another's subscription.

## Run it

Start the three pieces in this order. Use the same secrets and log files for the receiver and the runner.

1. The receiver, from this directory:

   ```bash
   python receiver.py --port 9300 --secrets-file ./run/secrets.json --log-file ./run/requests.jsonl
   ```

   It answers `500` for paths ending in `/fail` and `200` otherwise, and it logs whether each signature verifies.

2. Expose the receiver on port 9300 with your tunnel (for example `cloudflared tunnel --url http://localhost:9300`), and take the host without scheme or port.

3. The service, with the tunnel host allowed (`WEBHOOK_ALLOWED_HOSTS` is the only allowlist setting that changes):

   ```bash
   SPRING_PROFILES_ACTIVE=local,demo
   WEBHOOK_ALLOWED_HOSTS=<tunnel host>
   DEMO_WEBHOOK_SECRET=whsec_<throwaway value>
   ./gradlew bootRun
   ```

4. The runner:

   ```bash
   export CLIENT_TOKEN=... PRODUCER_TOKEN=...
   python runner.py --tunnel-host <tunnel host> --secrets-file ./run/secrets.json --log-file ./run/requests.jsonl --report ./run/report.json
   ```

   Use `--only ok host-not-allowed` for a quick run. `--concurrency` sets how many scenarios run at once.

The exit code is `0` only when every check passes.

## Unit tests

They need no service:

```bash
python -m unittest -v test_harness
```

They cover the signature check, the receiver's routes and log, and the scenario file.

## Limits

- **The `fail` scenario takes a long time.** Its timeout is one hour. The observed run took about 44 minutes with the attempt gaps described in the runbook, and those gaps are not explained. Use the `demo` profile to shorten the schedule, and read the real times from the report.
- **`last_error` is not checked.** The API does not expose it; the scenario checks the attempts and their HTTP statuses instead.
- **`event_type` values are not verified.** The runner uses `demo_<id>_<run>`. If the service rejects that format, the first run will say so.
- **No DNS scenario.** `dns_error` needs a host that does not resolve; the runner cannot create one.
- **No timing assertions.** The runner checks outcomes, not the gaps between attempts.
