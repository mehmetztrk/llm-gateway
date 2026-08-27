# 0006 — Blocking JDBC inside a reactive application

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

ADR-0002 chose WebFlux, whose central rule is that nothing may block an event-loop thread. This
milestone introduces PostgreSQL, and the obvious tension follows: JDBC is blocking by definition.

The reactive-purity answer is R2DBC. It is a real option and it was seriously considered. What it
costs is not small:

- **Flyway is JDBC-only.** Schema migration would need a second, blocking datasource anyway, so the
  application would carry two database stacks rather than one.
- **No `@Transactional` over the familiar Spring JDBC machinery**, a different exception hierarchy,
  and a materially smaller pool of engineers who can read the result.
- **Testcontainers, Spring Data JDBC and `JdbcClient` are the best-documented paths**; R2DBC is
  well-supported but thinner, and this repository is also a teaching artefact.

The decisive question turned out not to be "which driver is more reactive" but **how often the
database is on the request path at all**.

## Decision

Use blocking JDBC (`JdbcClient` + HikariCP + Flyway), and keep it off the event loop by two
mechanisms used together:

1. **The database is not on the hot path.** Authentication is the only per-request database need,
   and `CachingTenantRepository` answers it from Caffeine. A cache hit touches no connection, no
   socket and no scheduler.
2. **Every actual blocking call is explicitly offloaded**, with
   `Mono.fromCallable(...).subscribeOn(blockingScheduler)`, where `blockingScheduler` is backed by
   `Executors.newVirtualThreadPerTaskExecutor()`. There are exactly two such call sites:
   `ApiKeyAuthenticationFilter` (cache misses) and `AdminController` (rare, human-driven).

The offload is written out at each call site rather than hidden behind a helper. Verbosity is the
point: a helper makes it easy to add a third call site that forgets the hop.

## Consequences

- **Virtual threads are what make this comfortable.** `Schedulers.boundedElastic()` caps at roughly
  ten platform threads per core and queues beyond that, so a burst of cache misses would queue
  behind a small pool. A virtual thread costs a few hundred bytes and parks without holding an OS
  thread, so the effective limit becomes the Hikari pool — which is the thing that *should* be the
  limit. This is the one place in the codebase where Java 21's virtual threads earn their keep,
  precisely because it is the one place with genuinely blocking code.
- **The rule is now testable, not aspirational.** BlockHound runs in its own Gradle task
  (`blockHoundTest`) and fails the build if the completion pipeline blocks a non-blocking thread.
  It is scoped to its own JVM because `BlockHound.install()` is global and would otherwise trip on
  framework code during Spring start-up.
- **The cost is a rule people must remember.** Nothing in the type system stops someone calling
  `TenantRepository` directly from a `Mono` chain. The port is deliberately declared as blocking
  (returning `Optional`, not `Mono`) so that the boundary is visible in the signature rather than
  disguised — a port that returned `Mono` would look safe and be a trap.
- **Revisit if** the request path ever needs a database read that cannot be cached — a per-request
  write, say, on the critical path rather than the buffered ledger planned for M7. At that point
  the balance shifts and R2DBC deserves another look.

## Alternatives considered

- **R2DBC throughout.** Rejected for the reasons above; the deciding factor was Flyway forcing a
  second stack regardless.
- **MVC with virtual threads instead of WebFlux**, which would make this ADR unnecessary. Rejected
  in ADR-0002 for backpressure reasons, not throughput ones.
- **Blocking calls straight on the event loop, "because the cache makes it rare".** Rejected: rare
  is not never, and the failure mode is not a slow request but a stalled event loop taking every
  other in-flight request with it.
