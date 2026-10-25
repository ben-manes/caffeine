---
name: audit-adversarial-input
description: Analyze behavior under adversarial or extreme API inputs
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for defects triggered by adversarial or extreme API inputs.

For each category, construct concrete inputs and trace the code path:

1. **Weight extremes**: Integer.MAX_VALUE for every entry (overflow?),
   MAX_VALUE→1 delta (overflow?), 0 for all (unbounded growth?),
   inconsistent weigher (divergence?).

2. **Expiry extremes**: Long.MAX_VALUE nanos (timer wheel overflow?),
   0 or negative (infinite loops?), MAX→0 transitions, alternating durations.

3. **Maximum size extremes**: Long.MAX_VALUE (arithmetic overflow?),
   maximumSize(0) (graceful degeneration?), maximumSize(1) (temporary oversize?).

4. **Key/value adversarial behavior**: constant hashCode(0) (sketch degeneration?),
   slow equals() (lock hold explosion?), mutating hashCode (silent corruption?),
   huge values (allocation failure handling?).

5. **Concurrency extremes**: 10K threads on same computeIfAbsent, puts exceeding
   maintenance throughput (backpressure?), refresh storms from short refreshAfterWrite.

6. **Frequency sketch saturation**: all accesses to same key, all unique keys
   (reset cost?), counter overflow beyond 4-bit limit.

7. **Time extremes**: nanoTime near Long.MAX_VALUE (wrap-around?), non-monotonic
   ticker, large time jumps (timer wheel handling?).

8. **Inputs a client chooses**: the keys of a bulk call, the order of its lookups,
   and the lifetimes an `Expiry` derives from a request. An external scan framed as
   "a client who only chooses keys, order or lifetimes" found four costs that the
   categories above had not reached. Push hash-colliding keys through every bulk,
   snapshot and copy path (`getAll`, `putAll`, the async bulk load, the JCache bulk
   operations with their writer and loader) and price each at 10^4 to 10^6 keys,
   since a quadratic step can look flat in the thousands. Choose lookup order
   against every walk that reads recency (the expiration and eviction scans), and
   lifetimes that stack live entries ahead of an expiring cohort in the timer
   wheel. Measure the cost per call against an ordinary-key control rather than
   tracing it.

For each issue: state input values, trace computation, state whether it causes
incorrect behavior / OOM / infinite loop / degraded performance / graceful handling.

Do not report issues requiring API contract violations (e.g., null keys)
unless the violation is undetected and causes silent corruption.
