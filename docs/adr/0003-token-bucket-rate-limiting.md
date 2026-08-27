# 0003 — Token bucket, not fixed or sliding window

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

Four algorithms were on the table for "no more than N per minute".

**Fixed window.** Count requests in the current minute, reset at the boundary. Cheap — one `INCR`
and an `EXPIRE`. Its flaw is not subtle: a client that sends N requests at 11:59:59 and N more at
12:00:00 has stayed inside every window and delivered 2N in one second. For a gateway in front of a
GPU, that burst is the failure the limit exists to prevent.

**Sliding window log.** Store a timestamp per request in a sorted set, drop the ones older than a
minute, count what is left. Exact. It also stores one entry per request per caller, which for a
tenant doing 10k requests a minute is 10k set members that must be trimmed on every call.

**Sliding window counter.** Interpolate between the current and previous fixed windows. Cheap and
much better than fixed, but approximate in a way that is hard to explain to whoever is being
throttled by it.

**Token bucket.** Capacity N, refilling at N per minute. Two numbers per bucket, and a bounded burst
of at most N that is a deliberate, documented property rather than an accident of where the window
boundary fell.

## Decision

Token bucket, evaluated in a single Lua script inside Redis, with lazy refill.

## Consequences

- **The state is two numbers**, `tokens` and `ts`, regardless of traffic. Memory is a function of
  how many tenants exist, not of how busy they are — the property the sliding window log lacks.
- **Bursts are bounded and intentional.** A caller idle for a minute may spend its whole allowance
  at once. That is the right behaviour for an API used by batch jobs, and it is the same model
  OpenAI's own limits use, so client-side pacing libraries already expect it.
- **Refill needs no scheduler.** The bucket is recomputed arithmetically from elapsed time whenever
  it is next touched. No background job, nothing to run on exactly one node, and an idle tenant
  costs nothing at all because its key has expired.
- **The whole decision is one round trip and atomic.** A read-then-write across two calls would let
  concurrent requests all observe capacity and all take it, turning a limit of N into N plus however
  many raced. `RedisTokenBucketRateLimiterIT#isAtomicUnderConcurrency` fires 200 concurrent requests
  at a bucket of 50 and asserts the count.
- **Two dimensions, four buckets.** Requests and tokens are limited independently, per tenant and
  per key. A caller sending one enormous prompt a second is cheap by request count and ruinous by
  token count; a limiter that counted only requests would wave it through.
- **The cost is that tokens must be settled after the fact.** The completion length is unknowable
  before the model has produced it, so admission spends an estimate of the prompt and the real total
  is charged afterwards. A caller can exceed its token rate by at most one response, which then
  delays its next request. The alternative — refusing anything that *might* not fit — would reject
  work that would have fitted, every time.
- **Time is supplied by the caller, not read inside the script.** Scripts that read the clock are
  non-deterministic, which historically made them unsafe to replicate, and it makes them impossible
  to test without sleeping. Passing the time in lets a test drive the bucket with a fixed clock.

## Alternatives considered

- **Fixed window.** Rejected: the boundary burst is exactly the thing being defended against.
- **Sliding window log.** Rejected on memory and trim cost; the exactness it buys is not worth
  storing an entry per request.
- **Leaky bucket (queue).** Smooths output but makes callers wait rather than telling them to slow
  down. A gateway holding requests open is a gateway holding connections open.
- **A library such as Bucket4j or Resilience4j's RateLimiter.** Both are good; both would still need
  the Redis script for the distributed case, and this is one file of Lua whose behaviour is the
  thing a reviewer most wants to read.
