# Live verification, 2026-10-30

This run checks the objectives that the test suite cannot prove on its own. It uses a compose project of its own, with free host ports, and the service on port 8096 with the `local` profile.

## Results

| Objective | Result | Evidence |
|---|---|---|
| O10 `docker compose up` healthy in under 2 min | Met: 36 s | Fresh project, one run |
| O4 p95 below 300 ms | Met: p95 47 ms | 90 successful `GET /notification_events?delivery_status=failed&size=50` requests, one client |
| O4 `size` above 100 returns 400 | Met | `size=101` returned 400 |
| O4 CLIENT003 + `failed` returns EVT005 and EVT009 | Met | Sample data |
| O6 metric visible in Prometheus | Met | `notification_delivery_attempts_total` and related series present |
| O6 alerts provisioned | Met | Four rules loaded in Grafana |
| O6 alerts fire on failures | Observed: "Failed notification rate above 5%" and "Attempt failure rate including retries above 5%" went to firing | Exact time to firing not measured, so the 1-minute target is not verified |
| O2 first attempt within 2 s (p95) | Not met over a public tunnel: p50 2.9 s, p95 8.6 s, n = 40 | Includes the tunnel. Local delivery is blocked by the SSRF policy, so it cannot be measured on localhost |
| O3 full round in 4 min or less | Not verified | Gaps between attempts were 32 s and 103 s, against the configured 10 s, 30 s, 60 s and 120 s. Retries stopped after the third attempt |
| Dead-letter topic on a failed round | Not verified live | Covered by `DeliveryIntegrationTests` with Testcontainers |

## Kafka coordinator churn: cause found

With a single broker, the default `offsets.topic.num.partitions` (50) produced many `not the correct coordinator` errors and rejoins: 338 errors in about four minutes, and about 200 rejoins, against 12 in the Testcontainers run.

Setting `KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS=1`, as Testcontainers does, removed the errors: 0 errors and 12 rejoins in three minutes, the same as the Testcontainers run.

`group.initial.rebalance.delay.ms=0` alone did not change the result. The churn appeared with the delay at 0 and with the default delay.

Proposed fix, not applied: add `KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS: '1'` to the `kafka` service in `compose.yaml`. This setting is for local development only. A production broker needs a partition count that matches the cluster.

## Not verified

- The live retry chain and the dead-letter topic. The live run never reached attempts 4 and 5 with the default partition count. The same run with one partition was not repeated through a retry round.
- O2 and O3 on localhost. The SSRF policy rejects private and loopback addresses, so these need a public HTTPS receiver.
