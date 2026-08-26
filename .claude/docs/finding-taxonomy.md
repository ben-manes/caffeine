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
| liveness | Progress/termination failure without corruption: stranded drain status, lost wakeup, unbounded stall |
| external-contract | Misuse of a third-party or JDK API contract (dispose-on-error, merge-less collectors, live-view iteration) |

## Severity must be priced on a realistic configuration

A severity is a claim about what a **user** can reach, so the reproduction has to run the
configuration a user runs. The test harness is built out of instruments that exist to make
behaviour deterministic, and each of them can manufacture an impact nobody can reach in
production:

- **A frozen `FakeTicker`.** Several mechanisms self-heal simply because the clock moves.
  `TimerWheel.advance` sets `nanos = currentTimeNanos` unconditionally, so with
  `Ticker.systemTicker()` any operation reaching maintenance invalidates a live wheel iterator.
  Freeze the ticker and that detector stops working, which turned a truncated `Policy` snapshot
  into a reproducible non-terminating traversal holding `evictionLock`. With a real ticker the
  same scenario throws. The wedge was an artifact of the instrument.
- **`executor(Runnable::run)` or `CacheExecutor.DIRECT`.** Inline maintenance removes the
  coalescing that hides per-operation cost and turns an asynchronous drain into a re-entrant one.
  It is the right tool for determinism and the wrong one for pricing.

Before assigning `high` or `critical`, re-run the witness with the defaults a user gets: the
system ticker and the common pool. If the impact only appears under a test instrument, say so in
the finding and drop the severity accordingly. A mechanism that is real and an impact that is
reachable are two separate claims, and the second is the one severity encodes.

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
