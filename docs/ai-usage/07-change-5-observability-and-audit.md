# 07 - Change 5: observability and audit

## Goal

Implement the `add-observability-and-audit` OpenSpec change: delivery metrics (Micrometer), a structured audit log for subscription and replay operations, W3C trace context through Kafka headers, per-client rate limiting (100 requests per minute, Bucket4j) on the notification and subscription endpoints, and a Grafana delivery dashboard with alert rules for local use.

## How AI was used

- Implemented the change against the spec scenarios. Log hygiene tests parse the JSON log lines and assert the top-level fields, so the log format is checked by tests, not by reading the output.
- Metrics and alert rules were written for the local Grafana stack in `docker/grafana/`.

## Verification performed

| Check | Result |
|---|---|
| Build and tests | `./gradlew clean build`: 446 tests, 0 failures, 0 errors (PR #13). |
| Fresh-context review | Pending: task 4.1 in the change. |
| Dashboard and alert check | Pending: task 1.3 needs a manual check with screenshots. |

The change is merged, but it is not closed: the fresh-context review and the manual dashboard check are still open. Source: the PR #13 description. The developer should confirm these figures and the open items before the entry is cited as evidence.
