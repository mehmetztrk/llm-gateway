# LLM Gateway — Agent Instructions

Multi-tenant, OpenAI-compatible reverse proxy in front of several LLM providers.
Auth, quotas, rate limiting, caching, failover, cost accounting, tracing.

## Prime directives

1. **Zero budget.** No paid APIs, no paid cloud, no paid SaaS. Everything must run from
   `docker compose up` on a laptop with no credentials. A dependency that needs a key to *boot*
   is a bug.
2. **Never invent numbers.** Every figure in BENCHMARKS.md, the README or a commit message comes
   from an actual k6 run whose raw output is committed. No estimates, no "approximately", no
   numbers carried over from a previous run. A missed target is reported as missed.
3. **Provider-agnostic.** Adding a paid provider later must be a config change, never a code
   change outside `adapter/out/provider/`.
4. **Every feature ships with tests.** A milestone is not done until `./gradlew check` is green.

## Commands

| Task | Command |
|---|---|
| Build + all tests | `./gradlew check` |
| Unit tests only (fast loop) | `./gradlew test` |
| Integration tests (Testcontainers) | `./gradlew integrationTest` |
| Format | `./gradlew spotlessApply` |
| Run locally | `./gradlew bootRun --args='--spring.profiles.active=local'` |
| Infrastructure | `docker compose up -d` |
| Infrastructure + gateway container | `docker compose --profile app up -d --build` |
| Download Ollama models (once) | `./scripts/pull-models.sh` |
| Load test | `k6 run k6/<scenario>.js` |

Compose is driven from the repository root; `.env` sets `COMPOSE_FILE` to
`docker/compose.yaml:docker/compose.gpu.yaml`. Drop the GPU overlay for a CPU-only machine.

## Toolchain

Java 21 (Temurin, via SDKMAN) · Spring Boot 3.5.16 · Gradle 8.14.5 with a version catalog
(`gradle/libs.versions.toml`) · Spotless with palantir-java-format.

## Architecture rules (enforced by `ArchitectureTest`)

- `domain/**` imports **nothing** from Spring, Reactor, Jakarta, Jackson, Resilience4j or
  OpenTelemetry. Pure Java.
- `application/**` may import `domain/**` and Reactor. It defines **ports** (interfaces); it never
  imports `adapter/**`.
- `adapter/**` implements ports. Adapters never import each other.
- Dependency direction is always inward: adapter → application → domain.

New ArchUnit rules use `.allowEmptyShould(true)`; packages fill in milestone by milestone.

## Non-negotiable conventions

- **Constructor injection only.** Field `@Autowired` fails the build (ArchUnit), test sources
  excluded.
- **Immutable `@ConfigurationProperties` records** with `@DefaultValue` on nested types, discovered
  via `@ConfigurationPropertiesScan`. No `@Value` scattered through classes.
- **Records for DTOs and domain values.** Sealed hierarchies where the set of cases is closed, so
  the compiler catches an unmapped case instead of production returning a 500.
- **No blocking calls on the event loop.** JDBC, file IO and `Thread.sleep` are forbidden in any
  method returning `Mono`/`Flux` unless explicitly moved to a bounded scheduler. See ADR-006.
  BlockHound is wired into tests from M2 to catch violations.
- **No prompt content in logs by default.** Gated behind `gateway.observability.log-prompts`
  (default `false`, pinned by a test). See README "Privacy".
- **Structured JSON logs** via Spring Boot's native ECS format, with `trace_id`, `tenant_id` and
  `request_id` in the MDC.
- **Conventional commits**, small and focused: `feat(cache): add tenant-scoped semantic lookup`.
- English everywhere — code, comments, commits, docs.

## Testing rules

- **No `Thread.sleep`, ever.** Use `StepVerifier.withVirtualTime` for reactive time, Awaitility for
  async assertions, and an injected `Clock` port for anything time-based.
- Testcontainers for Redis and Postgres, declared `@Container static` and shared per class, wired
  with `@ServiceConnection` so no manual property plumbing. Let Spring Boot's BOM pin the
  Testcontainers version — do not override it.
- **MockProvider is the only provider used in performance tests.** A real model would make the
  model, not the gateway, the variable under test.
- WireMock for `OpenAiCompatibleProvider` contract tests.
- Every provider implementation must pass the shared `LlmProviderContractTest` suite.

## Teaching mode

The author is a Python/FastAPI engineer learning Java and must defend this code in interviews.
When introducing a non-obvious idiom (Reactor operator, virtual threads, Resilience4j annotation,
Testcontainers lifecycle, Gradle configuration), add a brief `why this, not that:` note in the code
or a short paragraph in the response. Name the rejected alternative and the reason.

## Milestone protocol

Work one milestone at a time. Per milestone: state the design in a few sentences, implement, run
the tests, report the real output, then list exactly what the human should verify manually. Then
stop and wait.

## Deliberately out of scope

- No Kubernetes manifests — deploy notes in the README only.
- No paid observability. OTel Collector → Tempo + Prometheus + Grafana, all local.
- No auth beyond API keys (no OAuth/OIDC) — documented as a limitation, not an oversight.
