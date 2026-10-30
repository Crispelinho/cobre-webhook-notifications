# AI usage log

AI (Claude Code) was used as a productivity complement. A human approved every decision before it moved forward.

| # | Phase | Entry |
|---|---|---|
| 00 | Initial prompt (kept verbatim in its original Spanish) | [00-initial-prompt.txt](00-initial-prompt.txt) |
| 01 | Requirements refinement and system design | [01-refinement-and-design.md](01-refinement-and-design.md) |
| 02 | Scaffold, CI and supply chain | [02-scaffold-ci-and-supply-chain.md](02-scaffold-ci-and-supply-chain.md) |
| 03 | Change 1: self-service query | [03-change-1-self-service-query.md](03-change-1-self-service-query.md) |
| 04 | Change 2: event ingestion and webhook delivery | [04-change-2-event-ingestion-and-delivery.md](04-change-2-event-ingestion-and-delivery.md) |
| 05 | Change 4: subscription management | [05-change-4-subscriptions.md](05-change-4-subscriptions.md) |
| 06 | Change 3: notification event replay | [06-change-3-replay.md](06-change-3-replay.md) |
| 07 | Change 5: observability and audit | [07-change-5-observability-and-audit.md](07-change-5-observability-and-audit.md) |

## Evidence images

Screenshots of the design phase conversation, kept as evidence of how AI was used and what was decided by the developer. They are not specifications; the decisions are recorded in the [decision log](../architecture/decision-log.md) and in `openspec/`.

| Image | What it shows |
|---|---|
| [image1.png](images/image1.png) | The initial prompt: ODD + SDD approach, and the instruction to refine the problem before any file is created. |
| [Image2.png](images/Image2.png) | The first analysis of the sample data: event counts, the two states present, `delivery_date` without a creation date, and the open questions it raised. |
| [Image3.png](images/Image3.png) | The review of the nine ambiguities (A1 to A9) against the decisions already taken. |
| [Image4.png](images/Image4.png) | The AI's recommendations compared with the decisions made, point by point. |
| [Image5.png](images/Image5.png) | The same comparison, continued. |
| [Image6.png](images/Image6.png) | The next questions to close before the architecture: MoSCoW scope, Gradle DSL, observability and deployment. |
| [multiagent.png](images/multiagent.png) | Status of the parallel sessions and the decisions pending from the developer during implementation. |
