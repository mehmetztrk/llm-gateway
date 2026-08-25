# 0001 — Java 21, Spring Boot 3.5 and a single Gradle module

- **Status:** Accepted
- **Date:** 2026-08-25

## Context

Three foundational choices had to be made before any code existed.

**Java version.** Java 21 is the current LTS with virtual threads, records, sealed types and
pattern matching for `switch` — all of which this design leans on (sealed error hierarchies for
exhaustive HTTP mapping, records for immutable config and DTOs, virtual threads for the blocking
persistence edge described in ADR-0006).

**Spring Boot version.** At the time of writing, `start.spring.io` offers only 4.x. Spring Boot
3.5.x is the last 3.x line and remains under support. The relevant question is not which version is
newest but which one the *ecosystem this project depends on* is proven against: Resilience4j's
`resilience4j-spring-boot3` starter, the OpenTelemetry Spring Boot starter, Testcontainers'
`@ServiceConnection` integration and Flyway's Boot auto-configuration all have their longest track
record on the 3.x line.

**Module layout.** A hexagonal design invites splitting `domain`, `application` and `adapter` into
separate Gradle modules so the compiler enforces the dependency direction.

## Decision

- **Java 21**, pinned through a Gradle *toolchain* rather than `sourceCompatibility`, so Gradle
  provisions the correct JDK regardless of what is on `PATH` and CI compiles identically to the
  laptop.
- **Spring Boot 3.5.16**, with the dependency-management plugin owning all transitive versions.
  Project-specific versions live in `gradle/libs.versions.toml`; anything Boot's BOM already
  manages is *not* pinned there, to avoid the classic "works locally, NoSuchMethodError in CI"
  version skew.
- **One Gradle module**, with the layering enforced at build time by ArchUnit (`ArchitectureTest`)
  instead of by module boundaries.

## Consequences

- Boot 4.x migration becomes a deliberate future exercise rather than a day-one risk. This is a
  real trade-off: the repository will read as one major version behind for as long as it sits at
  3.5, and the migration is worth doing once the feature set is complete.
- ArchUnit gives roughly the same guarantee as multi-module for a fraction of the build complexity,
  and the rules are legible as code — a reviewer can read the constraint rather than infer it from
  a directory tree. What it does not give is protection at *compile* time: a violation is caught by
  `./gradlew check`, not by the IDE as you type.
- A second deployable (say, a separate admin service) would be the trigger to revisit the
  single-module decision.
- Spotless with palantir-java-format removes formatting from review entirely; the cost is that
  hand-tuned alignment is not preserved.
