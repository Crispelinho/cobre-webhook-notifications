# Design: Add self-service notification query

## Context

First slice of the platform. It must establish the schema, the hexagonal layout and client isolation so that ingestion, delivery and replay only add behavior later. The full platform design lives in `docs/architecture/`.

## Decisions

### Full schema in the first migration

`V1__create_notification_schema.sql` creates `events`, `subscriptions`, `notifications` and `delivery_attempts`. The outbox table belongs to the ingestion change.

| Table | Key columns |
|---|---|
| `events` | `id varchar(64)` PK, `client_id`, `event_type`, `content text`, `created_at timestamptz` |
| `subscriptions` | `id varchar(64)` PK, `client_id`, `url varchar(2048)`, `event_types text[]`, `active`, `secret_ciphertext bytea` (nullable until HMAC), `created_at` |
| `notifications` | `id varchar(64)` PK, `event_id` FK, `subscription_id` FK, `client_id`, `destination_url`, `delivery_status` (check constraint), `round`, `attempts`, `created_at`, `delivery_date`, `delivered_at`, `last_error`, `version` |
| `delivery_attempts` | `id bigint` identity, `notification_id` FK, `round`, `attempt_number`, `attempted_at`, `http_status`, `error_type`, `latency_ms`; unique `(notification_id, round, attempt_number)` |

Indexes: `notifications (client_id, created_at desc, id desc)` and `notifications (client_id, delivery_status, created_at desc)`, matching the two query shapes.

IDs are `varchar(64)` so seeded IDs (`EVT001`) and UUIDv7 strings share the same column.

`client_id` is denormalized into `notifications` so every self-service query filters on one indexed column without joins.

### Read side with explicit SQL

The query adapter (`JdbcNotificationQueryAdapter`) uses `JdbcClient` with explicit SQL instead of JPA entities. The list joins `notifications` and `events` and builds its `WHERE` clause only from the filters present, always including `client_id`. Rows are mapped straight to domain objects.

This replaces the original plan of JPA entities for reads, for three reasons: a join with optional filters is clearer in SQL than in a JPA specification; there is no N+1 risk; and no entity for the `events` table has to live inside the `notifications` capability. JPA remains an option for the write side (ingestion, replay with optimistic locking) in later changes. The domain stays framework-free either way.

### Query port

`NotificationQueryPort` (output port) exposes `findPage(ClientId, NotificationFilter, PageRequest)` and `findById(ClientId, NotificationEventId)`. `ClientId` is a required argument of every query method, so the persistence layer cannot be called without a client scope. This makes client isolation structural rather than something each caller must remember.

### Authentication

- Spring Security OAuth2 Resource Server with a `NimbusJwtDecoder` built from an RSA public key (`app.security.jwt.public-key`), plus issuer and audience validators.
- Scopes come from the standard `scope` claim and become `SCOPE_*` authorities.
- A `ClientIdentity` resolver reads the `client_id` claim. A token without it is rejected with `401` by a custom validator.
- Errors are RFC 9457 problem details, including authentication errors.

### Development token tool

`tools/dev-token` is a separate Gradle source set run with `./gradlew devToken --args="CLIENT001 notifications:read"`. On first run it creates an RSA key pair under `.local/dev-jwt/` (git-ignored). The `local` profile reads the public key from there. Tests do not use it: they generate an in-memory key pair.

### Seeding

`SampleDataSeeder` runs on `ApplicationReadyEvent` when `app.seed.enabled=true` (enabled in the `local` and `demo` profiles, disabled by default). It inserts records only if their ID does not exist. Seed subscriptions point to `https://webhook.example.com/<client_id>` (a reserved domain) until real subscriptions are registered.

## Risks

- **Offset pagination** gets slower on deep pages. Accepted with the 100-item cap; keyset pagination is documented as an evolution.
- **Seed `created_at` assumption** may surprise reviewers. It is stated in `openspec/project.md` and in the API documentation.
