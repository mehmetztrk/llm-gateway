# LLM Gateway

An AI control plane: a multi-tenant reverse proxy that puts **one OpenAI-compatible endpoint** in
front of several LLM providers, adding authentication, quotas, rate limiting, caching, failover,
cost accounting and distributed tracing.

Any OpenAI SDK works against it by changing `base_url` and nothing else.

> **Status: M5 of 10 — routed, with health-aware failover.** `/v1/chat/completions` works streamed and
> non-streamed against a local Ollama and a deterministic mock provider; the official `openai`
> Python SDK talks to it with only `base_url` changed. Requests now require an API key, which
> resolves to a tenant, its model allow-list, its per-minute limits and its monthly budget. Model
> aliases route to an ordered list of providers with circuit breaking and automatic failover.
> Caching, the usage ledger and tracing are not implemented yet — see [Roadmap](#roadmap). This notice is updated as milestones land, and no
> capability is claimed here before it exists and is tested.

## Demo console

With the gateway running, open **<http://localhost:8080/>** for a built-in console: pick a model,
watch tokens stream in, and read the raw SSE frames as they arrive. It is a single static HTML file
with no dependencies — the zero-budget rule applies to the demo too.

## Why this exists

Once an organisation has more than one team calling more than one model, the questions stop being
about prompts and start being about control: who is allowed to call what, who pays for it, what
happens when a provider degrades, and how you answer "why did that request cost €4". Those are
platform problems, and they belong in front of the model rather than inside every application.

The entire system runs on a laptop with `docker compose up` and **no API keys and no paid
services** — a deliberate constraint, not a limitation of the design. Providers sit behind a single
`LlmProvider` port, so adding a commercial provider is a configuration change.

## Architecture

```mermaid
flowchart LR
    SDK[OpenAI SDK<br/>base_url -> gateway] --> API["/v1/chat/completions"]

    subgraph Gateway["LLM Gateway (Spring WebFlux)"]
        API --> AUTH[API key auth<br/>tenant + policy]
        AUTH --> LIMIT[Rate limit<br/>token bucket]
        LIMIT --> QUOTA[Quota<br/>soft warn / hard block]
        QUOTA --> CACHE[Cache<br/>exact -> semantic]
        CACHE --> ROUTE[Routing<br/>health + circuit breaker]
        ROUTE --> LEDGER[Usage ledger<br/>+ cost]
    end

    LIMIT -.-> REDIS[(Redis)]
    QUOTA -.-> REDIS
    CACHE -.-> REDIS
    CACHE -.-> PG[(Postgres<br/>+ pgvector)]
    LEDGER -.-> PG

    ROUTE --> OP[Ollama primary<br/>GPU]
    ROUTE --> OS[Ollama secondary<br/>CPU, failover]
    ROUTE --> MOCK[MockProvider<br/>deterministic, for benchmarks]

    Gateway -.-> OTEL[OTel Collector<br/>Tempo · Prometheus · Grafana]
```

The request pipeline runs cheapest-and-most-likely-to-reject first: auth → model allow-list →
rate limit → quota → cache → routing → provider → ledger.

## Quick start

Requires Linux with Docker; `bootstrap-dev.sh` installs everything else.

```bash
git clone https://github.com/mehmetztrk/llm-gateway.git
cd llm-gateway
./scripts/bootstrap-dev.sh      # Java 21, Docker, NVIDIA toolkit, k6 (asks for sudo)
docker compose up -d            # Postgres + Redis + two Ollama instances
./scripts/pull-models.sh        # ~2 GB, one time
./gradlew check                 # build and test
```

Then run the gateway:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

```bash
curl -s http://localhost:8080/actuator/health
```

Send it a completion — `mock-fast` needs no model and no GPU. The `local` profile seeds a demo key:

```bash
curl -s http://localhost:8080/v1/chat/completions -H 'Content-Type: application/json' -H 'Authorization: Bearer llmgw_local_demo_key_do_not_use_in_production' -d '{"model":"mock-fast","messages":[{"role":"user","content":"hello"}]}'
```

Or prove SDK compatibility for yourself:

```bash
pip install openai && python scripts/verify-openai-sdk.py --model qwen2.5:1.5b-instruct --api-key llmgw_local_demo_key_do_not_use_in_production
```

## Authentication and tenancy

Every `/v1/**` request needs an API key, sent the way an OpenAI SDK sends it:

```
Authorization: Bearer llmgw_...
```

The key resolves to a **tenant** and that tenant's **model allow-list**. A model outside the list is
a 403 before any provider is contacted. Keys carry a role: `TENANT` may call `/v1/**`, `ADMIN` may
additionally manage tenants and keys through `/admin/**`.

Keys are stored as `HMAC-SHA256(pepper, key)` — never reversibly, and never with a password hash.
An API key is 256 bits of `SecureRandom`, so there is nothing to brute-force, while Argon2id on
every request would exceed the entire p99 budget and hand anyone a CPU-amplification attack. The
reasoning is written up in [ADR-0009](docs/adr/0009-hmac-not-argon2-for-api-keys.md).

A fresh database has no keys, so `gateway.security.bootstrap-admin-key` seeds the first admin
credential. It is **unset by default**: a deployment that forgets to configure one fails closed
rather than shipping a well-known default.

### Admin API

```bash
curl -s -X POST localhost:8080/admin/tenants -H 'Authorization: Bearer $ADMIN_KEY' -H 'Content-Type: application/json' -d '{"name":"acme","allowedModels":["mock-fast","qwen2.5:1.5b-instruct"]}'
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/admin/tenants` | Create a tenant with its allow-list |
| `GET` | `/admin/tenants` | List tenants |
| `PUT` | `/admin/tenants/{id}/models` | Replace the allow-list (takes effect immediately) |
| `POST` | `/admin/tenants/{id}/keys` | Issue a key — the plaintext is returned **once** |
| `GET` | `/admin/tenants/{id}/keys` | List key metadata, never the keys themselves |
| `PUT` | `/admin/tenants/{id}/limits` | Set per-minute limits and the monthly budget |
| `GET` | `/admin/providers` | Provider health, as routing currently believes it |
| `DELETE` | `/admin/keys/{id}` | Revoke a key (idempotent, soft delete) |

## Routing and failover

A **model alias** is a name a tenant asks for; it resolves to an ordered list of concrete
`(provider, model)` targets. The first healthy one serves the request, the rest are the failover
chain:

```yaml
chat-default:
  - { provider: ollama-primary,   model: qwen2.5:1.5b-instruct }   # GPU
  - { provider: ollama-secondary, model: llama3.2:1b }             # CPU fallback
```

A concrete model name still resolves to exactly one target, so clients that hardcode one keep
working and never get a silently substituted model. Failover is what aliases are for.

Every provider is probed in the background and wrapped in a Resilience4j circuit breaker. Health is
`UP`, `UNKNOWN` or `DOWN`; unhealthy providers are moved to the back of the list rather than removed,
so a stale belief cannot become a self-inflicted outage.

**Failover stops at the first streamed chunk.** Once a client holds part of an answer, switching
providers would splice two responses together, so the failure is reported in-band instead.

Measured by `FailoverChaosIT` with the primary failing every call:

```
failover latency: median 7 ms, worst 8 ms (target < 2000 ms)
```

The honest reading is not "failover takes 7 ms" but "once the probe has demoted the dead provider,
its outage is invisible to callers". Reasoning in
[ADR-0010](docs/adr/0010-alias-based-routing-and-failover.md).

## Rate limits and quotas

Four token buckets guard every request: requests-per-minute and tokens-per-minute, each enforced
per tenant *and* per key. A key without its own limit inherits its tenant's — the safe reading of
"not configured". Every response carries the headers an OpenAI SDK already knows:

```
x-ratelimit-limit-requests / -remaining-requests / -reset-requests
x-ratelimit-limit-tokens   / -remaining-tokens   / -reset-tokens
```

Refusals are `429` with `Retry-After`. A monthly token budget adds a soft threshold — a warning
header while there is still budget — and then a hard `429` with `insufficient_quota`, deliberately
*without* `Retry-After`, since waiting will not help until the next period.

Token counts are charged in two phases: admission spends an estimate of the prompt, and the real
total is settled once the provider reports it. A caller can overshoot by at most one response, which
then delays its next request.

**When Redis is unreachable the gateway refuses requests** (`503`, `rate_limiter_unavailable`)
rather than letting them through. A limiter that fails open turns a Redis outage into unlimited
access to every provider behind it. The cache in M6 makes the opposite choice for the opposite
reason — both are argued in [ADR-0004](docs/adr/0004-fail-closed-limits-fail-open-cache.md).

No NVIDIA GPU? Set `COMPOSE_FILE=docker/compose.yaml` in `.env` — everything still runs, on CPU.

## Stack

Java 21 · Spring Boot 3.5 (WebFlux) · Spring Security · Resilience4j · Redis · PostgreSQL +
pgvector + Flyway · OpenTelemetry (GenAI semantic conventions) → Tempo / Prometheus / Grafana ·
Testcontainers · JUnit 5 · AssertJ · WireMock · k6 · GitHub Actions. Everything free and
self-hosted.

## Privacy: prompts are not logged

`gateway.observability.log-prompts` defaults to `false` and a test pins that default.

A gateway is the one component that sees every prompt from every tenant, which makes it the worst
possible place to leak them. Prompts routinely contain customer names, support-ticket contents,
source code and credentials pasted by users; logs are replicated, retained and searched far more
widely than a request body ever is. Turning this flag on centralises all of that into a log
aggregator under a single retention policy nobody reviewed. It exists for local debugging and
should never be enabled against real traffic.

## Roadmap

| | Milestone | Status |
|---|---|---|
| M0 | Repo skeleton, compose stack, health endpoint, CI | ✅ done |
| M1 | Provider port, Ollama + Mock, non-streaming passthrough | ✅ done |
| M2 | SSE streaming, backpressure, mid-stream failure | ✅ done |
| M3 | Tenants, API keys, Flyway schema | ✅ done |
| M4 | Rate limiting, quotas, 429 semantics | ✅ done |
| M5 | Routing, circuit breaker, failover + chaos test | ✅ done |
| M6 | Exact + semantic cache, tenant isolation | ⬜ |
| M7 | Usage ledger, cost accounting, usage API | ⬜ |
| M8 | OTel GenAI spans, metrics, Grafana dashboard | ⬜ |
| M9 | k6 load tests, BENCHMARKS.md | ⬜ |
| M10 | Full README, ADR set, demo script, deploy notes | ⬜ |

## Documentation

- [CLAUDE.md](CLAUDE.md) — engineering standards and conventions this repo is held to
- [docs/adr/](docs/adr/) — architecture decision records

## License

MIT
