---
paths:
  - "simulator/**"
---

# Simulator Conventions

## Configuration and Runs

- HOCON defaults: `simulator/src/main/resources/reference.conf`; override with
  `-Dcaffeine.simulator.*`. Trace paths use `format:filepath`, e.g. `lirs:trace.gz`.
- Policies implement `record(AccessEvent)`, `finished()`, and `stats()`; add `@PolicySpec`
  and register new policies in `Registry`.
- Single run: `./gradlew simulator:run -q -Dcaffeine.simulator.*=...`
- Size sweep/charts: `./gradlew simulator:simulate -q --maximumSize=... --metric=...`
- Trace conversion: `./gradlew simulator:rewrite -q --inputFormat=... --outputFormat=...`
- Long sweeps must append results and skip completed rows on resume. Agent-shell
  `caffeinate -i` has failed to acquire its IOKit assertion both with and without sandboxing;
  verify with `pmset -g assertions` or keep a user-level awake session.
- Validate positive `maximum-size` once in `Registry`, before building policies. Zero causes
  failures or nontermination in several policies. Do not move this check to `BasicSettings`:
  weighted `WindowTinyLfuPolicy` legitimately seeds its resizable admitter with zero.
- `Simulate` and `Rewriter` must return Picocli's exit status, including usage errors.

## Trace Characteristics and Policy Matching

`Registry.policies()` includes a policy only if its `@PolicySpec` supports every characteristic
declared by the reader. The sole characteristic is currently `WEIGHTED`; weighted traces
exclude weight-oblivious policies. `sketch.WindowTinyLfu` supports weights and budgets regions
by weight, so it supplies a static-window ceiling on weighted cells.

This exclusion keeps each panel's metric consistent. Do not add a Registry option that treats
weighted events as unit weight; use `simulator:rewrite` to strip weights explicitly. If a
trace-side projection becomes useful, it must narrow both `characteristics()` and the emitted
events (`AccessEvent.forKey(key)`). Narrowing metadata alone lets weight-aware policies keep
using real weights within an object-hit-rate panel. Such an adapter is deferred until at least
two routinely mixed characteristics justify it.

## Reporting and Trace Fidelity

- **Evictions use each policy's accounting.** A never-admitted entry counts as an eviction in
  CAMP/GDWheel, nothing in S3FIFO/Sieve, and a rejection in GDSF. Oversized-entry behavior belongs
  to the algorithm. Do not normalize these counters or retrofit size-awareness into a published
  LRU algorithm; exclude unsuitable policies or add a separately named size-aware variant.
- **Never overwrite an input trace.** The rewriter uses `Files.isSameFile` to reject output
  aliases and symlinks to its inputs before opening the output. Otherwise truncation precedes
  lazy input reading and can destroy the trace while reporting success.
- **Export limitations are accepted.** A `.gz` output name does not enable compression;
  `LirsTraceWriter` emits 64-bit keys although reference C readers accept signed ints;
  CloudPhysics folds keys to 32 bits and can collide. Compression, range checks, and collision
  warnings were reverted as excessive export machinery. Revisit only for a concrete need.
- **Recognized but undecodable containers must fail.** Probes try xz, commons-compress
  compressors, then archivers, rewinding on format misses before falling back to raw bytes.
  Swallow only format-miss exceptions: `XZFormatException` and `EOFException` for xz;
  `StreamingNotSupportedException` is set aside for archive handling. Other failures escape.
  Raw binary readers accept arbitrary bytes, so swallowing decode errors fabricates events
  (previous witnesses: corrupt xz became two corda events; valid 7z became 71).
  The xz EOF exception is necessary for eight-byte, one-event traces because probing reads a
  twelve-byte header; an xz-magic-only file consequently still falls through to raw.
- **Text decoding preserves byte identity.** ISO-8859-1 maps each byte to a distinct character,
  preserving real Latin-1 traces and avoiding malformed-UTF-8 aliases through U+FFFD for keys
  derived from fields such as MSR hostnames or Baleen shards. Parsing/filtering uses ASCII.
  Strict REPORT decoding was rejected because it refused real traces such as wikibench.
- JFreeChart's size axis is categorical: exponentially spaced sizes render equidistant.
  GUESS sampling is with replacement. These are disclosed limitations, not defects.
