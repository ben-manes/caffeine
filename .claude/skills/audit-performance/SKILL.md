---
name: audit-performance
description: Audit hot paths for performance inefficiencies (allocations, contention, layout)
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the Caffeine cache for performance inefficiencies.

Context: Caffeine runs in high-throughput, low-latency JVM services where cache
operations occur millions of times per second. The library is already heavily
optimized — generic textbook advice is not useful.

## Rules

1. **Read the actual source code** before analyzing.
2. **Only report findings traceable to specific lines.**
3. **Account for JIT compilation.** C2 eliminates many apparent inefficiencies:
   escape analysis, inlining, dead code elimination. Don't flag JIT-optimized issues.
4. **Ignore correctness, style, and API design.** This is purely runtime performance.
5. **Quality over quantity.** Five real findings beat twenty speculative ones.

## Analysis Areas (priority order)

### 1. Get Fast Path (highest priority)
Trace get()/getIfPresent() from entry to return. Count volatile/opaque reads,
method calls, branches, allocations. Even one saved volatile read matters.

### 2. Allocation and GC Pressure
Identify allocations surviving escape analysis on hot paths. Frequency, size,
lifetime. Do not flag JIT-eliminated allocations.

### 3. Contention and Cache-Line Effects
CAS retry rates, cache-line bouncing, graceful vs catastrophic degradation
for read buffer, evictionLock, CHM bins, node field updates.

### 4. FrequencySketch
Counter layout locality, hash computation, reset cost. Accessed every read/write.

### 5. Maintenance Work
Worst-case work per write, latency spikes, eviction cascades, timer wheel scan cost.

### 6. Memory Layout
Node object size, pointer indirection depth, false sharing, @Contended opportunities.

## Output Format

For each finding:
```
## [Category] Title
**Location:** file:method (lines X-Y)
**Severity:** negligible | moderate | high
**What happens:** (trace the code path)
**Why it matters:** (quantify)
**JIT considerations:** (will C2 handle this?)
**Proposed fix:** (specific code change)
**Expected benchmark impact:** (JMH prediction)
```

If fewer than 3 real issues, the code is well-optimized. Do not pad the output.

## Pricing traps

- **The macOS clock serializes.** HotSpot's `os::javaTimeNanos` on macOS keeps `System.nanoTime()`
  monotonic with a CAS on one global. On an M3 Max it cost 10 ns per call at 1 thread and 400 ns at
  8, and `currentTimeMillis` also collapses at 8. A multi-threaded measurement of an expiring or
  refreshing cache on the default ticker there measures the clock. Price it on Linux or with a
  cached-clock `Ticker`, and say which.
- **A false-sharing claim needs placement control.** Under G1 a class on a given classpath settles
  at a nearly fixed `address % 128`, so natural forks all land in one placement class. Rebuild the
  object until it lands on each target placement, pair the arms at the same placement, and check
  that it did not move. Apple Silicon lines are 128 bytes. Fork-to-fork noise at 8 threads was
  4-6%, so a 5-10% layout effect needs tens of paired forks, not one.
- **Padding an object moves its neighbours.** The constructor's first allocations (in
  `BoundedLocalCache`, the `ReentrantLock` and its `Sync`) sit directly after the cache object. A
  padded arm needs a size-matched control that pads without moving the fields under test.
