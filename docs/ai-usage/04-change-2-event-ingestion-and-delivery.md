# 04 - Change 2: event ingestion and webhook delivery

## Goal

Implement the `add-event-ingestion-and-delivery` OpenSpec change: `POST /events` for internal producers, a transactional outbox, the Kafka relay, HTTPS webhook delivery with retries, a dead-letter topic and idempotent consumption. Merged as pull request #6. Batch 4 (destination validation and HMAC signature) is tracked separately.

## How AI was used

- Implemented the change in batches, each reviewed and committed by the developer:
  1. Ingestion: V2 outbox migration, the `events` capability, `POST /events`, duplicate and validation handling, UUIDv7 notification IDs.
  2. Outbox relay: `SKIP LOCKED` batches, idempotent Kafka producer, and a test that pauses the real Kafka container to prove events accepted during an outage are delivered later.
  3. Delivery: delivery outcome classification, state machine, HTTPS sender with redirects disabled, retry topics per profile, dead-letter handler and idempotent consumption, tested with WireMock over HTTPS.
- Used a separate delegated agent for each batch. The agent worked on the branch and left changes uncommitted; the orchestrating session reviewed the diff and the test results before the developer approved each commit.
- Turned each specification scenario into a named test, so the scenario list in the spec doubles as the test list.
- Resolved spec ambiguities against the approved decision log instead of guessing, and recorded each resolution in the agent reports.
- Diagnosed a flaky test suite instead of rerunning it until it passed (see below).

## Mistakes the human caught or the specification corrected

- **Wrong instruction from the orchestrator.** The orchestrating session told the first agent to take `client_id` from the JWT claim, which is what the security principle suggests. The specification says producers post events *for* a client, so `client_id` comes from the body and access is controlled by the `events:write` scope, which client tokens never carry. The agent flagged the contradiction in its report instead of silently following the wrong brief, and the specification won. The developer approved that outcome.
- **Ambiguous retry rule.** The task said "4xx except 429 is permanent", but the specification and decision D19 retry `408`. The specification was followed.
- **Flaky build caused by test infrastructure, not product code.** Every cached Spring test context started its own `grafana/otel-lgtm` container, and no test asserted against it. Five contexts meant five heavy containers competing for Docker, and the suite failed with startup timeouts on different tests each run. The fix moved the LGTM stack to the local launcher only and disabled OTLP export for the test task. The suite went from failing runs to a green run of 122 tests.
- **Stale branch base.** The change branch was cut from an older specs commit. Since the specs had been squash-merged into `develop` under a different hash, the pull request would have carried a duplicate commit and conflicted with change 4. The commits were rebased onto `develop`, which needed one manual conflict resolution in `SecurityConfig`: both sides added routes, and the resolution kept both.

## Verification performed

| Check | Result |
|---|---|
| Full build after the batch 1 agent | 114 tests, 0 failures |
| Full build after the batch 2 agent (after the test-infrastructure fix) | 122 tests, 0 failures |
| Full build after the batch 3 agent | 213 tests, 0 failures |
| Full build after rebasing onto `develop` | 363 tests, 0 failures (includes change 4) |
| Kafka outage test | Event accepted with `202` while the broker is paused, not published, then published after unpause |
| TLS trust test | The production HTTPS client rejects an untrusted test certificate; the test-only client trusts it |
| CI on pull request | Validate OpenSpec, Build and test, and Dependency review all succeeded |

## Human validation and corrections

- The developer reviewed every batch diff before each commit and kept the local-only `server.port` setting out of every commit.
- The developer approved the producer authorization model (`events:write`) and the `409` response for duplicate `event_id`.
- The developer merged the pull request into `develop`.

## Open items

- Batch 4 of this change (destination validation before each attempt and the `Cobre-Signature` HMAC header) is in progress on its own branch.
- Task 5.2, the dual blind AI review of the delivery and security change, is still pending and will be recorded in a later entry.
