---
name: audit-arithmetic
description: Audit for arithmetic and boundary bugs (overflow, off-by-one, drift)
context: fork
agent: auditor
disable-model-invocation: true
---

Assume there is at least one arithmetic or boundary bug in this code.

Audit for:
- Off-by-one errors
- Incorrect inequality direction (< vs <=, > vs >=)
- Overflow / underflow (int, long, specifically nanoTime arithmetic)
- Implicit narrowing or widening conversion
- Incorrect rounding or truncation
- Sign errors
- Sentinel value collisions (can a valid value equal a sentinel?)
- Counter drift (can a counter diverge from the quantity it tracks
  over a long-running cache?)
- Timer wheel bucket arithmetic (modular arithmetic, wrap-around)
- Frequency sketch counter saturation and reset arithmetic
- Hill climber / adaptive sizing: window vs main partition adjustment
  (percentage calculations, rounding, overshoot, oscillation)
- FrequencySketch table size calculation (ensureCapacity rounding to
  power of two, interaction with maximumSize)
- Weight delta computation in UpdateTask (sign conventions, can
  intermediate sums overflow when weights are near Integer.MAX_VALUE?)

For each suspected issue:
- Provide a concrete input (specific key, value, weight, time values)
- Show step-by-step evaluation through the code
- Show expected vs actual behavior
- If it requires billions of operations, state the count needed

Ignore style and performance.
Only report high-confidence defects supported by evaluation traces.
