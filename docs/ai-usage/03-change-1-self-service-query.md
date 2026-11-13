# 03 - Change 1: self-service query

## Goal

Implement the `add-self-service-query` OpenSpec change: schema, domain, read adapter, JWT security, the two `GET` endpoints, the seeder and the development token tool. Merged as pull request #3.

## How AI was used

- Implemented the change in five batches (schema, domain and use cases, persistence adapter, security, endpoints and seed). The developer reviewed every batch diff before it was committed.
- Turned every Given/When/Then scenario into an acceptance test, using the real sample data as fixtures.
- Checked Spring Boot 4 package locations (test slices, Jackson 3) in the dependency jars instead of assuming them.
- Ran the application end to end (`bootRun` with Docker Compose and the seed) and called it with development tokens.
- Delegated an adversarial review to a separate AI agent that had not seen the design conversation.

## Verification performed

| Check | Result |
|---|---|
| Architecture test injected with a forbidden Spring import | The test failed and named the file and line, proving it is not vacuous; the file was restored. |
| End-to-end run with the real application | Seed loaded 10 notification events; CLIENT002 saw only its own; CLIENT001 got `404` for EVT003; no token gave `401`. It also revealed that `401` bodies were sent as ISO-8859-1, which no test had caught; fixed to UTF-8. |
| Independent review | No critical findings; 12 findings; 10 accepted. The AI verified finding 1 by reading Spring Security's bytecode before accepting it. |
| Final build | 66 tests, 0 failures; `openspec validate --strict` passes; CI green on the pull request. |

## Security issues found by the independent reviewer

The implementing session did not catch these; the independent reviewer did:

1. `WWW-Authenticate` exposed why a token was rejected (`error_description`, for example "Jwt expired at …").
2. A development JWT issuer was the default in every profile, so a misconfigured deployment would trust it silently.
3. Any valid token reached routes without an explicit authorization rule.

All three were fixed and covered by tests before the merge.

## Human validation and corrections

- The developer approved the switch from JPA entities to explicit SQL for reads, a deviation from the approved design that the AI flagged instead of applying silently.
- The developer had a local-only change (`server.port`); the AI kept it out of every commit.
- The developer merged the pull requests; the AI only deleted merged branches, following the agreed rule.
