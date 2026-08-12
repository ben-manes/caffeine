---
paths:
  - "jcache/**"
---

# JCache Adapter Conventions

- Significantly more complex than the Guava adapter — has its own subsystems
- **Expirable wrapper**: All values are stored as `Expirable<V>`, not `V` directly.
  JCache requires application-level expiration via `ExpiryPolicy`, separate from
  Caffeine's native expiry. Every value access involves the wrapper.
- **Access-expiry: write the Expirable timestamp everywhere, poke the native timer only on
  reads**: JCache access expiry (`get`, `getAll`, `invoke` READ, and a *failed* conditional
  `remove`/`replace`) extends `Expirable.expireTimeMillis`; `ExpirableToExpiry` (the native
  `Expiry` wired for any non-eternal policy) derives the timer-wheel deadline from it. The
  split is `getAccessExpireTime` (eval the policy) → `setAccessExpireTime` (write the
  timestamp — **every** path) → `setVariableExpiration` (poke the native timer via
  `setExpiresAfter` — **read paths only**). A read path (`get`/`getAll`/iterator) has no
  enclosing `compute` (kept lock-free for read throughput), so it pokes the native timer
  *after* the read via the by-key `setExpiresAfter` — a benign lock-free race with no identity
  guard: a concurrent write replacing the entry between the lock-free capture and the poke
  lands the access duration on the *new* entry. Never a stale read — the per-wrapper
  `Expirable.expireTimeMillis` gates every lazy read, and `setAccessExpireTime` wrote the old,
  now-dead wrapper; at worst the new entry inherits the accessed entry's native deadline
  (slightly-early eager eviction + a phantom `EXPIRED`, only when that deadline is shorter than
  the new entry's own write deadline), an accepted concurrency edge (JCache doesn't order
  concurrent events, cf. read-through `getAll`). Extra-benign because `ExpiryPolicy` is
  **parameterless**: `getExpiryForAccess()` can't depend on the value, so the misapplied
  duration is the one the policy would assign the new entry anyway — zero deviation for the
  standard `Accessed`/`Touched` policies (access == creation duration), the early edge only for
  a custom access < creation policy. Don't wrap reads in a locked `computeIfPresent` to close
  it. A write path (the failed-conditional / `invoke`-READ ops run inside
  `cache.asMap().compute*`) must **not** poke it: `setExpiresAfter` → `afterWrite` under the
  bin lock violates `Cache.policy()` ("no policy operation within another operation's atomic
  scope") and can block on the eviction lock or run maintenance inline (a same-bin nested CHM
  mutation). The compute already refreshes the native timer via its own `Expiry` on commit
  (`expireAfterUpdate` fires even on a same-instance return, reading the just-written
  timestamp). Don't add `setVariableExpiration` to a write path. **The absolute-millisecond to
  native-nanosecond bridge (`ExpirableToExpiry`) subtracts in milliseconds and converts once** —
  the deadline and the ticker share an arbitrary origin, so converting the deadline first saturates
  it near the nanosecond horizon and collapses the remaining duration. Don't reorder it, and don't
  swap the `ticker.read()` for the `currentTime` parameter core supplies (reverted as BC-1/BC-2:
  the expiry suites use an auto-increment ticker where dropping a read shifts the observed times).
