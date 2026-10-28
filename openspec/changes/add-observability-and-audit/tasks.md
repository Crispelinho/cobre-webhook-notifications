# Tasks: Add observability and audit

## 1. Metrics and alerts (Should)

- [ ] 1.1 Micrometer meters in the dispatch, dead-letter and outbox paths; OTLP export to LGTM
- [ ] 1.2 Grafana dashboard and alert rules provisioned through `compose.yaml`
- [ ] 1.3 Manual check of the metric and dead-letter alert scenarios against the local stack, with screenshots for the presentation

## 2. Logs and audit (Should)

- [ ] 2.1 Structured JSON logs with trace, notification event and client IDs; content never logged
- [ ] 2.2 `AuditLog` port and structured adapter; calls from replay and subscription use cases
- [ ] 2.3 Tests for the audit scenarios and a test that event content does not reach the logs

## 3. Could

- [ ] 3.1 Trace propagation through Kafka headers
- [ ] 3.2 Bucket4j per-client rate limiting with tests

## 4. Verification

- [ ] 4.1 `./gradlew build` passes; fresh-context AI review before the pull request