- `CombinedCsvReport` uses the union of input policies, with empty missing cells.
  `tabulate` collects policy order, metrics, and duplicate names per input in one pass.
  Check metric availability before reporting duplicates and finish validation before opening
  output, so rejected inputs preserve an existing report.

## Clairvoyant Look-Ahead

When `opt.Clairvoyant` or clairvoyant admission is enabled, `Simulator` wraps its reader in
`ClairvoyantTraceReader`. This materializes the trace once into a fixed-width temporary file
and supplies immediate next-access times for Bélády's MIN and the admitter.

- A record is `[key, (weight | penalties), nextAccess]`. Choose the layout once from the
  delegate's global `WEIGHTED` characteristic and the first event's penalty-awareness.
  Reject a composite needing both weights and penalties rather than silently dropping one.
- Append records forward, then fill next-access positions backward using a
  `nextSeen: key → position` map. Heap use is O(distinct keys), released before policy replay;
  both passes and replay use sequential, buffered I/O. Forward back-filling would require
  random writes across long reuse distances.
- Replay this materialization to **all** policies. It freezes non-repeatable synthetic traces
  such as `ThreadLocalRandom` so the policies and admitter see identical requests.
- Each consumer's sequential `Cursor` advances exactly once per access, including
  `admitter.record` in every host. Obtain cursors during policy construction, while the reader
  is bound; late construction would fail or start a cursor midway through the trace.
- The reader owns cursors and closes them on `TraceReader.close()`. Decorators do not forward
  `close()`: `ClairvoyantTraceReader` retains no consumed delegate, and `TraceFormat.readFiles`
  composites are lambdas. This is safe because the only materializing reader is installed
  outermost and its delegates inherit no-op close. Add forwarding if a second materializing
  reader must be nested, not speculatively.
- Preserve `KeyOnlyTraceReader` through multi-file wrappers and materialize its `keys()`
  `LongStream` without per-event boxing. Use `forEachOrdered` to drain delegates, not
  `Stream.iterator()`, which has buffered the whole input before yielding in this path.
  The disk-backed implementation matched corda/DS1 bit-for-bit and ran DS1@4M about twice as
  fast with 512 MB instead of 2 GB heap.
- `opt.Clairvoyant` does not record penalties itself. `PolicyActor` attributes them from the
  observed hit/miss; recording them in both places doubles the result. Tests must mirror this
  attribution and drive the policy through the reader.

## Policy Implementation

**Match reference behavior before introducing deliberate deviations.** Establish bit-for-bit
hit/miss agreement on canonical traces before changing memory bounds, naming, or quality.
For a published algorithm, the paper remains the specification when the authors' repository
later diverges (e.g. post-publication S3-FIFO warmup/hit-rate changes in libCacheSim).
Document that divergence rather than chasing it.

Simulator policies omit library machinery such as concurrency and field-access/layout
optimizations, but must preserve algorithmic quality. `product.Caffeine` runs the shipped
cache; `sketch…simple` is a readable reference. Close algorithmic gaps between them, not gaps
caused by library complexity. In particular, the simple climber retains small-cache grow-first
direction and never-freeze restart. Validate with bundled traces and
`corda_large + 5×loop + corda_large` at 512.

- **CLOCK-Pro/CLOCK-Pro+ retain all distinct keys.** Removed clock nodes remain in `data`,
  as `clock-pro.c` keeps `page_struct` in its hash table after `remove_from_clock`.
  Removing them changed 8/48 canonical cells (cs, multi1, 2_pools, sprite at 512/1024), by up
  to 0.5pp. Cardinality-sized memory is the accepted price of reference fidelity.