- **A query-style op that returns the same `Expirable` still counts as a native write — accepted
  best-effort divergence**: core's `remap` has no way to hear "this was a no-op" from outside its
  package (`RemapHints` is package-private), so the adapter's query paths — a *failed*
  `putIfAbsent` (hence `loadAll(replaceExistingValues=false)` too), a NONE-action entry processor,
  and under an eternal `ExpiryPolicy`, where `setAccessExpireTime` no-ops, the non-matching
  conditional `remove`/`replace` and `invoke` READ — all reach `remap`'s metadata block:
  `exceedsWriteTimeTolerance` resets the node's native `writeTime` and `discardRefresh` cancels any
  in-flight reload. So with `policy.refresh.after-write` a polled `putIfAbsent` on a live key can
  starve that key's refresh, and with an eternal policy plus `policy.eager-expiration.after-write`
  it can hold the entry past its native TTL. **Won't be fixed** (adjudicated 2026-07-29): those are
  Caffeine knobs bolted onto a JCache cache, and JCache semantics come from `ExpiryPolicy` — mixing
  the two is the user's call, and the adapter is best-effort. Both repairs were rejected: teaching
  core that an unchanged value is not a write changes `asMap().compute` for every caller (and the
  trailing `UpdateTask`'s `reorder(writeOrderDeque(), ...)` would then push a stale-`writeTime` node
  behind newer entries, expiring it *later*), while routing `putIfAbsent` through `computeIfAbsent`
  with an expired-prior fallback splits an atomic op and never reaches `invoke`. Don't re-raise it,
  and don't add a public no-op hint for it.
- **Dispatch is staged until the computation commits**: `beginComputation()`/`endComputation()`
  delegate to the `EventDispatcher`, which marks the thread and lazily creates a per-thread gate on
  the first publish. `endComputation()` completes the gate from a `finally`, so a throwing
  `Weigher`/native `Expiry` cannot leave the key's chain undrained, and holds the mark across the
  release because a caller-runs executor dispatches the listeners right there. The chain append
  still happens inside the `compute`, which is what preserves per-key ordering; only the execution
  moves out. A publish with no computation in progress is not staged, since nothing would release
  it: that is what keeps the `unwrap`-driven loader from stalling the key, and it means a cache
  with no listeners allocates no gate. Don't move the publish out of the compute, don't stage a
  publish made outside a computation, and don't release the gate anywhere but the `finally`.
- **A callback may not re-enter the cache from the publishing thread**: `requireOperable()` — the
  precondition every public operation calls, in place of a bare `requireNotClosed()` — refuses
  while the dispatcher's mark is set. Callbacks that run inside the computation (filter,
  `EntryProcessor`, `CacheLoader`, `CacheWriter`, `ExpiryPolicy`, `Copier`) would otherwise take a
  second per-key lock while holding the caller's; a caller-runs listener would await the future
  executing it. Reads are refused along with writes because lazy expiry makes `get` and
  `containsKey` enter a computation, so narrowing to mutations would make the failure depend on
  whether the key had expired, which is why the check belongs at the start of the operation and not
  in `beginComputation()`. `removeAll()` delegates to the guarded `removeAll(Set)` and `invokeAll`
  to the guarded `invoke`; neither wraps its own loop in a computation, so their per-key siblings
  do the refusing. The spec permits detection rather than requiring it, so two inherent JCache
  hazards are deliberately left alone: a cross-cache listener cycle, and a synchronous listener
  dispatched asynchronously that operates on the key it was notified about (it chains behind the
  dispatch running it and awaits it — pre-existing, reproduced with the gate both on and off).
  Don't extend the mark to dispatch threads to chase those.
- **EntryProcessor state machine**: `EntryProcessorEntry.Action` tracks the dominant
  operation (NONE → READ/CREATED/UPDATED/LOADED/DELETED). `getValue()` is stateful —
  first call triggers loading. Action transitions have strict rules. `remove()` from
  CREATED resets to NONE (same-call create+delete is a no-op); DELETED with a null prior
  means `remove()` (or load-through then `remove()`) hit an absent/expired key — the
  `CacheWriter.delete` still fires (write-through), but no REMOVED event or removal stat.
