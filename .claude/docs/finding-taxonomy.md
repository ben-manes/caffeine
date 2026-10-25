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

State the affected configuration separately from the harm within it. Being non-default or in a
lower-priority module does not establish a severity ceiling; apply the module's stated use-case
scope and price the demonstrated impact.

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
| redundancy | Removable work or state with a behavioral-equivalence proof and a concrete source simplification |
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
  Use it to control scheduling; its timings do not price default-executor behavior.

Before assigning `high` or `critical`, re-run the witness with the defaults a user gets: the
system ticker and the common pool. If the impact depends on harness effects absent from the
claimed user configuration, identify those effects and do not attribute them to that configuration.
A mechanism that is real and an impact that is reachable are two separate claims, and the second
is the one severity encodes.

## A refutation must be able to detect the defect

A run that does not reproduce a claim is evidence only at the power it ran with, and a closure
written from it becomes a ruling that later audits apply to every rediscovery. Each of the first
three conditions was missing from a closure that a later reproduction overturned, and the fourth
is what lets a zero mean anything:

- **Attempts sized to the rate.** Zero hits in N attempts bounds the rate only below
  1 - 0.05^(1/N) at 95% confidence (about 3/N once N is large), so zero in five still allows 45%.
  A manual refresh of an absent key racing a `put` and an `invalidate` was accepted as discarded
  0/5 on a pool executor; at 20,000 aligned attempts on the default executor it installed the
  stale load 1 to 13 times.
- **Two CPUs as well as the host's count.** With two CPUs the common pool has one worker, which
  makes the default executor a different configuration. Rerun with `-XX:ActiveProcessorCount=2`.
  The access-order scan's reordering cost 3.8 points of hit rate at two CPUs and none at 14, and
  duplicate maintenance submissions reached 39 per 10,000 puts at two CPUs against 5 at 14.
- **The size the cost is about.** A quadratic step or a walk over a whole region looks flat in
  the thousands, so sweep the entries, keys or batch to 10^5 or 10^6. Hash-colliding keys were
  judged clean after 5,000 puts; an async bulk load of 32,768 of them took 2.5 s, against 0.2 s
  once its snapshot used a tree-binned map.
- **A positive control.** The same harness reproduces the effect under a configuration that
  forces it (an inline executor, a widened window, a planted state), so that zero means absent
  rather than unobservable.

A run that misses one reports what it ran ("not reproduced in 5 attempts at 14 CPUs") and leaves
the claim open. A confirmed invariant names the property it was checked for and the conditions of
any run behind it, and supports nothing wider: three audits passed the JCache dispatcher's
synchronous chaining stage after checking it for executor rejection only, and that stage could
lose a key's queue slot inside the append. A ruling or design note whose reason is a measurement
records its conditions beside the conclusion, so the next reader can tell what it covers.

## Confidence

| Level | Meaning |
|-------|---------|
| high | Concrete interleaving or input constructed; reproducible |
| medium | Plausible scenario but depends on timing or conditions not fully verified |

Omit low-confidence speculation entirely — it wastes triage time.

For `redundancy`, high confidence may rest on a complete source equivalence proof covering the
relevant exits, callers, and observations. Keep cleanup severity `low` and report test execution
and runtime effects separately. A measurable speedup or broken contract is not required; do not
invent one to justify the cleanup.

## Classification (for triage)

| Label | Meaning |
|-------|---------|
| patch | Real issue, fixable in the current change |
| defer | Real issue but pre-existing, not introduced by this change |
| reject | Noise, false positive, or explained by design decisions |
| escalated | Cannot determine statically; needs dynamic testing |
