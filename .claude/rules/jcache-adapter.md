---
paths:
  - "jcache/**"
---

# JCache Adapter Conventions

JCache has its own expiry, events, statistics, configuration, and lifecycle over Caffeine.
Read the relevant section of the [conformance reference](../docs/jsr107-conformance.md) before
adjudicating a finding; it records the accepted differences, boundaries, and tests.

## Expiry and entry processors

- Values are `Expirable<V>` wrappers. `ExpiryPolicy` governs JCache expiry; native expiry is a
  mirror for eager removal. Every value read checks the wrapper's deadline.
- Access expiry writes the held wrapper's timestamp, then updates the native timer **only on
  read paths** (`get`, `getAll`, iterator). Reads remain lock-free; the by-key timer update can
  race a replacement, an accepted discrepancy. Inside `compute`, failed conditional operations
  and `invoke` READ write only the wrapper: core's `expireAfterUpdate` refreshes the timer on
  commit. `setExpiresAfter` there can enter maintenance under the bin lock and violates the policy
  API's atomic-scope restriction. See [access expiry](../docs/jsr107-conformance.md#access-expiry).
- `ExpirableToExpiry` subtracts in milliseconds and converts the difference once. Converting the
  absolute deadline first saturates near the nanosecond horizon. Keep its ticker read: using
  core's `currentTime` shifts auto-increment-ticker tests. Preserve finite-deadline sentinel
  adjustments. See [deadline conversion](../docs/jsr107-conformance.md#deadline-conversion).
- Zero creation expiry suppresses the store, CREATED, and put count, but still calls the writer
  and publishes EXPIRED for the new value. Zero update expiry stores/counts/publishes UPDATED
  before expiry. Null or throwing creation expiry means eternal; null or throwing update/access
  expiry means unchanged. See [zero expiry](../docs/jsr107-conformance.md#zero-expiry) and
  [policy defaults](../docs/jsr107-conformance.md#policy-defaults).
- `EntryProcessorEntry.Action` tracks NONE, READ, CREATED, UPDATED, LOADED, or DELETED.
  `getValue()` may load. CREATED then remove resets to NONE; LOADED then remove calls the
  writer's delete. Removing absence calls the writer but records no removal or REMOVED event.
  Forward an explicitly null varargs array unchanged.
- `invoke` reconciles a lazily expired prior **before** the processor: publish EXPIRED, count
  eviction, and present absence. If the processor or writer throws any `Throwable`, commit that
  expiry removal, await its synchronous listener, then rethrow via `processorFailure`: `Error`
  unchanged, other failures as `EntryProcessorException`. `postProcess` stays expiry-free;
  READ/UPDATED imply a live prior. See [entry processors](../docs/jsr107-conformance.md#entry-processors).
- Query operations returning the same wrapper still count as native writes. Their interaction
  with vendor refresh/native TTL settings is accepted; do not expose a public no-op hint or
  split an atomic operation to avoid it. See [native extensions](../docs/jsr107-conformance.md#native-extensions).

## Writes, loads, and statistics

- Single-key writer calls run inside the mutating `compute`, under the same per-key lock. Use
  `compute` when delete must fire for an absent key. Bulk `writeAll`/`deleteAll` runs once before
  per-key mutations with `publishToWriter=false`; the batch atomicity exemption permits the
  resulting same-key ordering window. It does not excuse store-only writes caused by copying:
  prepare all `putAll` copies before `writeAll`, reuse `CopiedEntry`, and retain a partial writer
  failure as suppressed if the store loop fails. See [write-through](../docs/jsr107-conformance.md#write-through).
- Read-through `getAll` replaces concurrent writes with loaded values and publishes CREATED;
  `loadAll(replaceExistingValues=false)` keeps existing values. Preserve this contract difference.
  Load-time publication can precede storage and a prior mapping's EXPIRED event. These are
  accepted divergences from the listener contract, which does promise per-key ordering.
  See [read-through and event ordering](../docs/jsr107-conformance.md#read-through-and-event-ordering).
- Read-through timing excludes only `delegate.load`/`loadAll`, through an operation-local scope
  opened after a miss. Record total minus loader time from `finally`; copying remains included.
  Native loads through `unwrap` have no JCache timing scope. Never restore negative global
  pre-credits. Start `putAll` timing before input copying; end successful `getAll` timing after
  output copying. See [statistics](../docs/jsr107-conformance.md#statistics).
- After commit, capture a synchronous listener failure, record required counters/timers, then
  rethrow it. Suppress a listener failure onto the operation's own failure. Do not broaden this
  into a `Throwable` catch: listener `Error` propagates unchanged. See
  [listener failures](../docs/jsr107-conformance.md#listener-failures).

## Event dispatch and re-entry

- Every `EventDispatcher.publish` appends a dependent future, including an empty queue's first
  event. A source stage such as `supplyAsync` can throw executor rejection inside `compute`,
  after the writer succeeded, aborting only the cache mutation.
- Append inside `compute`, but stage listener execution until it ends. `beginComputation` marks
  the publishing thread; the first publish lazily creates its gate. `endComputation` releases it
  from `finally`, keeping the mark during release for caller-runs executors. Do not stage a
  publication outside a computation: an `unwrap` loader has no release point. No listeners means
  no gate allocation.
- Queue append uses atomic `compute`; cleanup uses conditional `remove(key, future)`. The
  preceding identity `get` is only a fast path. These protect a successor's slot and ordering;
  do not add locking. Await or ignore synchronous futures on relevant operation exits, including
  bulk failures. See [dispatch and commit](../docs/jsr107-conformance.md#dispatch-and-commit).
- Public operations use `requireOperable()`. While the publishing thread is marked, refuse
  callback re-entry, including reads (lazy expiry may compute). Do not mark dispatch threads:
  cross-cache cycles and an asynchronously dispatched synchronous listener accessing its own key
  are accepted residual hazards. Maintenance-thread filters remain unmarked unless maintenance
  is inline inside an already marked computation. Batch writer callbacks outside the per-key
  loop are also unmarked. See [callback re-entry](../docs/jsr107-conformance.md#callback-re-entry).
- Throwing vendor `Weigher`/native `Expiry` callbacks can abort storage after writer/event
  publication. This accepted misuse boundary differs from standard `ExpiryPolicy`, whose runtime
  failures are caught. Do not move publication outside `compute` to handle it. See
  [native extensions](../docs/jsr107-conformance.md#native-extensions).

## Configuration and lifecycle

- HOCON (`TypesafeConfigurator`) can supply a cache before programmatic configuration is used.
  Validate resolved through-mode factories once in `CacheFactory.createCache`, before any factory
  runs or the name is published. A missing required loader/writer factory throws
  `IllegalArgumentException`; a factory returning null is a separate initialization question.
  `isReadThrough()` alone selects the loading proxy after validation. See
  [configuration](../docs/jsr107-conformance.md#configuration).
- `CacheProxy.copyOf` passes through NPE, ISE, CCE, and `CacheException`, wrapping other runtime
  failures as `CacheException`. Loader copying retains `CacheLoaderException`. Do not copy live
  listener-configuration leaves or re-key registrations by the caller's custom `equals`. See
  [copying and listener configuration](../docs/jsr107-conformance.md#copying-and-listener-configuration).
- In OSGi, creation/get paths temporarily use the manager's classloader as TCCL for
  `FactoryBuilder`. Destroy/close resolves no class names and needs no swap. Types resolve through
  TCCL too. Weak registry keys do not prevent application values/factories retaining a loader;
  weakening managers would collect live managers. Use `CachingProvider.close(ClassLoader)`.
  See [classloaders](../docs/jsr107-conformance.md#classloaders).
- A configured `ExecutorService` is cache-owned and shut down on close. Common-pool shutdown is
  a no-op. Share an executor through a plain `Executor`, e.g. `shared::execute`; do not add a
  shutdown flag. The spec does not require executor shutdown.
- `inFlight` tracks explicit asynchronous work, including `loadAll`'s `CompletionListener`
  continuation. `loadAllAndNotify` returns that future; admission, submission failure handling,
  and retirement stay in `loadAll`. Compose with `chainSynchronous()`, never join it on the load
  executor. Do not add native refreshes to the barrier. Closed-cache event suppression uses
  dispatch's `isClosed()` check independently of the bounded await. See [lifecycle](../docs/jsr107-conformance.md#lifecycle).

## Validation

- Run `:jcache:tckTest` as well as `:jcache:test` for conformance changes. Tests include the
  unpacked TCK and isolated per-JVM tests. Read what the TCK asserts: event/statistic differences
  often need parity tests. Preserve TCK interoperability when it is stricter than 1.1.1, including
  `CacheLoaderTest.shouldPropagateExceptionUsingLoadAll` and
  `CacheMBStatisticsBeanTest.testIterateAndRemove`.
- Compare primary source, not web-search summaries: local `cache-api-*-sources.jar` for javadoc,
  TCK source, and the relevant provider's code. Prior summaries reversed Ehcache 3 and Coherence's
  synchronous listener exception behavior. See the [source map](../docs/jsr107-conformance.md#sources-and-method).
- Tests run on minimum JDK 11 unless `-PjavaVersion=N` is supplied. For version-dependent paths
  such as `ExecutorService` becoming `AutoCloseable` in JDK 19, use an explicitly `AutoCloseable`
  double or select the newer JDK.