- **Consecutive duplicate handling belongs to each policy.** The LIRS references
  (`lirs.c`, Zhong's `replace_lirs_base.cc`/`replace_lirs2.cc`) skip correlated references
  after incrementing the hit-rate denominator (`warm_pg_refs++`/`mTraceLength`).
  Thus the duplicate leaves algorithm state unchanged but still counts as a hit. This is
  reference-author intent, confirmed by correspondence; it is not in the LIRS/CLOCK-Pro
  papers, though the 2Q paper discusses correlated references.
  `Lirs2Policy`, `ClockProPolicy`, and `ClockProPlusPolicy` need guards because their
  role swaps/adaptive targets are not idempotent; record an operation and hit before returning.
  A bare early return understates hit rate (about 1pp on cs).
  `LirsPolicy` omits the guard because a repeated top-of-stack access already preserves S/Q
  state and records a hit; it matches both references, including cs's 101 consecutive
  duplicates. `ClockProSimplePolicy` also uses its normal hit path and regresses with a guard.
- **Preserve real-valued threshold boundaries when converting to integers.** S3-FIFO routes
  eviction on `S.size >= 0.1 * C`, which cannot hold for an empty S at positive capacity.
  Flooring the threshold to zero caused an insertion loop; retain the `Math.max(1, …)` floor.

## Sketch Sizing

Sketch sizing affects admission quality because it controls aging cadence. Weighted
`WindowTinyLfuPolicy` must retrack live entry count through `Frequency.ensureCapacity`,
as BLC calls `ensureCapacity(mappingCount())` on additions. Freezing initial sizing inverted
the reported static-window optimum on metaCDN_rprn@4G from 80% to 1%.
Recheck bundled unweighted LIRS cells bit-for-bit after changes.

- `CountMin4` grows its table and retunes its period on every capacity call. Retracking must
  **not restart the epoch** unless reallocation forgets the counts. Resetting
  `eventsToCount`/`additions` on every weighted access made `reportMiss`'s boundary
  unreachable and froze the climber at step 1. Preserve
  `ClimberResetCountMin4Test.ensureCapacity_retrackWithoutReallocation_keepsTheEpochRunning`.
- `IndicatorResetCountMin4` must forward retracking to its resizable
  `ClimberResetCountMin4`. `PerfectFrequency`, `RandomRemovalFrequencyTable`,
  `TinyCacheAdapter`, and `CountMin64TinyLfu` genuinely cannot resize and keep the default no-op.
- Construct admitters eagerly, even for weighted policies. Clairvoyant cursors are available
  through `currentCursor()` only within the policy-construction `ScopedValue`.
  Seed the weighted admitter with `maximum-size = 0` and let retracking allocate at half-fill,
  preserving unweighted output. `TinyLfu` permits zero only for `Frequency.isResizable()`
  (the CountMin4 family). A fixed count-min-64 zero seed gives `sampleSize = 0` and ages on
  every increment, preventing frequency accumulation.
- **The remaining native/product cadence difference is accepted.** `FrequencySketch` uses
  `10 × maximum`; `CountMin4` uses `10 × table.length`. They coincide at power-of-two gate
  sizes (4096/8192/16384/32768) and at sizes 129–256 due to the library's minimum.
  Outside those ranges the simulator can age up to 2× staler (DS1@1051635: 1.99×;
  strad_p8@4097: 2×; arc/P3@152508: 1.72×), or below 256 up to 32× fresher
  (loop@101: 2× fresher). The library can repoint its period downward; CountMin4's table is
  grow-only. Retracking fixed moving-count sizing; this residual rounding/cadence gap was not
  worth rebasing the corpus. It affects `Admission.TINYLFU`, including static-window
  ceiling estimates and native simulator policies, **not** `product.Caffeine` measurements.

## Hit-Rate Validation

- **Validate trace format before sizing a study.** Inspect the first line, not the suffix or
  file size. For example, the all-trc family mixes ten plain LIRS streams with 27 files of
  `N <k>` / `I <id> <t>` / `O <id> <t>` records that no reader supports. A wrong reader may
  throw, but a mismatched valid parser can silently emit no events; verify nonempty results.
- **Use absolute percentage points and measured noise.** Product admission is randomized;
  observed single-seed spread was about 0.1–0.8pp (loop@101: 0.12; multi3@2981: 0.49).
  Treat sub-1pp single-seed deltas as unresolved, not wins. Run at least 3 seeds, preferably 5,
  on low-hit-rate cells. Accept robust multi-seed wins around 2pp or larger with no collapse, judged
  cell-by-cell; summing many sub-noise deltas into `net +Npp` is misleading.
- Equal seeds make arms reproducible, but do not provide request-indexed common randomness
  when admission contests consume draws on different requests. Exact pairing requires matching
  draw counts and request-index digests. Interleaving arms limits temporal drift without
  synchronizing their random draws.
- **A shadow must model reachable host geometry.** The live climber exchanges window and
  protected capacity while probation stays fixed. Static `WindowTinyLfuPolicy(percentMain)`
  re-splits main, so it is not an exact counterfactual. Construct integral
  `(window, protected, probation)` targets, apply host clamps, deduplicate aliases, then scale
  those triples into miniatures. Compare request by request against an independent state model
  and the real policy; final hit rate cannot expose geometry or queue-order errors.
- **Sampled panels need explicit clocks and evidence.** Use `floorMod` for hash buckets;
  signed `%` can admit every negative hash under `< 1`. Separate host requests, sampled
  requests, and the first-full boundary. An empty epoch abstains instead of choosing the first
  tied arm. Randomized admission arms receive one request-indexed variate from a domain
  distinct from membership sampling; equal seeds with conditional draws do not suffice.
- **Movement requires host acknowledgement.** Derive an integral delta from the live coordinate,
  validate the applied sign/magnitude, and carry capped commands until the approved target is
  reached. Test both directions, zero/partial clamps, changed targets, and requests immediately
  before/at clock boundaries; nominal percentage assignment is insufficient.
- **Gate estimator quality, safe movement, and production cost separately.** Measure retained
  graph size, steady-request allocation, command/boundary allocation, and CPU. Low duty cycle
  does not remove dormant callback/counter/branch cost. Benchmark the actual product integration,
  warm the measured objects/branches, and make estimator work observable to the JIT.
- **Distinguish recovery latency from a settled error.** Classify terminal state before
  interpreting oracle regret; a trace ending during a walk/audit measures finite-horizon
  recovery. A sequential treatment/control crossover on one drifting cache is not causal:
  use matched states/requests, synchronized randomness, and simultaneous or replay-forked arms.

Canonical comparisons use bundled LIRS loop, multi1/2/3, 2_pools, cpp, cs, and scan at
500/1k/2k; ARC DS1 at 1M–8M; S3 at 100k–800k; and the corda+loop phase-shift stress.

Run that stress at **512, 513, 1024, 4096, 4097, and 8192**. Product should remain near its
static-window ceiling and above LRU without cliffs at either tier boundary. Reactive climbing
uses small tuning through `SLOW_ADAPT_THRESHOLD` (512), standard tuning through
`DENSITY_THRESHOLD` (4096), and density above 4096. Starved regions can pin density climbing
at smaller sizes; do not lower its threshold without this check. Use real bundled
`corda:trace_vaultservice_large.gz` + `lirs:loop.trace.gz`; synthetic phase shifts do not
reliably reproduce the trap. See [design decisions](../docs/design-decisions.md).

## LIRS Reference Validation

- Use a high `non-resident-multiplier` (e.g. 100) when comparing with unbounded reference
  shadows, so the simulator's memory bound does not affect results.
- Round the **cold** allocation: `HIR = (int)(HIR_RATE/100 * mem_size)`, apply its floor, and
  give LIR the remainder. Hot-side rounding shifts the boundary by a block and costs 1–4 misses.
  `lirs.c` floors HIR at 2 (`LOWEST_HG_NUM`); Zhong's `replace_lirs2.cc` floors it at 4.
  `MAX_S_LEN` is `mem_size*2500` for LIRS and `mem_size*8` for LIRS2, hence the latter's
  default `stack-length-multiplier = 8`.
- Cold-side rounding matched `lirs.c` on 8/8 cells at shipped defaults: cs@512/1024,
  ps@256/1024, multi1@1024, gli@512, cpp@1024, and 2_pools@1024. Build the reference from
  simulator resources with `cc -std=gnu89` for its legacy implicit declarations.
- **LIRS2's stack bound controls admission, not just memory.** `stackLength` is the admission
  bar's depth. Reducing its multiplier from 8 to 1 freed no blocks while raising slot visits
  per request from 1.997 to 8,105; another witness moved hit rate by 12.91pp and reduced
  promotions by 92%.
  At the published 8, a working set can be locked out until the bound reaches 12.
  A flat sweep proves only its tested range; multiplier 1 is outside the published bound.
- Export with `simulator:rewrite --outputFormat=LIRS`; remove `*` checkpoints if the C/C++
  reader rejects them.

## Reader and Policy Test Scoping

Add tests as part of a specific fidelity fix, against a real oracle: a documented byte layout
(e.g. libCacheSim alignment/byte order), paper-defined behavior/arithmetic (e.g. CAMP's
`roundedCost`), or an independent robustness boundary (size 1, corrupt-trace rejection).

Do not add blanket reader tests that merely restate our interpretation of an undocumented
format. K5cloud's old block-only key omitted volume identity (#1974); such a test would have
preserved the aliasing error. Validate interpretations through hit-rate comparisons with the
reference implementation or paper.
