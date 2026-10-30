# quality-and-delivery Specification

## Purpose
How the platform makes delivery visible to the monitoring team: metrics, structured logs and alerts in near real time.

## Requirements

### Requirement: Delivery metrics

The system SHALL export, over OTLP, a counter of delivery attempts by outcome, a timer of attempt latency, a timer from creation to final outcome, a counter of dead-letter messages, and the age of the oldest unpublished outbox row.

#### Scenario: Metric visible in near real time

- **GIVEN** the local stack is running
- **WHEN** a notification event is delivered
- **THEN** the attempt counter for its outcome increases in Grafana within 30 seconds

### Requirement: Delivery alerts

The local stack SHALL provision a Grafana dashboard and alert rules that fire when the `failed` rate exceeds 5% over 5 minutes, when a message reaches the dead-letter topic, and when the oldest unpublished outbox row is older than 60 seconds.

#### Scenario: Dead-letter alert

- **GIVEN** a receiver that always answers `500`
- **WHEN** a notification event exhausts its retries
- **THEN** the dead-letter alert fires within 1 minute

### Requirement: Structured logs

Logs SHALL be structured JSON including the trace ID and, when known, the notification event ID and client ID. Event content, tokens and secrets SHALL never be logged.

#### Scenario: Investigating a client complaint

- **GIVEN** a client reports a missing notification and gives its notification event ID
- **WHEN** the monitoring team searches the logs by that ID
- **THEN** they find every delivery attempt with its outcome, without the event content
