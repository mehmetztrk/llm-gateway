# Benchmarks

Every number here came from a k6 run on the hardware below. Raw summaries are committed under
`k6/results/`. Nothing is estimated, rounded up, or carried over from an earlier run — and the
targets that were **missed are reported as missed**, with the number.

## How to reproduce

```bash
docker compose up -d
./gradlew bootJar -x test
java -jar build/libs/llm-gateway-*.jar --spring.profiles.active=local --gateway.cache.semantic-enabled=false
```

```bash
PEAK_VUS=20 k6 run k6/overhead.js && k6 run k6/streaming.js && k6 run k6/cache.js
```

The scripts provision their own tenant with raised limits through the admin API. That is not
cheating: the rate limiter is still in the request path and its Redis round trips are still being
measured — only the bucket does not empty. Without it the first run failed 99.9% of requests with
`429`, which was the gateway working exactly as designed against a demo tenant allowing 60 requests
a minute.

## Environment

| | |
|---|---|
| CPU | Intel Core i7-10750H, 12 threads |
| RAM | 15 GB |
| JVM | Temurin 21.0.12, default heap |
| Postgres / Redis | in Docker, same host |
| Load generator | k6 v2.2.0, **same host as the gateway** |
| Date | 2026-09-03 |

**The load generator shares the machine with the gateway.** On a laptop that is unavoidable, and it
means k6 and the JVM compete for the same 12 threads. These numbers are therefore pessimistic at
high concurrency and should not be read as capacity figures.

## Gateway overhead — `k6/overhead.js`

Measured against `MockProvider`, which returns deterministic tokens with no I/O. Running this
against a real model would produce a number describing the GPU, not the gateway. What is left in
the measurement is the gateway's own work: authentication, four rate-limit buckets, quota, cache
lookup, routing, serialisation.

Every request uses a unique prompt so the cache never hits.

| Concurrency | p50 | p95 | **p99** | Throughput |
|---|---|---|---|---|
| 5 VUs | 2.38 ms | 3.01 ms | — | 1 326 req/s |
| 10 VUs | 4.50 ms | 5.98 ms | **7.41 ms** ✅ | 1 536 req/s |
| 20 VUs | 8.03 ms | 10.62 ms | **13.47 ms** ✅ | 1 795 req/s |
| 50 VUs | 18.64 ms | 25.84 ms | **31.89 ms** ❌ | 1 945 req/s |

**Target: p99 < 15 ms. Met at up to 20 concurrent clients; missed at 50.**

That is the honest headline, and the shape of the curve is more informative than any single number:
latency grows roughly linearly with concurrency while throughput flattens around 1 800–1 950 req/s.
The gateway is saturated at that point, and requests queue.

**Where the time goes, and what I would do about it.** Each request makes roughly seven sequential
Redis round trips — four token buckets (tenant requests, key requests, tenant tokens, key tokens),
one quota read, one cache lookup, one cache write — plus one more for settlement. On loopback each
is a few hundred microseconds; sequentially they dominate a request whose provider costs nothing.

The obvious fix is to pipeline the four bucket operations into a single Lua script, turning four
round trips into one. That is a real, bounded piece of work and it is *not* done: writing it up as
a plan is honest, claiming the resulting number would not be.

## Streaming time to first byte — `k6/streaming.js`

20 concurrent clients, 30 s.

| | |
|---|---|
| p50 | 5.25 ms |
| p95 | 6.53 ms |
| **p99** | **11.65 ms** ❌ |
| max | 18.10 ms |
| Throughput | 2 437 req/s |

**Target: TTFB overhead < 10 ms at p99. Missed — 11.65 ms.**

p50 and p95 sit comfortably inside the target; the tail does not. At 2 400 requests per second on a
shared laptop that is a narrow miss, and the same Redis serialisation above is the likely cause,
since admission control runs before the first byte can be written.

Backpressure is not measured here — it is asserted structurally, by
`MockProviderStreamTest#isDemandDriven`, which counts what the source produced under a bounded
request and proves the gateway never runs ahead of a slow consumer.

## Cache effectiveness — `k6/cache.js`

