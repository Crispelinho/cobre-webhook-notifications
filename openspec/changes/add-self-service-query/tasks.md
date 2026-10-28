# Tasks: Add self-service notification query

## 1. Schema

- [ ] 1.1 Add `V1__create_notification_schema.sql` with tables, constraints and indexes from `design.md`
- [ ] 1.2 Integration test: Flyway migrates an empty PostgreSQL (Testcontainers) and Hibernate validation passes

## 2. Domain

- [ ] 2.1 Add `NotificationEvent`, `DeliveryAttempt`, `DeliveryStatus`, `ClientId` and `NotificationEventId`
- [ ] 2.2 Add `NotificationFilter` with its validation rules (date range order) and unit tests

## 3. Application

- [ ] 3.1 Add input ports `ListNotificationEvents` and `GetNotificationEvent`, and their use cases
- [ ] 3.2 Add output port `NotificationQueryPort` with `ClientId` required on every method
- [ ] 3.3 Unit tests for the use cases with a fake port

## 4. Persistence adapter

- [ ] 4.1 Add JPA entities, repositories and mappers
- [ ] 4.2 Implement `NotificationQueryPort` with filters, sorting and pagination
- [ ] 4.3 Integration tests against PostgreSQL (Testcontainers) for each filter and for client scoping

## 5. Security

- [ ] 5.1 Configure the resource server: RS256 public key, issuer, audience and `client_id` validators
- [ ] 5.2 Map `notifications:read` to the GET endpoints
- [ ] 5.3 Return problem details for `401` and `403`

## 6. REST adapter

- [ ] 6.1 Add `GET /notification_events` and `GET /notification_events/{notification_event_id}` with snake_case DTOs
- [ ] 6.2 Validate query parameters and return `400` problem details
- [ ] 6.3 Return `404` for unknown and foreign notification events with the same body

## 7. Seed and tooling

- [ ] 7.1 Add `SampleDataSeeder` (idempotent) and the `local` profile
- [ ] 7.2 Add the `tools/dev-token` source set and the `devToken` Gradle task

## 8. Acceptance

- [ ] 8.1 Acceptance tests for every scenario in `specs/self-service-api/spec.md`
- [ ] 8.2 Acceptance tests for every scenario in `specs/platform-security/spec.md`
- [ ] 8.3 `./gradlew build` passes
