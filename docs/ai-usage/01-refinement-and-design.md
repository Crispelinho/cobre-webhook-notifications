# 01 - Requirements refinement and system design

## Goal

Refine the challenge, resolve ambiguities and approve a candidate architecture before writing code (ODD + SDD).

## How AI was used

- Analyzed the problem statement and `notification_events.json`, proposed objectives and success criteria, and listed nine architectural ambiguities.
- Asked prioritized question groups (3-5 questions each), with a recommendation and trade-offs for every option.
- Drafted the candidate architecture (C4 context and containers, hexagonal components, data model, flows) and marked open decisions as provisional.
- Audited its own output against `CHALLENGE_PLAN.md` when asked, and reported gaps it had missed (AI log, audit trail, test strategy, load assumptions).

## Human validation and corrections

| Topic | What the AI proposed | What the human decided or corrected |
|---|---|---|
| Cardinality | 1 event = 1 notification | 1:N, one notification per matching subscription |
| `POST /events` duplicates | 200 with the original result | 409 Conflict |
| Build tool | Maven (from the original plan) | Gradle with Kotlin DSL; the plan was made technology-agnostic |
| Broker | RabbitMQ | Kafka, aligned with Cobre's published stack |

## Verification performed

- Cross-checked decisions against each other and found contradictions: `delivery_date` vs. failed events in the JSON, SSRF allowlist vs. per-subscription URLs, and client tokens on `POST /events`.
- Checked the claim that Cobre uses Kafka against public job postings (secondary source, not confirmed by Cobre engineering).
- Capacity estimate (Little's law) showed that sequential per-partition Kafka consumption does not scale to the assumed peak; this was documented as a known limit with an evolution path.
- Found that the project folder was nested inside an unrelated Git repository before initializing Git.

## Outcome

Approved design, MoSCoW scope for a two-day deadline, and repository structure. Full transcript to be exported into this folder.
