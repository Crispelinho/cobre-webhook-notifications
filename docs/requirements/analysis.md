# Requirements Analysis

Analysis of the [problem statement](../challenge/problem_statement.md) and the [sample data](../challenge/notification_events.json), done before the design.

## Observations on the sample data

| Observation | Implication |
|---|---|
| 10 notification events from 3 clients (CLIENT001 ×4, CLIENT002 ×3, CLIENT003 ×3), all on 2024-03-15 | Date filters can only be tested with hour ranges. More test data is needed. |
| Only two statuses: `completed` (7) and `failed` (3: EVT003, EVT005, EVT009) | `pending` and `retrying` do not exist in the sample; the state machine is ours to define. |
| There is a `delivery_date` but no creation date | The statement asks to filter by *event creation date*: a direct contradiction. |
| `failed` records also have a `delivery_date` | `delivery_date` cannot mean "first successful delivery". |
| No subscriptions, webhook URLs, attempt counts or HTTP responses | The whole delivery model has to be designed. |
| `event_type` uses `credit_*` and `debit_*` prefixes | Subscriptions can filter by event type. |
| `content` is free text with amounts and account numbers | Sensitive financial data: affects logging, exposure and security analysis. |
| The API path uses `notification_event_id`, the sample uses `event_id` | The relation between events and notification events must be defined. |

## Ambiguities and how they were resolved

| ID | Ambiguity | Resolution |
|---|---|---|
| A1 | Where do events come from? | `POST /events`, called by internal producers with service credentials. Events are stored and matched against active subscriptions. |
| A2 | Are the sample statuses history or pending work? | The sample is a set of notification events for querying (seed data), not the ingestion mechanism. |
| A3 | Which date do filters use? | A new `created_at` is the filter field. `delivery_date` is the timestamp of the final outcome of the current round (`completed` or `failed`), null while pending. `delivered_at` is the first 2xx, null if none. The sample is kept as is; seeded records use `created_at = delivery_date`. |
| A4 | Event, notification and subscription cardinality | One event can match several subscriptions. Each match is an independent notification event with its own destination and history. Matching requires the same `client_id` and a subscribed `event_type`. |
| A5 | What does "definitely failed" mean? | `failed` means a permanent error or exhausted retries. Only transient errors are retried. |
| A6 | Replay semantics | Asynchronous: `202 Accepted`. Only from `failed`. Keeps the notification event ID and history and starts a new round of up to 5 attempts. |
| A7 | Authentication, and how a receiver trusts a webhook | The API uses JWT (RS256) with per-client isolation. Webhooks are signed with HMAC-SHA256. |
| A8 | Allowing the presentation URL without opening SSRF | HTTPS only, a host allowlist set by environment variable, validation at registration and at send time, private ranges blocked, no redirects. |
| A9 | Which OWASP list? | OWASP API Security Top 10 (2023) as the primary reference, mapped to the OWASP Top 10 (2021). |

## Contradictions found while cross-checking decisions

Decisions were checked against each other and against the sample data. Four conflicts were found and fixed before the design was approved:

1. **`delivery_date` vs. the sample:** the first definition (first 2xx, null if none) contradicted the `failed` records that have a date. Redefined as the final outcome date.
2. **`POST /events` and isolation:** if client tokens could call it, a client could create events for another client. It now requires a service credential.
3. **Matching by event type only:** would deliver one client's event to another client's subscription. Matching now also requires the same `client_id`.
4. **Single allowlisted URL vs. per-subscription URLs:** replaced by a host allowlist that applies to every subscription.
