# Research Foundations

Papers and talks that informed Caffeine's design, mapped to implementation.

## Design Philosophy

From the "Design of a Modern Cache" talk:
- Use O(1) algorithms for predictable performance
- Optimize the system, not a single metric
- Tradeoffs based on simulations
- Must be correct & maintainable
- APIs with low conceptual weight

Performance mantras:
 - Don't do it
 - Do it, but don't do it again
 - Do it cheaper
 - Do it less
 - Do it later
 - Do it when they're not looking
 - Do it concurrently

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
- `HillClimberWindowTinyLfu` in simulator — the paper's reactive climber (research prototype)
- `WindowClimber.java` — the production climber (applied by `BoundedLocalCache.climb()`). The
  shipped design has advanced beyond the paper: reactive below 4096, and above it a
  within-sample density signal with a starvation-guarded probe machine whose up-probe verdict
  prices capacity at main's probation margin (see `hill-climber.md` and
  `adaptive-window.html`). The simulator deliberately carries no faithful reference of it —
  `product.Caffeine` (the real cache) is the arbiter
- Adjusts window percentage up/down based on observed hit rate changes

#### What MiniSim actually models

MiniSim's premise is simple: run small, sampled copies of the cache at several window sizes and
let their miss counts choose the full cache's next size. That comparison is meaningful only when
each small copy represents a state the full cache can actually reach.

The 2026 alternative-climber study found two separate implementation problems, and both yielded
small corrections to the registered MiniSim implementation:

1. **The old sampler selected far too many keys.** Java's `%` keeps the sign of its left operand,
   so `hash % R < 1` accepts every negative hash in addition to the intended bucket. At
   `R = 1000`, it selects about 50.05% of the hash space instead of 0.1%. This problem is already
   fixed on main: current `MiniSimClimber` uses `floorMod`.
2. **The old miniature cache shapes did not match the live cache shapes.** It built each arm as an
   ordinary static `WindowTinyLfuPolicy`, which recomputed the protected/probation split whenever
   the window percentage changed. The live climber instead transfers capacity between window and
   protected while leaving probation fixed. In a 1,000-entry cache, the live default starts at
   `(window=10, protected=792, probation=198)` and reaches an 80% window at `(800, 2, 198)`. The
   old 100-entry miniature modeled that arm as `(81, 15, 4)` rather than the reachable
   `(80, 0, 20)`, so it could rank the wrong cache. MiniSim now constructs the full cache's
   reachable integer targets first, keeps probation fixed, applies the live rail, removes aliases,
   and scales those exact shapes into miniature caches. It also moves from the host's actual
   integer window size to the selected target; it no longer assumes that percentage arithmetic
   landed on the requested coordinate. Its epoch clock starts when the host fills (earlier
   requests only warm the miniatures), and an epoch whose miss vector is uniform, such as a
   period with no sampled requests, holds rather than electing the first arm.

This correction deliberately does not transplant the rejected 27-policy experimental controller.
That prototype also gave every arm the same random admission draw for each sampled request. Main's
registered MiniSim still gives arms equal seeds whose conditional admission contests may consume
different draw indices. Its geometry and movement are now faithful, but it is not yet an exact
common-random counterfactual panel.

Keep these rules for any future shadow or miniature simulator:

- Model the exact reachable `(window, protected, probation)` state, not just a window percentage.
- Clamp and deduplicate full-cache targets before scaling them down.
- Compute integral movement from the acknowledged live state, and test arrival and reversal in
  both directions.
- Give every arm the same request, frequency state, and request-indexed random draw. Equal seeds
  alone are not enough when arms consume randomness conditionally.
- Start and score epochs explicitly. A period with no sampled evidence must abstain rather than
  select the first arm because every score tied at zero.
- Judge prediction quality separately from implementation cost. A shadow can identify a good
  target and still be too large or expensive to run in production.

The paper's Mini-Sim result, the 2018 repository version, and current `MiniSimClimber` are therefore
three different artifacts. The paper does not identify the exact binary behind its figures, and
the repository later changed hashing, sampling, fullness timing, and clock behavior. A current run
is evidence about current code, not a paper reproduction, unless those differences are controlled
one at a time.

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
providing additional resilience. The cache randomly admits ~1% of candidates to make
frequency estimation attacks non-deterministic. Not a direct implementation influence,
but informs the threat model.
