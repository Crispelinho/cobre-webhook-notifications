# Replay behaviour

This page describes how replay works on `develop`. It records behaviour that is already implemented; it adds no decision. The decisions it cites are in the [decision log](decision-log.md).

## Rules

1. **Only failed notifications.** A replay is accepted only for a notification event whose `delivery_status` is `failed`. Any other status is refused with `409 Conflict` (`NotReplayableException`). Replaying a `completed` or `pending` notification is never allowed.
2. **Snapshot URL.** The replay goes to the destination URL stored on the notification event when it was created (D13), not to the current URL of the subscription. A later change of the subscription URL does not move an existing notification.
3. **Destination validated before every send.** Each attempt of the new round, the replay included, runs the shared destination policy (D19, D26) before any request is sent. A destination that is no longer allowed (for example a host that now resolves to a private address) is recorded as `invalid_destination` and nothing is sent.
4. **Secret required.** The request is signed with the secret of the subscription identified by the notification's `subscription_id`. A subscription without a secret is never delivered unsigned: the attempt fails with `missing_secret` and no request is sent (D67).
5. **Inactive subscriptions do not block a replay.** A replay of a notification whose subscription is inactive is allowed. It goes to the snapshot and is signed with that subscription's secret (D57). Inactivity alone never blocks an attempt or a replay (D67).
6. **Deactivation does not reopen failed notifications.** Deactivating a subscription changes only the subscription row. Existing notification events keep their status, their delivery and their retries (D67). A failed notification becomes eligible again only through an explicit replay, which is the client's choice.
7. **A replay starts a new round.** The event keeps its ID and its history; the new round has up to five attempts (D18, D57).

## Which decision covers what

| Rule | Decision |
|---|---|
| Snapshot URL stored on the notification | D13 |
| Destination policy before every send | D19, D26 |
| Replay of an inactive subscription is allowed, signed with its secret | D57 |
| Existing notifications keep delivery and retries; inactivity never blocks an attempt or a replay; missing secret is never delivered unsigned | D67 (supersedes D62) |
| Five attempts per round | D18 |

D57 is the replay rule: it allows the replay and says where it goes and how it is signed. D67 is the deactivation rule: it says what deactivation does to existing notifications, and it repeats that inactivity does not block a replay.
