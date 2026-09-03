# 0005 — Exact cache first, semantic cache second

- **Status:** Accepted
- **Date:** 2026-09-03

## Context

Two caches were specified: an exact-match cache and a semantic one. The question is not whether to
have both, but what order they run in and what each is allowed to do.

## Decision

Exact first, semantic second, and both scoped by tenant *and* model.

## Consequences

**The order is a correctness argument, not a preference.** An exact hit is unconditionally right:
same tenant, same model, same prompt, same sampling parameters, therefore the same answer. A
semantic hit is right only if the similarity threshold is right (ADR-0008). Consulting the cheap,
certain answer first means the uncertain one is only ever reached when the certain one has nothing
to offer.

The cost argument points the same way. An exact lookup is one Redis key; a semantic lookup is an
embedding call plus a vector scan. Trying the exact layer first costs almost nothing when it misses
and saves the expensive path when it hits.

**Both layers are written on a miss.** Writing only to the semantic layer would make every literal
repeat pay for an embedding and a vector scan to learn something a hash lookup already knew.

**What is in the key, and why each part is there.**

- **Tenant** — not a filter applied afterwards but part of the key itself. That is the difference
  between isolation by construction and isolation as long as nobody forgets a `WHERE` clause.
- **Model** — a tenant that asked for a large model must not be served a small model's answer. That
  is a different product at a different price.
- **Sampling parameters** — caching across temperatures would hand a deterministic answer to
  someone who explicitly asked for a varied one.
- **Messages, length-prefixed** — plain concatenation would make `["ab","c"]` and `["a","bc"]`
  collide, and a cache collision across prompts is a wrong answer served with confidence.

**The cache sits after admission control, not before.** A cache consulted first would make repeated
requests free of rate limiting, so a tenant could hammer the gateway without limit as long as it
repeated itself — and a cache outage would then also be an outage of admission control. This
ordering is also what makes fail-open safe: see ADR-0004.

**A cache hit charges no tokens to the quota.** No tokens were spent; charging for them would erase
the saving the cache exists to produce. The *request* was still charged against the rate limiter at
admission, so repetition remains bounded.

**Cached answers are replayed as streams.** A client that asked for a stream gets a stream, cached
or not, delivered word by word. Returning a single blob because the answer happened to be cached
would make cache hits visibly different from misses and break incremental rendering on exactly the
requests that were supposed to be fastest.
