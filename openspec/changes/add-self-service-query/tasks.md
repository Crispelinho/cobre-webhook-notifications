# Tasks: Add self-service notification query

## 1. Schema

- [x] 1.1 Add `V1__create_notification_schema.sql` with tables, constraints and indexes from `design.md`
- [x] 1.2 Integration test: Flyway migrates an empty PostgreSQL (Testcontainers) and constraints hold

## 2. Domain

- [x] 2.1 Add `NotificationEvent`, `DeliveryAttempt`, `DeliveryStatus`, `ClientId` and `NotificationEventId`
- [x] 2.2 Add `NotificationFilter` with its validation rules (date range order) and unit tests

## 3. Application

- [x] 3.1 Add input ports `ListNotificationEvents` and `GetNotificationEvent`, and their use cases
- [x] 3.2 Add output port `NotificationQueryPort` with `ClientId` required on every method
- [x] 3.3 Unit tests for the use cases with a fake port
- [x] 3.4 Architecture test: domain and application import no framework or adapter code

## 4. Persistence adapter

- [x] 4.1 Implement `NotificationQueryPort` with `JdbcClient` and explicit SQL (see `design.md`)
- [x] 4.2 Filters, sorting and pagination
- [x] 4.3 Integration tests against PostgreSQL (Testcontainers) for each filter and for client scoping

## 5. Security

- [x] 5.1 Configure the resource server: RS256 public key, issuer, audience and `client_id` validators
- [x] 5.2 Map `notifications:read` to the GET endpoints
- [x] 5.3 Return problem details for `401` and `403` (verified over HTTP in 8.2)

## 6. REST adapter

- [x] 6.1 Add `GET /notification_events` and `GET /notification_events/{notification_event_id}` with snake_case DTOs
- [x] 6.2 Validate query parameters and return `400` problem details
- [x] 6.3 Return `404` for unknown and foreign notification events with the same body

## 7. Seed and tooling

- [x] 7.1 Add `SampleDataSeeder` (idempotent) and the `local` profile
- [x] 7.2 Add the `tools/dev-token` source set and the `devToken` Gradle task (verified manually against the running application)

## 8. Acceptance

- [x] 8.1 Acceptance tests for every scenario in `specs/self-service-api/spec.md`
- [x] 8.2 Acceptance tests for every scenario in `specs/platform-security/spec.md`
- [x] 8.3 `./gradlew build` passes
