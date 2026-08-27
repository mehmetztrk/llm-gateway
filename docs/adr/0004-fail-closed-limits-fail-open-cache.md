# 0004 — Fail closed on rate limiting, fail open on caching

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

Both the rate limiter and (from M6) the cache live in Redis. When Redis is unreachable, each one
faces the same question and, it turns out, needs the opposite answer.

The instinct is to pick one policy and apply it consistently. That instinct is wrong here, and the
reason is worth being precise about: **the two components fail in opposite directions.**

## Decision

- **Rate limiting and quotas fail closed.** If the limiter cannot answer, the request is refused
  with `503` and code `rate_limiter_unavailable`.
- **The cache fails open.** If the cache cannot answer, the request proceeds to the provider as
  though it were a miss.

## Consequences

**Why the limiter fails closed.**

A limiter that fails open converts an outage of Redis into *unlimited access to every provider
behind the gateway*. That is precisely the situation limits exist to prevent, and it is worst
exactly when it is most likely: a Redis under load is often a Redis under load *because* traffic
spiked. The moment a paid provider is configured, "fail open" also means an unbounded bill with no
upper limit and no alert until the invoice.

The failure mode of fail-closed is honest and bounded: requests are refused with a 503 that says
why, an operator sees it immediately, and nothing is spent. The cost is real — a Redis outage
becomes a gateway outage — and it is the cost we choose.

Three things keep that cost as small as possible:

- The Redis timeout is 250 ms, so a slow Redis fails fast instead of adding its latency to every
  request.
- `/actuator/health/liveness` stays reachable, so a Redis blip does not make every replica look dead
  and trigger a rolling restart.
- Tenants with no monthly budget never touch Redis for quota at all, so they keep working.

**Why the cache fails open.**

A cache that fails closed converts an outage of an *optimisation* into an outage of the service. The
cache exists to avoid work; when it is unavailable the correct behaviour is to do that work. Serving
a request the slow way is the same outcome a cache miss produces, and cache misses are normal.

There is no correctness argument on the other side either: a cache miss cannot leak data, overspend
a budget, or bypass a limit, because rate limiting and quota checks run *before* the cache is
consulted. The pipeline order is what makes fail-open safe here — reverse it, and a cache outage
would also bypass admission control.

**The rule, stated generally.** Fail closed when the component's job is to say no; fail open when
its job is to say faster. Anything that enforces a policy fails closed. Anything that only avoids
work fails open.

**What this is not.** It is not a claim that fail-closed is always right. If this gateway were ever
placed in front of something where availability mattered more than spend — an internal
non-billable model, say — the trade would be worth revisiting per provider rather than globally.

## Verification

`RateLimiterFailsClosedIT` points Redis at a port nothing listens on and asserts a `503`, not a
`200`. The corresponding cache test lands with M6 and will assert the opposite.