- **EntryProcessor lazy-expiry is reconciled before the processor**: `invoke` reconciles a
  lazily-expired prior inline *before* running the processor — it publishes EXPIRED, counts
  the eviction, and passes the entry to the processor as absent,
  mirroring `BoundedLocalCache`'s evict-before-callback ordering. If the processor or a
  write-through `CacheWriter` then throws **any `Throwable`** (else the eager EXPIRED is
  orphaned and double-fires on the next reap), the expired prior's removal is **committed**,
  its synchronous EXPIRED listener is awaited, and the failure is rethrown after `compute`
  returns via `processorFailure` — an `Error` as-is, anything else wrapped as an
  `EntryProcessorException` (per the `Cache.invoke` javadoc's "wrap any `Exception` thrown")
  — so a failed `invoke` fires exactly one EXPIRED + one eviction. The expiration is a clock
  fact, not
  contingent on the operation succeeding. Consequently `postProcess` is
  expiry-free: a null prior means "absent", so READ/UPDATED (which imply a live prior) never
  observe one and their `requireNonNull(expirable)` is a proven invariant. Don't move the
  expiry check back into `postProcess` or re-read the clock there.
- **Write-through fires the writer INSIDE the compute**: single-key mutating ops
  (`put`/`putIfAbsent`/`replace`/`remove`/`getAndRemove`/invoke) call
  `CacheWriter.write`/`delete` *within* the `cache.asMap().compute*` lambda (gated by a
  `publishToWriter` flag), so the store write/delete and the cache mutation commit atomically
  under the per-key bin lock — a racing same-key op cannot interleave between them. Use
  `compute` (not `computeIfPresent`) when the writer must fire unconditionally, e.g. a
  write-through `delete` on an absent key. Don't hoist the writer call out ahead of the
  compute. The batch ops `putAll`/`removeAll` are the exception: their `writeAll`/`deleteAll`
  fires once up front (a single lock can't span all bins), then per-key `compute`s mutate with
  `publishToWriter=false`, so a concurrent same-key single-key op can invert the cache vs the
  store across the batch window. Spec-sanctioned — the `CacheWriter` contract exempts batch
  methods from atomicity ("not required to be atomic in the writer"); see `jsr107-conformance.md`.
  **That exemption covers ordering and cross-key atomicity, not writing entries to the store that
  the cache then refuses** (`writeAll`: "cache mutations will occur for entries that succeeded"), so
  `putAll` copies the whole batch for store-by-value **before** `writeAll` runs, via a `CopiedEntry`
  holder that the store loop reuses rather than copying twice. Don't move the copies back into the
  loop. If the loop aborts anyway (a throwing extension `Weigher`/native `Expiry` — the
  user-callback family), a saved `CacheWriterException` is retained with `addSuppressed` rather
  than replaced.
- **Read-through `getAll` clobbers (replace-on-materialize); `loadAll(keepExisting)` uses
  putIfAbsent — intentional divergence**: `LoadingCacheProxy.getAll` delegates the miss-load to
  Caffeine's `getAll`, whose `bulkLoad` stores loaded values with `put` (**replace**), so a value
  written concurrently *during* the load (the window is the whole SoR round-trip) is overwritten
  by the freshly-loaded value. This is deliberate, not a lost-write bug: the loaded value is the
  freshest read of the SoR, and — crucially — `JCacheLoaderAdapter.loadAll` always fires `CREATED`
  for it, so a `CacheEntryListener` that tracks/owns loaded resources is notified. Storing via
  `putIfAbsent` instead would **silently drop** the loaded value on a concurrent-write race → its
  `CREATED` never fires → the listener's resource tracking leaks. So read-through `getAll`
  clobbers-and-notifies rather than drops-silently, and legitimately differs from
  `loadAll(replaceExistingValues=false)` → `loadAllAndKeepExisting`, whose contract *is* "keep
  existing" (there the freshly-loaded value losing to a present entry is the point). Don't "fix"
  read-through `getAll` to `putIfAbsent`. (The race can emit two `CREATED`s for one key with no
  intervening `UPDATED` — an accepted concurrency edge; `loadAll` can't know at load time that a
  write will materialize, and JCache doesn't order concurrent events.)
- **EventDispatcher**: Per-key ordering via CompletableFuture chains. Synchronous
  listeners tracked in ThreadLocal; callers must call `awaitSynchronous()` or
  `ignoreSynchronous()`. **Every publish is a dependent stage**, including the first event for a
  key, which chains off a freshly completed future rather than calling `supplyAsync`: a dependent
  captures an executor rejection into the returned future, while a source stage calls `execute`
  synchronously and throws the rejection out of the enclosing `compute`, aborting a mutation the
  `CacheWriter` has already committed. Don't restore `supplyAsync` on the empty-slot branch. The
  per-key dispatch-queue slot (`compute` appends the dependent; a `whenComplete` removes the
  future) is **race-safe** and needs no extra locking: `compute` is atomic (CHM bin lock) so the chain-append can't interleave a
  completing future's cleanup, and cleanup is a **conditional** `remove(key, future)` that fires
  only if that exact future is still the slot's head (the preceding `get(key) == future` is only an
  optimistic fast-path). A successor chained before cleanup leaves the slot intact; cleanup before a
  successor lets the next event start a fresh chain after the prior one already completed (ordering
  preserved). Don't add locking or "fix" the get-then-remove — the conditional remove is the
  authority.
- **Account for a committed direct operation before rethrowing a synchronous listener failure**:
  call `awaitSynchronousFailure`, record the operation's required statistics using the existing
  start-state/timer rules, then rethrow the captured `CacheEntryListenerException`. This matches
  `putAll`/`removeAll`, invoke, and loading siblings. Do not turn this into a broad `Throwable`
  catch: listener `Error` is deliberately outside the specified wrapping path and propagates as-is.
- **A `CaffeineConfiguration` extension `Weigher`/native `Expiry` that throws leaves a valid
  notification without a persisted store — by-design, not a phantom event**: the adapter publishes
  CREATED/UPDATED and fires the write-through `CacheWriter` *inside* the `cache.asMap().compute`
  remapping function, but an extension `Weigher` (`setWeigherFactory`+`setMaximumWeight`) and native
  `Expiry` (`setExpiryFactory`→`ExpiryAdapter`) run in core (`BoundedLocalCache.remap`) *after* that
  function returns — the absent branch weighs + `expireAfterCreate`s under a bare `try`/`finally`
  (no catch), the present branch rethrows a non-evicted throw before `setValue`. So a throwing
  weigher/native `Expiry` aborts the store (absent: no node; present: value unchanged) though the
  event already published and the writer already wrote. Not a defect: (1) throwing violates
  Caffeine's own `Weigher`/`Expiry` contract — misuse, same family as the declined weigher/expiry
  callback warnings; the JCache-*standard* `ExpiryPolicy` surface is immune (`getWriteExpireTimeMillis`
  catches `RuntimeException` internally, so it can never abort the compute); (2) it can't be atomic —
  a listener/writer notification can't be rolled back, and Caffeine has no post-metadata listener
  hook to gate on; (3) the notification is *valid* — the value genuinely was created/updated, just
  not persisted, so a listener/SoR should receive it, and any replication/mirroring already needs
  periodic consistency reconciliation (network skew etc.) that self-corrects the un-persisted write.
  The only residue: `put`/`getAndPut` (unlike `invoke`) don't wrap the compute in
  `try..catch { ignoreSynchronous() }`, so a stranded synchronous future can deliver on the caller's
  next op — not worth littering every cache write with the guard. Don't defer the event publish
  outside the compute (breaks per-key ordering).
- **In-flight futures**: All async operations add to `inFlight` set for close() to
  await. New async operations must add futures to this set — including a continuation that still
  owes the user a callback: `loadAll`'s tracked future is `thenCompose`d onto its
  `chainSynchronous()` notification so the `CompletionListener` is inside the barrier, and the load
  body must not `join()` that chain (the synchronous dispatches share its executor). The separate
  "no events to a closed listener" guarantee comes from `dispatch`'s `isClosed()` early-return, not
  from this barrier. See `jsr107-conformance.md`.
- **`close()` shuts down an *owned* `ExecutorService` — per-cache ownership is by-design**:
  `shutdownExecutor()` calls `es.shutdown()` when `executor instanceof ExecutorService` (the
  `PMD.CloseResource` suppression marks it deliberate). The `Factory<Executor>` contract means
  "create the executor this cache owns", so shutdown-on-close is correct. The default factory is
  `ForkJoinPool::commonPool`, and `commonPool().shutdown()` is a JDK-documented **no-op**, so the
  default is safe. A user who wants to **share** one executor across caches must pass it as a plain
  `Executor` (e.g. `shared::execute`) — the `instanceof ExecutorService` gate then skips shutdown,
  and the trailing `tryClose` no-ops on a non-`AutoCloseable`. So a shared `ExecutorService` handed
  in raw (via a `Factory` returning a singleton) getting shut down on the first `close()` is misuse,
  not a defect — the plain-`Executor` wrapper is the sanctioned share opt-out. Don't add a
  don't-shutdown flag or skip the shutdown.
- **TypesafeConfigurator**: External HOCON config (`application.conf`) can intercept
  cache creation. Config-based caches take precedence over programmatic ones.
- **Validate through-mode dependencies after resolving configuration and before building**:
  `readThrough=true` requires a `CacheLoader` factory and `writeThrough=true` requires a
  `CacheWriter` factory. Validate the resolved standard/vendor configuration before any ticker,
  executor, scheduler, copier, policy, listener, loader, or writer factory is invoked; invalid
  configurations must leave the cache name unpublished and throw `IllegalArgumentException`.
  A present factory returning null is a separate initialization-failure question.
  `CacheFactory.createCache` is the single validation point: the builder no longer re-checks the
  loader factory, so `config.isReadThrough()` alone selects the loading proxy. Don't restore the
  `&& (getCacheLoaderFactory() != null)` gate — it would silently re-admit the invalid config.
- **OSGi classloader**: `CacheManagerImpl` swaps the thread context classloader to the
  manager's loader on the *creation* paths (`createCache`/`getCache`) — the JCache
  `FactoryBuilder.ClassFactory` resolves config-named factory classes via the ambient TCCL
  there. `destroyCache`/`close` intentionally do **not** swap: teardown resolves no class
  names (the resources are already-instantiated objects), so the TCCL is irrelevant to
  Caffeine's own close logic; a user `close()` that relies on the ambient TCCL is the user's
  concern (Ehcache3 swaps the TCCL nowhere). The classloader is held via WeakReference.
- Tests include JSR-107 TCK (auto-unpacked) and isolated tests (per-JVM forking)
- **Run `:jcache:tckTest` for spec-conformance changes** — not just `:jcache:test`. The
  TCK encodes interpretations (and some pre-1.1.1 strictness) that unit tests don't
  cover, e.g. `CacheLoaderTest.shouldPropagateExceptionUsingLoadAll` still asserts
  `CacheLoaderException` wrapping even after the 1.1.1 spec relaxed that rule, and
  `CacheMBStatisticsBeanTest.testIterateAndRemove` pins the hit/removal accounting
  split for iterator.next vs iterator.remove. When TCK and spec javadoc disagree,
  TCK wins.
- **Cross-check conformance from source, never web search.** When adjudicating a spec
  behavior or comparing sibling impls (RI, Ehcache 3, Hazelcast, cache2k, Infinispan,
  Coherence), read the actual code — web-search summaries are unreliable and misstated two
  impls in one session (Ehcache 3 and Coherence CE both *swallow* a synchronous listener
  exception, the opposite of what search claimed). Get the primary source: the spec text
  from the local `cache-api-*-sources.jar` (`javax/cache/**`); the RI and Coherence CE are
  open on GitHub (`jsr107/RI`, `oracle/coherence`); use the GitHub contents API to find the
  exact dispatch/notifier file before quoting it. Don't present a search result as a
  verified fact.
- **JDK-version-gated behavior**: the suite compiles and runs on the **minimum** JDK
  (11) by default — pass `-PjavaVersion=N` to run on a newer one. Behavior that only
  exists on a newer JDK won't reproduce on the default runner (e.g. `ExecutorService`
  became `AutoCloseable` in JDK 19, so a `close()`/`tryClose` path is dead on 11).
  Exercise such paths with an explicitly-`AutoCloseable` test double so the logic runs
  on any JDK, or run with `-PjavaVersion`.
