# Tasks: Add notification replay

## 1. Domain and application

- [x] 1.1 `NotificationEvent.replay()` (only from `failed`; next round; clears `delivery_date`) with unit tests
- [x] 1.2 `ReplayNotificationEvent` input port and use case

## 2. Adapters

- [x] 2.1 Conditional update on `version` plus outbox row in one transaction
- [x] 2.2 `POST /notification_events/{notification_event_id}/replay` and the `notifications:replay` rule

## 3. Acceptance

- [x] 3.1 Tests for every scenario, including concurrent replays and a replayed delivery end to end
- [ ] 3.2 `./gradlew build` passes; fresh-context AI review before the pull request
