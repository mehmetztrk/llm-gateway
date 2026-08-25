# 0000 — Use Architecture Decision Records

- **Status:** Accepted
- **Date:** 2026-08-25

## Context

This gateway sits on a series of choices that look arbitrary from the outside and are expensive to
revisit: reactive versus imperative, fail-open versus fail-closed when Redis is unreachable, where
the semantic-cache similarity threshold sits, whether a cache is allowed to be shared across
tenants. Six months from now the code will still show *what* was chosen and give no hint of *why*,
or of what was rejected.

## Decision

Every significant decision gets a short Markdown file in `docs/adr/`, numbered sequentially,
following Michael Nygard's format: Context, Decision, Consequences. ADRs are immutable once
accepted — a decision that changes gets a new ADR that supersedes the old one, and the old one is
marked Superseded rather than edited or deleted.

A decision is "significant" if reversing it later would mean changing more than one package, or if
a reviewer would reasonably ask "why did you do it that way?".

## Consequences

- The reasoning survives the author's memory, which matters when defending this code in an
  interview or handing it to someone else.
- Rejected alternatives are recorded, so the same debate is not re-run from scratch.
- Small cost per decision, and a discipline to actually write them at the time rather than
  retrofitting a plausible story afterwards.
- The ADR log is deliberately a record of decisions, not documentation of how the system works —
  that lives in the README and the code.

## Planned ADRs

| # | Subject | Lands with |
|---|---|---|
| 0001 | Java 21, Spring Boot 3.5, single Gradle module | M0 |
| 0002 | WebFlux over MVC + virtual threads for the proxy path | M2 |
| 0003 | Rate-limit algorithm: token bucket over sliding window | M4 |
| 0004 | Fail-open on cache, fail-closed on rate limit | M4 |
| 0005 | Cache strategy: exact before semantic, tenant-scoped keys | M6 |
| 0006 | Blocking JDBC inside a reactive application | M3 |
| 0007 | pgvector as the semantic-cache vector store | M6 |
| 0008 | Semantic cache similarity threshold | M6 |
