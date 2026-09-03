# 0010 — Alias-based routing, and where failover stops

- **Status:** Accepted
- **Date:** 2026-09-03

## Context

M5 asked for "failover to secondary on failure". Implementing it exposed a gap in the model: the
primary Ollama serves `qwen2.5:1.5b-instruct` and the secondary serves `llama3.2:1b`. They are
different models. There was nothing to fail *over to*, because a request naming one model cannot be
satisfied by a provider that does not have it.

Answering the request anyway with a different model would be the gateway silently substituting the
product for something else — a decision an infrastructure component does not get to make on a
client's behalf without being told to.

## Decision

**Introduce model aliases.** An alias is a name a tenant asks for; it resolves to an ordered list of
concrete `(provider, model)` targets. The first healthy target serves the request, and the rest are
the failover chain.

```yaml
chat-default:
  - { provider: ollama-primary,   model: qwen2.5:1.5b-instruct }   # GPU
  - { provider: ollama-secondary, model: llama3.2:1b }             # CPU fallback
```

**A concrete model name resolves to exactly one target.** Naming a model is a statement about
*where*, not only *what*, so it gets no failover. Failover is what an alias is for, and asking for
one is how a tenant says "I care about the answer more than about which model produced it".

## Consequences

- The substitution becomes an explicit, operator-owned decision, visible in configuration and
  printed at start-up.
- Clients that hardcode a model name keep working unchanged, so the OpenAI compatibility promise
  survives.
- **The one-target rule is not a detail.** An earlier version returned every provider serving the
  same name, and identical requests started returning different answers depending on which provider
  answered — caught by a test asserting that streamed and non-streamed responses agree. Failover
  and determinism pull in opposite directions; aliases are where the trade is made explicit.

### Where failover stops: mid-stream

Failing over a non-streamed call is simply "try the next one". A stream that has already delivered
chunks cannot be retried anywhere — the client holds part of an answer, and starting a second
provider would splice two different responses together and hand the result over as one.

So failover is allowed **strictly before the first chunk**. After that the failure propagates to
the client as an in-band error frame with no `[DONE]`, which is how a client tells a truncated
answer from a complete one. `FailoverExecutorTest` asserts both halves, including that the fallback
provider is never contacted once the first chunk has gone out.

### Health: three states, and why the probe matters more than it looks

Health is `UP`, `UNKNOWN` or `DOWN`. `UNKNOWN` exists because "we have not asked yet" is genuinely
different from "we asked and it failed": treating an unprobed provider as down makes every cold
start an outage, and treating it as up sends the first request into the dark.

Providers believed `DOWN` are **moved to the back, not removed**. If every candidate looks down the
request is still attempted, so a stale belief cannot become a self-inflicted outage.

Live traffic and a background probe both feed the registry, and they do different jobs. Traffic
tells you a provider is broken. Only a probe tells you it is *fixed*, because routing deliberately
stops sending traffic to a provider it believes is down — without a probe, nothing would ever
observe the recovery.

The probe also turned out to matter more than expected. The first version of the chaos test assumed
user requests would keep hitting the dead provider until enough of them had failed to mark it down.
They do not: the probe gets there first, so by the time traffic arrives routing already prefers the
provider it has seen answer. **The dead provider costs users nothing at all.** That is the more
valuable half of the design, and the test now asserts it directly.

Health is held in memory, per instance, and deliberately not shared in Redis. It is an observation
about *this instance's* ability to reach a provider; a replica elsewhere may legitimately see
something different, and averaging those produces a number true for nobody. Sharing it would also
put a network round trip on the routing path, which is the one place that cannot afford one.

### The circuit breaker earns its keep separately from failover

Failover handles a request that fails. A breaker handles the thousandth request that is *going* to
fail: without one, every request keeps paying the full timeout against a dead provider before moving
on, so an outage becomes latency on every request rather than a switch to the secondary.

Resilience4j's `CallNotPermittedException` is translated into `ProviderCallFailed` at the adapter
boundary, so routing treats an open circuit exactly like any other failure. Leaking the library's
type would make the routing layer depend on a resilience library it should know nothing about.

Probes deliberately bypass the breaker. An open breaker means "stop sending traffic", not "stop
checking" — if probes were refused too, nothing would observe the recovery that closes it.

## Measured

`FailoverChaosIT` records the wall-clock time of requests served while the primary fails every call:

```
failover latency: median 7 ms, worst 8 ms (target < 2000 ms)
```

That number is small because the probe has already demoted the dead provider, so the request is not
paying for a failed attempt first. The honest reading is not "failover takes 7 ms" but "a provider
outage is invisible to callers once the probe has noticed it, and the probe notices within a few
hundred milliseconds". The worst case — a provider that dies between two probes — costs one failed
attempt, bounded by that provider's timeout.
