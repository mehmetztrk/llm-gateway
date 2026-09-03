# 0008 — Semantic cache similarity threshold

- **Status:** Accepted
- **Date:** 2026-09-03

## Context

The semantic cache serves a stored answer when a new prompt embeds close enough to an old one.
"Close enough" is a single number, and it is the most consequential number in the project.

The asymmetry is what matters. A threshold set too **high** costs a cache miss: the gateway does
the work it would have done anyway, and nobody notices. A threshold set too **low** means the
gateway confidently answers a question that was never asked — and does it fast, with a `200`, and
with no signal to the client that anything unusual happened. Those two errors are not comparable,
so the number is not chosen to maximise the hit ratio.

## Decision

Cosine similarity **≥ 0.95**, configurable per deployment via
`gateway.cache.similarity-threshold`, and the semantic layer can be switched off entirely.

## Consequences

- 0.95 on `nomic-embed-text` corresponds roughly to a paraphrase or a reformatting of the same
  question — "what is an API gateway" against "what's an API gateway?" — while questions that
  differ in a material noun or a negation fall below it. Negation is the case that keeps the
  threshold high: "is X safe" and "is X not safe" embed far closer than their answers do.
- **The response says where it came from.** `x-llmgw-cache: hit-semantic` distinguishes a semantic
  hit from an exact one, so a tenant that finds an answer surprising can tell whether it was
  produced for them, and an operator can measure the two hit rates separately rather than reading a
  blended number.
- The hit ratio will be lower than a lower threshold would produce. That is the intended trade, and
  BENCHMARKS.md reports exact and semantic hits separately so the trade stays visible rather than
  being hidden inside one headline figure.
- **This number deserves revisiting with real traffic, and it has not had any.** 0.95 is a
  defensible starting point argued from the shape of the risk, not a value tuned against a labelled
  dataset of prompt pairs. Saying so is more useful than implying a rigour that does not exist. A
  deployment with real logs should measure its own false-hit rate and move the number.
- **Off is a legitimate setting.** For a tenant where a wrong-but-plausible answer is expensive —
  anything touching money, health or law — the correct threshold is "no semantic cache", and the
  configuration supports that without a code change.
