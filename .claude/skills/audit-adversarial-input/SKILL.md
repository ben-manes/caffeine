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

For each issue: state input values, trace computation, state whether it causes
incorrect behavior / OOM / infinite loop / degraded performance / graceful handling.

Do not report issues requiring API contract violations (e.g., null keys)
unless the violation is undetected and causes silent corruption.
