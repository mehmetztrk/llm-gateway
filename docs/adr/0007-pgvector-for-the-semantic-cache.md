# 0007 — pgvector for the semantic cache

- **Status:** Accepted
- **Date:** 2026-09-03

## Context

The semantic cache needs to store embeddings and find the nearest neighbour within a tenant. Three
options were on the table under the zero-budget rule: pgvector in the Postgres already running,
Redis Stack's vector search in the Redis already running, or an in-JVM brute-force index.

## Decision

pgvector, in the same Postgres instance that holds tenants, keys and the usage ledger.

## Consequences

- **Isolation becomes a `WHERE` clause on an indexed column**, in the same database and the same
  transaction boundary as the tenant rows it refers to, with a foreign key that deletes cache
  entries when a tenant is deleted. That is the property this milestone is judged on, and it is far
  easier to argue about in SQL than across two datastores.
- **One less thing to reason about during an outage.** Redis is already the component that fails
  *closed* for rate limiting; making it also the semantic cache would put a fail-closed and a
  fail-open concern in the same box, and the ADR-0004 argument would become muddier to hold in
  anyone's head.
- **The embedding model is part of the schema, not configuration.** `vector(768)` is the width of
  `nomic-embed-text`. Changing the embedding model changes the number and invalidates every stored
  row, so it is a migration, not a config flip — and the code says so by validating the width
  before insert rather than letting Postgres reject each row separately.
- **ivfflat, therefore approximate.** An exact scan is O(rows) per lookup, and a cache that gets
  slower as it fills is a cache that stops being worth having. The recall cost is acceptable
  precisely because a miss is only a slower correct answer.
- **Cost: blocking JDBC on the request path.** Unlike authentication this is not behind a hot cache
  — a semantic lookup always costs a round trip. It is moved to the virtual-thread scheduler like
  every other blocking call (ADR-0006), and it is the reason the exact layer is consulted first.
- **Expiry is swept opportunistically on write**, in proportion to the traffic that created the
  rows. No scheduled job to own and nothing that must run on exactly one node.

## Alternatives considered

- **Redis Stack vector search.** Rejected: couples the cache to the component that fails closed,
  and its licence (RSALv2/SSPL) is a conversation this project does not need to have.
- **In-JVM brute force.** Rejected: dies with the process and cannot be shared across replicas,
  which undercuts the "control plane" story the repository is meant to tell.
