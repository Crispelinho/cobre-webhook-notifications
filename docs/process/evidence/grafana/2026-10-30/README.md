# Grafana evidence, 2026-10-30

Screenshots of the **Notification delivery** dashboard (uid `cobre-delivery`) taken by the developer on a local run on 2026-10-30. They show the metrics of objective O6 (see [live verification](../../../live-verification-2026-10-30.md)). They are cropped to the Grafana content: they show no URL, token, secret or tunnel host.

The run profile and the event ids sent were not recorded with the screenshots.

| File | Time range | What it shows |
|---|---|---|
| [o6-delivery-dashboard-overview.png](o6-delivery-dashboard-overview.png) | 07:51 to 10:18 local time | About two and a half hours of activity. Delivery attempts by outcome (`permanent`, `retryable`, `success`) between about 08:20 and 10:05. Failed attempt ratio 100%, above the 5% alert threshold. Dead-letter messages in the last hour: 2. Attempt latency p95 between about 0.5 s and 1.8 s. Creation to final outcome p95 up to about 23 minutes for `failed`. Oldest unpublished outbox row below the 60 s alert line. |
| [o6-delivery-dashboard-single-run.png](o6-delivery-dashboard-single-run.png) | Last 30 minutes, ending about 08:25 local time | One run of about 5 minutes. Attempts `retryable` (peak 0.08/s), `permanent` and `success` between about 08:19 and 08:24. Failed attempt ratio 93.3%. Dead-letter messages in the last hour: 0. Attempt latency p50 about 100 ms and p95 about 950 ms. Creation to final outcome p95 about 2 s for `completed` and about 40 s for `failed`. |

Notes:

- The "Creation to final outcome p95" panel measures from the notification event's `created_at`, also for a replayed round (see [Known limitations](../../../../architecture/system-design.md#known-limitations)). A value of about 23 minutes may include time before a replay, not only delivery time.
- A `failed` outcome in about 40 s means a short retry schedule (the `local` profile since #55, or `demo`). With the default schedule a full round takes much longer.
- Not captured yet: the alert rules list, an alert in the firing state, and the outbox-age alert. Task 1.3 of `add-observability-and-audit` stays open until they are.
