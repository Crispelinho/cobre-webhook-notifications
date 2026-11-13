# 06 - Change 3: notification event replay

## Goal

Implement the `add-notification-replay` OpenSpec change: `POST /notification_events/{id}/replay` for failed notification events, guarded by the `notifications:replay` scope. A replay starts a new delivery round from PostgreSQL, so it reuses the outbox path of change 2 instead of sending the webhook directly.

## How AI was used

- Implemented the change against the spec scenarios: 404 for another client's event, 409 with a fixed message when the event is not `failed`, and a new round that clears `delivery_date` and `last_error` while keeping attempt rows, `attempts` and `delivered_at`.
- The round change and the outbox row are written in one transaction, guarded by status and version (optimistic locking).
- An independent AI review, run in a fresh context, looked at the branch. It found no critical issues.

## Verification performed

| Check | Result |
|---|---|
| Build and tests | `./gradlew clean build`: 388 tests, 0 failures (PR #9). |
| Independent review | No critical findings. Its warnings were fixed: JDBC tests for each guard, the 409 message made fixed, and the outbox payload built in SQL. |
| Open item | Task 3.2 (the fresh-context review record) is tracked in the change. The review ran on this branch, but the record is not yet linked from the change. |

Source: the PR #9 description. The developer should confirm these figures against the PR before the entry is cited as evidence.