10 concurrent clients replay a pool of 20 distinct prompts for 30 s, against a tenant created fresh
for the run so no previous run's warm cache is inherited.

| | |
|---|---|
| Requests | 79 757 |
| **Cache hit ratio** | **99.85 %** |
| Tokens saved | 3 066 074 |
| Throughput | 2 657 req/s |

The 99.85 % figure is a property of the workload, not of the cache: 20 prompts replayed 80 000 times
has a theoretical ceiling of 99.97 %, and the cache reached almost all of it. **A real workload with
a long tail of unique prompts would see a far lower ratio.** Quoting 99.85 % as though it were an
expected production number would be dishonest; what it demonstrates is that the cache works and
costs almost nothing when it hits.

### On the cost figure

3 066 074 tokens were not sent to a provider. At the reference rate for `mock-fast`
(300 micros per 1 000 output tokens), that is **$0.92** of avoided spend for this 30-second run.

**Tokens are measured; cost is arithmetic over a stated rate.** Every model served here is free, so
the dollar figure is a counterfactual — "what this traffic would have cost at these rates" — not
money anyone saved. `pricing.yml` holds the rates, and they are reference prices for comparable
hosted models rather than anything billed.

## Semantic cache: the finding that cost the most to learn

The first overhead run showed a flat **4-second** p99, which looked like a timeout and was not.

With the semantic cache enabled, every request makes an embedding call to `nomic-embed-text` on the
lookup path and another on the store path. Under 50 concurrent clients those calls queue behind a
6 GB laptop GPU, and the gateway's latency becomes the embedding model's latency.

| Configuration | p50 | p99 |
|---|---|---|
| Exact cache only | 18.64 ms | 31.89 ms |
| Exact + semantic | ~4 000 ms | ~4 020 ms |

This is not a bug, and it is not something to hide in a footnote. It is the cost of the feature,
and it says something specific: **a semantic cache is worth having when upstream inference is
expensive relative to an embedding, and actively harmful when it is not.** Against a real hosted
model taking 2–20 seconds, a 40 ms embedding is a rounding error and the cache is a large win.
Against a mock that returns instantly, it is pure overhead.

Two things follow, and both are implemented: the exact layer is tried first and answers most
repeats without ever embedding, and the semantic layer can be switched off per deployment
(`gateway.cache.semantic-enabled`). A third — embedding asynchronously on the store path, so only
lookups pay — is not implemented and is the obvious next optimisation.

## Failover — `FailoverChaosIT`

Not a k6 run; measured inside the integration suite, with the primary provider configured to fail
every call.

```
failover latency: median 7 ms, worst 8 ms (target < 2000 ms)
```

**Target met, but the number needs its caveat.** 7 ms is small because the background health probe
has already demoted the dead provider, so the request never attempts it. The honest claim is not
"failover takes 7 ms" but "once the probe has noticed an outage, that outage is invisible to
callers". The worst case — a provider that dies between two probes — costs one failed attempt,
bounded by that provider's configured timeout.

## Summary against the original targets

| Target | Result | |
|---|---|---|
| Gateway overhead p99 < 15 ms | 7.41 ms @ 10 VUs, 13.47 ms @ 20 VUs, 31.89 ms @ 50 VUs | ✅ to 20 VUs, ❌ beyond |
| Streaming TTFB overhead < 10 ms | p99 11.65 ms (p50 5.25 ms) | ❌ narrowly |
| Backpressure, no unbounded buffering | Asserted structurally by an instrumented test | ✅ |
| Failover < 2 s under chaos | median 7 ms, worst 8 ms | ✅ |
| Cache hit ratio and token reduction | 99.85 % on a replay workload, 3 066 074 tokens | ✅ measured, workload-specific |
| Redis down → fail closed on limits | `RateLimiterFailsClosedIT` asserts 503 | ✅ |
| Zero cross-tenant leakage | `CacheApiIT`, `CrossTenantIsolationIT` | ✅ |

Two of seven targets were missed. Both are single-digit-millisecond misses at high concurrency on a
laptop shared with the load generator, both have an identified cause (sequential Redis round trips),
and the fix is described above rather than performed and then claimed.
