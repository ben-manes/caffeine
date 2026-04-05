# Finding Taxonomy

Standard classification for all audit and review findings. Referenced by the
auditor agent and all `/audit-*` and `/review-change` skills.

## Severity

| Level | Definition | Example |
|-------|-----------|---------|
| critical | Violates correctness under any valid interleaving | Data race causing lost update |
| high | Violates contract under specific conditions | ConcurrentMap postcondition broken on retry path |
| medium | Correctness preserved but fragile | Works only because of current CHM implementation detail |
| low | Style or robustness concern | Missing null check on code path unreachable today |

## Categories

| Category | Scope |
|----------|-------|
| memory-model | JMM access mode violations, missing happens-before edges |
| state-corruption | Weight divergence, lifecycle violations, deque corruption |
| specification | ConcurrentMap/Map/Cache/Policy API contract violations |
| resource-leak | Unclosed resources, unrecoverable state after Error |
| notification | Missing, reordered, or duplicated removal/eviction notifications |
| exception-safety | Incomplete cleanup on exception paths, phantom state |
| api-surprise | Public method returning nonsensical values for valid inputs |
| performance | O(n) on O(1) path, unnecessary allocation on hot path |

## Confidence

| Level | Meaning |
|-------|---------|
| high | Concrete interleaving or input constructed; reproducible |
| medium | Plausible scenario but depends on timing or conditions not fully verified |

Omit low-confidence speculation entirely — it wastes triage time.

## Classification (for triage)

| Label | Meaning |
|-------|---------|
| patch | Real issue, fixable in the current change |
| defer | Real issue but pre-existing, not introduced by this change |
| reject | Noise, false positive, or explained by design decisions |
| escalated | Cannot determine statically; needs dynamic testing |
