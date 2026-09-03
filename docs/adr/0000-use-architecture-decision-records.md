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

## The log

| # | Subject |
|---|---|
| [0000](0000-use-architecture-decision-records.md) | Use architecture decision records |
| [0001](0001-java-21-spring-boot-3-5-single-module.md) | Java 21, Spring Boot 3.5, single Gradle module |
| [0002](0002-webflux-over-mvc-with-virtual-threads.md) | WebFlux over MVC with virtual threads |
| [0003](0003-token-bucket-rate-limiting.md) | Token bucket, not fixed or sliding window |
| [0004](0004-fail-closed-limits-fail-open-cache.md) | Fail closed on limits, fail open on cache |
| [0005](0005-exact-before-semantic-caching.md) | Exact cache first, semantic second |
| [0006](0006-blocking-jdbc-in-a-reactive-application.md) | Blocking JDBC inside a reactive application |
| [0007](0007-pgvector-for-the-semantic-cache.md) | pgvector for the semantic cache |
| [0008](0008-semantic-cache-threshold.md) | Semantic cache similarity threshold |
| [0009](0009-hmac-not-argon2-for-api-keys.md) | HMAC-SHA256, not Argon2id, for API keys |
| [0010](0010-alias-based-routing-and-failover.md) | Alias-based routing, and where failover stops |
