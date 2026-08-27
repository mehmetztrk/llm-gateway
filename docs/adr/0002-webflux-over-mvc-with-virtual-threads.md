# 0002 — WebFlux for the proxy path, not MVC with virtual threads

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

Java 21 makes this a real question rather than a formality. Before virtual threads, "high
concurrency on the JVM" meant reactive, and the debate was over. Now `spring-boot-starter-web` on
virtual threads gives you thread-per-request semantics with a scheduler that no longer collapses at
a few thousand concurrent requests — with plain, debuggable, stack-trace-friendly blocking code.

So the honest question is: what does this specific workload need that virtual threads do not give?

The workload is unusual in one important way. This gateway is almost pure I/O with almost no
computation: it accepts a request, forwards it, and relays tokens back. A single streamed
completion can stay open for **minutes**, producing a few hundred bytes a second. A hundred
concurrent streams is not an exotic load for a shared control plane — it is a Tuesday.

## Decision

Spring WebFlux for the request path.

## Consequences

**What decided it: backpressure, not concurrency.**

Virtual threads solve *how many* requests can be in flight. They do not, on their own, give a way
to say *"do not read from upstream faster than the client is consuming"*. In a thread-per-request
model, relaying a stream means a loop that reads from the provider and writes to the client; when
the client is slow, that loop blocks on the write, and whatever the HTTP client has already buffered
sits in memory. With `OutputStream`-based streaming there is no demand signal travelling upstream —
only a socket buffer filling up, and the gateway's own heap absorbing the difference.

Reactive Streams makes that demand signal explicit and end to end: the client's slow consumption
propagates through Netty, through the operator chain, into the WebClient response, and ultimately
into the TCP window on the provider connection. That is the mechanism behind the
"no unbounded buffering" target, and `MockProviderStreamTest#isDemandDriven` asserts it directly by
counting what the source produced under a bounded request.

A gateway is exactly the component where this matters. It has no answer of its own — it is a pipe,
and a pipe's job is to not become a bucket.

**What we pay for it.**

- **Stack traces are assembly traces.** A failure in an operator chain shows Reactor internals, not
  the path through our code. Mitigated by keeping chains short and by `checkpoint()` where it earns
  its place, but this is a genuine and permanent cost to debuggability.
- **The blocking rule becomes absolute.** One `Thread.sleep`, one JDBC call on an event-loop thread,
  and throughput collapses in a way that load testing may not reveal until production. This is why
  BlockHound runs in tests and why ADR-0006 has to exist at all: Flyway and Spring Data JDBC are
  blocking, so the persistence edge needs deliberate, documented handling rather than a shrug.
- **A steeper learning curve**, which matters here — the author is learning Java on this project.
  Reactor is a second language on top of Java, and code that "looks right" can be subtly wrong
  (eager evaluation at assembly time, missing `defer`, hot vs cold publishers). Two of those exact
  mistakes were caught by tests during M1 and M2.
- **Fewer libraries.** Anything blocking is off the table or needs wrapping.

**When this would be the wrong call.** If the gateway were request/response only, with no streaming,
MVC on virtual threads would be the better choice on every axis that matters: simpler code, better
stack traces, a larger pool of maintainers who can read it, and equivalent throughput. The
justification for WebFlux here rests entirely on streaming relay with backpressure. If streaming
were ever dropped, this decision should be revisited rather than defended.

## Alternatives considered

- **MVC + virtual threads.** Rejected for the backpressure reason above, not for throughput.
- **MVC + virtual threads, with a hand-rolled bounded queue per stream.** This is essentially
  rebuilding Reactive Streams badly, in application code, with our own bugs.
- **A dedicated proxy (Envoy, nginx) in front.** Solves relaying, but the tenant-aware logic —
  quotas, semantic caching, cost accounting — is the actual product, and it needs to sit in the
  data path, not beside it.
