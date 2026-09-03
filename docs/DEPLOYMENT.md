# Deployment notes

This project is built to run on a laptop with `docker compose up`. Nothing here has run in
production, and the notes below say what would have to change before it did — which is more useful
than a deployment guide that implies it already has.

## What must be set before anything real

| Setting | Why it cannot be left alone |
|---|---|
| `gateway.security.key-pepper` | Mixed into every API-key digest. The default is the literal string `change-me-in-production`, and the application logs a warning when it is still in use. Rotating it invalidates every issued key — which is the intended emergency lever. |
| `gateway.security.bootstrap-admin-key` | **Unset by default.** A fresh database has no keys and no way to create one, so this seeds the first admin credential. Unset means the deployment fails closed rather than shipping a well-known default. Set it once, create a real key through the admin API, then remove it. |
| `POSTGRES_PASSWORD`, Redis auth | Compose uses `llmgw/llmgw` because it is a local demo. Redis is unauthenticated for the same reason. |
| `gateway.observability.log-prompts` | Already `false`, and a test pins it. It exists for local debugging and must never be true against real traffic. |
| `management.tracing.sampling.probability` | `1.0` here because a demo with sampled-out traces shows nothing. Any real volume needs a lower rate. |

## Scaling

**The gateway is stateless and horizontally scalable.** Everything a replica needs is in Postgres or
Redis. Three pieces of state are deliberately *not* shared, and each is a considered choice rather
than an oversight:

- **The API-key cache** (Caffeine, per instance). A revoked key keeps working for up to
  `gateway.security.cache-ttl` on replicas that did not process the revocation. That TTL *is* the
  revocation SLA and it is configuration, not an accident.
- **Provider health** (in memory, per instance). Health is an observation about *this instance's*
  ability to reach a provider; a replica in another zone may legitimately see something different,
  and averaging those produces a number true for nobody. See ADR-0010.
- **Circuit breaker state** (Resilience4j, per instance). Same reasoning. Each replica learns about
  an outage independently, which with live traffic feeding the registry takes a few requests.

Rate limits and quotas *are* shared, through Redis, because a limit that each replica enforced
separately would be N times the configured limit.

### Sizing

`BENCHMARKS.md` measured ~1 800 req/s per instance against a mock provider on a 12-thread laptop
that was also running the load generator. Treat that as a floor, not a capacity figure. The
bottleneck is seven sequential Redis round trips per request; the identified fix — pipelining the
four rate-limit buckets into one Lua script — is not implemented.

Hikari is capped at 10 connections because the database is off the request hot path by design. If a
future feature puts a database read back on that path, this is the first number to revisit.

## Health and probes

- `/actuator/health/liveness` — the process is alive. **Deliberately unaffected by provider health
  or Redis availability**: a Redis blip must not make every replica look dead and trigger a rolling
  restart of the whole fleet.
- `/actuator/health/readiness` — ready to serve.
- Provider health is at `/admin/providers` and is *not* an actuator health indicator. A provider
  being down is not a reason to restart or de-pool the gateway — routing around it is the gateway
  working correctly.

## Database

Flyway migrations run at start-up. Four so far; `V3` requires the `vector` extension, which is why
the image is `pgvector/pgvector` rather than stock Postgres.

**The usage ledger grows without bound.** There is no retention policy, because the right one
depends on how long an operator must be able to answer "why did that request cost that" — a
question this project cannot answer for someone else. A production deployment needs a partitioning
or archival strategy before the table becomes the largest thing in the database.

## What is deliberately absent

- **No Kubernetes manifests.** They would be untested guesses.
- **No TLS termination.** Expected to be handled by whatever sits in front.
- **No horizontal-scaling test.** The design is stateless and the shared state is in Redis and
  Postgres, but "should scale" is an argument, not a measurement, and it is labelled as such.
- **No auth beyond API keys.** No OAuth, no OIDC, no per-user identity — a documented limitation,
  not an oversight.
