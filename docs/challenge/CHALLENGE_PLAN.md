# Challenge Plan: Cobre Technical Challenge

This document defines the working framework, lifecycle, phases and governance for the **Cobre** technical challenge. It guides the engineering process under a hybrid **ODD (Objective-Driven Development)** and **SDD (Specification-Driven Development)** approach using **OpenSpec**, before any code or repository is initialized.

---

## 1. Guiding Principles and Governance

1. **No premature implementation:** in the initial stage, no base project is created, no project generators are run, no repository is initialized, no dependencies are installed and no files are modified until the design is explicitly approved.
2. **Transparent, documented AI usage:** AI is encouraged as a complementary productivity tool. Use cases, *prompts*, iterations and validations are documented in detail throughout the process for the final defense before the panel.
3. **Quality over speed:** first refine the problem, design the solution and specify the contracts, then write functional code.

---

## 2. Hybrid Work Cycle

1. **ODD (define the outcome):** set clear objectives, constraints and measurable success criteria.
2. **Interactive refinement:** ask questions, propose alternatives, analyze trade-offs and record design decisions.
3. **SDD + OpenSpec:** turn approved decisions into requirements, *Given/When/Then* scenarios and change proposals organized by capability.
4. **Human approval:** strict validation before moving to implementation.
5. **Implement and verify:** code under hexagonal architecture, unit and integration tests, security review and traceability.

---

## 3. Plan Phases and Cobre Tasks

| Plan phase | Challenge phase | Main focus |
| :--- | :--- | :--- |
| **Phase 1** | Requirements refinement (ODD) | Analysis of the statement, ambiguities, scope and success criteria. |
| **Phase 2** | System design | Components, event flows, webhooks, retries, persistence, observability and resilience. |
| **Phase 3** | Specification (SDD + OpenSpec) | Turning decisions into requirements, acceptance scenarios and API contracts. |
| **Phase 4** | Repository and scaffold | Base project and build tool, hexagonal packages and base configuration (not evaluated on its own). |
| **Phase 5** | Implementation and validation | API and webhook development, acceptance tests and security mitigations. |
| **Phase 6** | Delivery and defense | Diagrams, final README, repository packaging, AI documentation and presentation. |

---

## 4. Traceability to the Evaluated Tasks

- **Task 1. System Design (Phases 1, 2, 3 and 6):** documented architecture, diagrams, justified decisions and a scalability and resilience strategy.
- **Task 2. API Implementation (Phases 3, 4, 5 and 6):** repository with the implementation, hexagonal architecture, HTTPS webhook delivery, REST endpoints and automated tests.
- **Task 3. Security (Phases 1, 2, 3, 5 and 6):** OWASP Top 10 risk analysis, documented mitigations and implemented security tests.
- **AI usage documentation (all phases):** transparent record of AI usage, decision verification and validations performed.

---

## 5. Work Organization in OpenSpec (Capabilities)

Design and changes are managed by modular functional capabilities instead of a single monolithic proposal:

- **Capability: Platform design (Task 1):** architecture, components, data model, event flows, retries, resilience and observability.
- **Capability: Self-service API (Task 2):** listing with filters, single lookup, pagination, per-client authorization and the *replay* endpoint.
- **Capability: Notification delivery (Task 2):** subscriptions, HTTPS delivery, timeouts, retries, idempotency and result persistence.
- **Capability: Platform security (Task 3):** authentication, client isolation, URL validation, SSRF prevention, rate limiting and auditing.
- **Cross-cutting capability: Quality and delivery (all tasks):** testing, observability, documentation and local setup.
