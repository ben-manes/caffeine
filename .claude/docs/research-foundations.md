# Research Foundations

Papers and talks that informed Caffeine's design, mapped to implementation.

## Design Philosophy

From the "Design of a Modern Cache" talk:
- Use O(1) algorithms for predictable performance
- Optimize the system, not a single metric
- Tradeoffs based on simulations
- Must be correct & maintainable
- APIs with low conceptual weight

Performance mantras: Don't do it. Do it, but don't do it again. Do it cheaper.
Do it less. Do it later. Do it when they're not looking. Do it concurrently.

## Core Design

### TinyLFU: A Highly Efficient Cache Admission Policy
Einziger, Friedman, Manes. ACM Trans. Storage, 2017.
https://doi.org/10.1145/3149371

**What it introduced:** Frequency-based admission policy using a compact approximate
LFU structure (counting Bloom filter / Count-Min Sketch). The combined replacement
and eviction policy W-TinyLFU uses a small window (LRU) feeding into a main space
(segmented LRU), with TinyLFU as the admission filter between them.

**Implementation:**
- `FrequencySketch.java` — 4-bit Count-Min Sketch with periodic halving (aging)
- `BoundedLocalCache.java` — W-TinyLFU: window deque → admission filter → probation/protected deques
- Admission decision: `admit()` compares frequency of candidate vs victim
- Window size: ~1% of total by default, adaptive via hill climber

### Adaptive Software Cache Management
Einziger, Eytan, Friedman, Manes. Middleware '18, 2018.
https://doi.org/10.1145/3274808.3274816

**What it introduced:** Two mechanisms for auto-tuning the window-vs-main partition
in W-TinyLFU: hill climbing (gradient-free optimization) and an indicator-based
approach. The hill climber adjusts the partition size to adapt to workload changes
without manual configuration.

**Implementation:**
- `HillClimberWindowTinyLfu` in simulator — research prototype
- `BoundedLocalCache.java` — production hill climber (`climb()` method)
- Adjusts window percentage up/down based on observed hit rate changes

### Lightweight Robust Size Aware Cache Management
Einziger, Eytan, Friedman, Manes. ACM Trans. Storage, 2022.
https://doi.org/10.1145/3507920

**Relationship:** Co-authored advisory role. Analyzes Caffeine's existing weighted
eviction approach and proposes an alternative size-aware admission extension to
TinyLFU. Caffeine already supports variable-sized entries via its `Weigher` API
and weighted eviction. The paper compares Caffeine's approach against their
proposal and other size-aware algorithms (AdaptSize, LHD, LRB, GDSF),
demonstrating competitive hit ratios and byte hit ratios with 3x lower CPU
overhead. The alternative approach was never adopted into Caffeine — it came
after the fact and there was no need to revisit the existing design.

**In simulator:** Several size-aware policies implemented for comparison (CAMP, GDSF,
GDWheel in `greedy_dual/`).

## Concurrency Architecture

### BP-Wrapper: A System Framework Making Any Replacement Algorithms (Almost) Lock Contention Free
Ding, Jiang, Zhang. ICDE 2009.

**What it introduced:** Batching and prefetching to eliminate lock contention from
replacement algorithms. Instead of acquiring the policy lock on every access, buffer
operations and process them in batches under the lock.

**Implementation:**
- `BoundedBuffer.java` — striped ring buffer for read recording (lossy, lock-free)
- `MpscGrowableArrayQueue.java` — write buffer with guaranteed delivery
- `afterRead()` — offers to read buffer, schedules drain
- `afterWrite()` — offers task to write buffer, inline fallback if full
- `maintenance()` — single-threaded batch processing under evictionLock

## Expiration

### Hashed and Hierarchical Timing Wheels
Varghese, Lauck. IEEE/ACM Trans. Networking, 1997.
https://doi.org/10.1109/90.650142

**What it introduced:** O(1) timer start/stop using circular buffers (timing wheels)
with hierarchical extensions for large time ranges.

**Implementation:**
- `TimerWheel.java` — hierarchical timing wheel with 5 levels
- Bucket widths: ~1s, ~1min, ~1hr, ~1.6d, ~6.5d (powers of 2 in nanos)
- O(1) insert/delete via doubly-linked list per bucket
- Cascading: entries demoted to finer-grained wheels as time approaches
- Used for variable expiration (`expireAfter(Expiry)`)

## Security

### Denial of Service via Algorithmic Complexity Attacks
Crosby, Wallach. USENIX Security 2003.

**What it introduced:** Demonstrated hash-flooding attacks against hash tables,
causing O(n) degeneration per operation.

**Relevance:** Caffeine delegates to `ConcurrentHashMap` which uses tree bins
(red-black trees) for collision resistance in JDK 8+. The frequency sketch uses
a separate hash (`spread`/`rehash` functions) independent of the key's `hashCode()`,
providing additional resilience. Not a direct implementation influence, but
informs the threat model.
