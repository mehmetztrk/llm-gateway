# 0009 — HMAC-SHA256, not Argon2id, for API key storage

- **Status:** Accepted
- **Date:** 2026-08-27
- **Supersedes:** the M3 acceptance criterion in the original milestone plan, which specified
  Argon2id. That criterion was written before the hot-path cost was thought through, and is wrong.

## Context

Keys must be stored so that a database dump cannot be used to call the API. The reflex answer is
"use a password hash — bcrypt, scrypt, Argon2id", and that reflex is correct for passwords. An API
key is not a password.

| | Password | API key here |
|---|---|---|
| Entropy | ~30 bits, human-chosen, reused | 256 bits from `SecureRandom` |
| Guessable | Yes — dictionaries, patterns | No, by construction |
| Verified | On login, occasionally | **On every single request** |

Argon2id exists to make guessing a low-entropy secret expensive. Against a 256-bit random value
there is nothing to guess, so the deliberate slowness buys nothing — and it is charged on every
request.

## Decision

Store `HMAC-SHA256(pepper, key)` as hex. The pepper lives in configuration
(`gateway.security.key-pepper`) and never in the database.

## Consequences

**Why not Argon2id.**

- **Latency.** Argon2id at defensible parameters costs tens to hundreds of milliseconds. The
  gateway's entire p99 overhead budget is 15 ms. One hash would exceed the whole budget by an order
  of magnitude, and no amount of caching fixes the cold case.
- **It is an amplification attack waiting to happen.** Authentication runs before any authorisation,
  so anyone on the network can force it. With a password hash, each request with a random key costs
  the server a full Argon2 computation while costing the attacker nothing. A cache does not help:
  misses are exactly what such traffic produces. `CachingTenantRepository` caches negative lookups
  for this reason, but the hash itself must be cheap regardless.

**Why not a bare SHA-256.**

- A bare digest can be verified offline by anyone holding the table. The pepper means a database
  dump alone is insufficient — an attacker needs the application's configuration too, which is a
  meaningfully different compromise.
- Rotating the pepper invalidates every key at once, without touching the database. That is a
  useful emergency lever to have.

**What this costs.**

- The pepper is now a secret with a lifecycle: it must be set, stored and rotated deliberately. A
  deployment that leaves it at the default gets a start-up warning, and the default value is
  obviously unusable on purpose.
- Losing the pepper means every key must be reissued. That is the intended trade — the alternative
  is a digest that stands alone, which is precisely what we chose against.
- **This reasoning is specific to high-entropy generated secrets.** If this system ever grows
  human-chosen passwords, they get Argon2id, and this ADR does not apply to them.

Both GitHub and Stripe apply the same reasoning to their tokens, which is a sanity check rather
than an argument.

## Alternatives considered

- **Argon2id / bcrypt.** Rejected: cost per request and the amplification vector.
- **Bare SHA-256.** Rejected: no defence in depth if the database leaks.
- **Encrypting keys rather than hashing.** Rejected outright — reversible storage means the
  plaintext exists somewhere recoverable, which is the property we are trying to eliminate.
