# Proposal: verify destination ownership per client instead of a global allowlist

- **Status:** Proposed. Not accepted. It replaces the environment-variable allowlist of D26 only if accepted.
- **Scope:** how webhook destinations are authorised at registration and at delivery.

## Context

D26 allows a destination when its host is in `WEBHOOK_ALLOWED_HOSTS`, a list set by environment variable and read at startup. That works for a fixed set of hosts that the platform controls: the seed data, the demo, and the test receiver.

It does not work when the destinations belong to clients. A payment gateway may have a million client domains. A static list would need a restart for every new client, and every instance would hold the whole list in memory.

## Decision drivers

- Any client can register a destination without an operator change.
- A destination must never reach private, loopback or link-local addresses (SSRF), with or without a client.
- Routing must not depend on a process restart.
- A client must prove it controls a destination before the platform sends it event data.

## Options

### A. Keep the environment-variable allowlist (D26 as it is)

- Pro: simple, no state, already implemented and tested.
- Con: does not scale to client domains. Every new client is a deploy.

### B. Public destinations only, without ownership proof

- Pro: no per-client setup. Most webhook providers accept any public `https` URL.
- Con: a client can point a subscription at a third party that did not agree to receive the data. The platform becomes a relay for event content.

### C. Ownership verification per destination (proposed)

At registration, the platform proves that the client controls the destination:

1. It sends a challenge (a random nonce) to the URL, using a verification request.
2. The destination must echo the nonce, or publish it in a DNS `TXT` record for the host.
3. Only a verified destination is stored as active for delivery. The state lives in the database, with an index on client and host, and is cached for a short time.

Delivery keeps the controls that do not depend on the client: `https`, port 443, no user information, no redirects, every resolved address public, and the IP pinned to the address that was checked (D26).

- Pro: scales to any number of clients without a restart. A client cannot receive data for a host it does not control.
- Con: a new state machine (pending, verified, revoked), a verification request on registration, and a cache to invalidate on revocation.

## Proposed decision

Option C for client destinations. Keep the environment-variable allowlist only for hosts the platform itself owns, if any are needed. It is not used for client URLs.

## Consequences

- Registration becomes asynchronous when verification is needed. The API must return a state (`pending` or `verified`), and deliveries must not start before `verified`.
- The verification must be re-run when the client changes the URL. Today a URL cannot change (`PATCH` accepts only `active` and `event_types`), which makes this simpler.
- Revocation: the state must stop deliveries within a bounded time. The cache TTL sets that bound.
- DNS rebinding remains a risk after verification. Resolving and pinning at send time (already implemented) limits it.

## What changes in the code

Not in this proposal. The implementation would need a separate change with its own tests and an update of D26 and of the runbook's destination section.

## How to explain this in the demo

- The environment variable is a development and demo control. It shows the policy working, but it is not how the production design routes to client URLs.
- The policy rejects a host that is not listed, and it shows the reason only in the log, so an attacker cannot learn which hosts are listed.
- The production design replaces the list with ownership verification (Option C). The SSRF controls stay the same.
