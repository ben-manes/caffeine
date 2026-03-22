---
name: audit-coverage-gaps
description: Discover test coverage gaps that could hide correctness defects
context: fork
agent: auditor
disable-model-invocation: true
---

Assume the existing tests miss at least one real defect.

1. For each public method, identify the hardest-to-test edge cases:
   - Null values (where permitted)
   - Maximum/minimum weight
   - Zero-duration expiration
   - Mapping functions that return the same instance
   - Mapping functions that throw
   - Weighers that throw
   - Expiry callbacks that throw
   - CompletableFuture values (async cache edge cases)
   - Keys/values with adversarial hashCode/equals

2. For each edge case, trace the code path. Does the code handle it correctly?

3. Identify combinatorially hard behavioral dimensions:
   - Operation A on expired entry during concurrent operation B
   - Exception in user callback X while holding lock Y
   - GC collecting reference R between code points P and Q
   - Fast path falls through to slow path under contention (e.g.,
     entry appears expired on fast path, recovers under lock on slow path)
   - Async cache: future completes between check and action (e.g.,
     isComputingAsync returns true, but future completes before
     the code that depends on that check executes)

4. For each candidate gap, provide a minimal test case with the specific
   cache configuration and thread interleaving needed to reach the code path.

Priority ordering:
- Paths involving the catch-commit-rethrow pattern (doComputeIfAbsent, remap)
- Slow paths reachable only via contention (synchronized blocks after optimistic checks)
- Interactions between expiration and the async value lifecycle (ASYNC_EXPIRY, isComputingAsync)

Focus only on behavioral coverage gaps that could hide correctness bugs.
