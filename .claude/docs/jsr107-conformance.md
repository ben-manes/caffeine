# JSR-107 (JCache) Conformance Reference

Sources, audit methods, and standing rulings for the `jcache/` adapter. Read the relevant topic
before raising a finding; these conclusions preserve accepted behavior and resolved defects.
Provider comparisons are recorded evidence, not a substitute for checking current source.

## Sources and method

Use the JSR-107 1.1.1 specification and API javadoc. The 1.0 PDF predates changes to
`getCacheNames` iterator behavior, typed `getCache(String)`, loader exception wrapping, and
iteration over expired entries. Check the 1.1.1 revision history before relying on older text.

- [JSR landing page](https://jcp.org/en/jsr/detail?id=107)
- [Specification](https://docs.google.com/document/d/1ijduF_tmHvBaUS7VBBU2ZN8_eEBiFaXXg9OI0_ZxCrA/edit)
- Local `cache-api-*-sources.jar`: `javax/cache/**` javadoc and package documentation.
- [Reference implementation](https://github.com/jsr107/RI), version 1.1.1 sources:
  `https://repo1.maven.org/maven2/org/jsr107/ri/cache-ri-impl/1.1.1/cache-ri-impl-1.1.1-sources.jar`.

Fetch the spec as needed; do not bundle it. Cite section or javadoc names because exports reflow.

```bash
SPEC_DOC=1ijduF_tmHvBaUS7VBBU2ZN8_eEBiFaXXg9OI0_ZxCrA
curl -fsSL "https://docs.google.com/document/d/$SPEC_DOC/export?format=txt" -o /tmp/jsr107_spec.txt
```

For an ambiguous behavior, read the spec, inspect what the TCK actually asserts, compare the RI
and at least three other providers, then check Caffeine's sibling paths. Explicit spec wording
outweighs RI behavior; the RI helps resolve ambiguity but has documented bugs. Preserve TCK
interoperability where its assertions are stricter than 1.1.1, and record the distinction.
Use the other providers to check whether the RI is an outlier. When providers split, favor the
spec text and RI interpretation and record the differing results.
Source comments and search summaries are not verified behavior. In particular, prior summaries
reversed Ehcache 3 and Coherence's synchronous listener exception handling.

| Area | Spec section / API | RI source |
|---|---|---|
| Expiry | Expiry Policies; `ExpiryPolicy` method table | `RICache`, `RICachedValue` |
| Events and filters | Cache Entry Listeners; `CacheEntryListener`, `javax.cache.event` | `RICacheEventDispatcher`, `RICacheEntryEvent`, `RICacheEntryEventFilteringIterator` |
| Counters and timing | Statistics Effects of Cache Operations; `CacheStatisticsMXBean` | `RICacheStatisticsMXBean`, counter call sites in `RICache` |
| Loader and writer | Integration; `CacheLoader`, `CacheWriter` | `RICache.writeCacheEntry`, `deleteCacheEntry`, batch calls |
| Entry processors | Entry Processors; `MutableEntry`, `EntryProcessorResult` | `EntryProcessorEntry`, `MutableEntryOperation` |
| Copying | Store-By-Value and Store-By-Reference | `RISerializingInternalConverter`, `RIReferenceInternalConverter` |
| Configuration | Configuration; factories and type enforcement | `RICache`, configuration classes |
| Lifecycle and JMX | Caching Providers; CacheManager/Cache close contracts | `RICacheManager`, `RICachingProvider`, `RICacheMXBean` |

The spec's Annotations section (CDI/Spring) is outside this adapter's scope. The audit skill's
A–N matrix covers the remaining surface. Expiry, events, statistics, integration, and processors
need event/statistic assertions: the TCK often checks only final contents. Do not infer that an
operation's expiry-event column of “No” forbids an independently timed expiry notification.
For statistics, check the operation table as well as broad counter definitions: a read-through
get is a miss, not a put; `clear` differs from `removeAll`; expiry has no dedicated MXBean counter.

### Provider source map

Fetch individual files or shallow-clone small modules. Locate current paths before citing them;
Hazelcast, Infinispan, and Coherence are large enough that a full clone is usually unnecessary.

| Provider repository | JCache expiry entry point |
|---|---|
| `cache2k/cache2k` | `TouchyJCacheAdapter`, `ExpiryPolicyAdapter.calculateExpiryTime`, `durationToTicks` |
| `oracle/coherence` | `coherence-jcache/.../localcache/LocalCache`, `JCacheEntryMetaInf.modified`, `LocalCacheValue.updateInternalValue` |
| `ehcache/ehcache-jcache` (Ehcache 2) | `JCache.setTimeTo` |
| `ehcache/ehcache3` | `Eh107Cache`, `Eh107Expiry` |
| `hazelcast/hazelcast` | `AbstractCacheRecordStore.updateRecord` |
| `infinispan/infinispan` | `jcache/commons/.../AbstractJCache.put` / `replace` |

Find other domains' writer, MXBean, listener, and copier classes in the same modules. Trace
delivery as well as publication: Coherence's UPDATED notification comes through a backing-map
listener, not an explicit event call in `put`. Distinguish local and partitioned variants.

### Validation pattern

Run `:jcache:test` and `:jcache:tckTest` for conformance changes. The latter unpacks tests into
`jcache/build/tck/org/jsr107/tck/`; its `cache-tests-1.1.1-test-sources.jar` is also in the Gradle
cache. Read `CacheExpiryTest` and `CacheMBStatisticsBeanTest` assertions, not just test names.
`CacheLoaderTest.shouldPropagateExceptionUsingLoadAll` retains stricter wrapping than 1.1.1;
`CacheMBStatisticsBeanTest.testIterateAndRemove` pins iterator hit/removal accounting.

Use a parameterized parity test when sibling operations should agree on events, counters, and
contents. Examples: `JCacheCreationExpiryTest.writeOp_absent_zeroCreationExpiry` and
`JCacheUpdateExpiryTest.writeOp_present_zeroUpdateExpiry`. Establish the expected direction from
the contract first; an existing sibling can itself be wrong.

## Expiry

### Zero expiry

Creation and update have different contracts. `getExpiryForCreation() == Duration.ZERO` means
the entry is not added; zero update expiry means it is updated and immediately expires.

| Case | Store | Operation event | Put count | Writer |
|---|---|---|---|---|
| Zero creation | Suppressed | No CREATED; EXPIRED carries the new value | None | Still runs |
| Zero update | Store immediately expired | UPDATED, then independently timed EXPIRED | Counted | Runs before commit |

Do not suppress the writer on zero creation. It persists the caller's write intent even though
the cache stores nothing, matching `RICache.writeCacheEntry` before creation-expiry evaluation.
Do not substitute the prior value or null in its EXPIRED event: that would hide disposal of the
new value from resource-tracking listeners.

Recorded creation-path comparison, from source on 2026-08-29:

| Provider | Writer before expiry guard | `putIfAbsent` result | Put counted | EXPIRED |
|---|---|---|---|---|
| RI | yes | false | no | yes |
| Coherence | yes | false | no | no |
| Caffeine | yes | false | no | yes |
| Ehcache 2 | suppressed | true | unverified | unverified |
| Hazelcast | suppressed | unverified | no | no |
| Infinispan | below adapter; inconclusive | unverified | no | unverified |
| cache2k | delegated to core; inconclusive | unverified | unverified | unverified |

The RI and Coherence have comments suggesting suppression that disagree with their writer/event
call order; trace the implementation. `CacheMBStatisticsBeanTest.testExpiryOnCreation` pins zero
puts for `put`/`putAll`; `CacheExpiryTest.expire_whenCreated` discards `putIfAbsent`'s result.
Caffeine records a miss for an absent zero-creation `putIfAbsent`, following the statistics
table's prior-presence rule and `getAndPut`; the RI incorrectly derives a hit from `false`.
Pin: `JCacheCreationExpiryTest.putIfAbsent_absent_zeroCreationExpiry_recordsMissNotHit`.

Recorded zero-update comparison:

| Behavior | UPDATED / put counted | Providers |
|---|---|---|
| Store immediately expired | yes / yes | RI, Coherence, cache2k, Ehcache 3 |
| Remove or suppress | no / no | Infinispan, Ehcache 2, Hazelcast |

Caffeine follows the first behavior because the spec distinguishes creation from update and the
RI agrees. All put/replace/invoke siblings gate suppression on creation only
(`expirable == null`). `CacheExpiryTest.expire_whenModified` checks only absence afterward, so
both behaviors pass. The parity tests above check the missing event/statistic distinction.

### Policy defaults

- Creation returning null or throwing a runtime exception yields eternal expiry
  (`Long.MAX_VALUE`), consistently in `CacheProxy` and `JCacheLoaderAdapter`. Null creation is
  implementation-defined; the RI would NPE. The normal CREATED/put effects still occur.
- Update/access returning null or throwing leaves expiry unchanged. The loader helper takes a
  `created` flag; reload translates the update `Long.MIN_VALUE` sentinel to the old wrapper's
  deadline. Do not turn a finite deadline eternal or log an NPE for an ordinary null update.
  Pins: `CacheLoaderTest.reload_nullUpdateExpiry_keepsExpiration` and
  `reload_updateExpiryFailure_keepsExpiration`.
- Expiration counts in `CacheEvictions`, whether discovered lazily or through native EXPIRED
  removal. RI, Ehcache 3, cache2k, and Hazelcast exclude it, but the spec is ambiguous and offers
  no expiry counter. This accepted choice makes expiry visible to dashboards; do not remove it
  for ecosystem parity.
- An expired entry found by remove/getAndRemove/removeAll emits EXPIRED and an eviction, not
  REMOVED and a removal. The RI set overload disagrees with its own single-key/no-argument paths.
  TCK pins: `CacheExpiryTest.testCacheStatisticsRemoveAll` and
  `testCacheStatisticsRemoveAllNoneExpired`; the expired set-overload pairing is not pinned.

### Deadline conversion

`ExpirableToExpiry` subtracts in milliseconds, then converts the remaining duration once.
The wrapper deadline and ticker share an arbitrary origin. Converting the absolute deadline to
nanoseconds first saturates near the signed-long horizon: at `Long.MAX_VALUE - 100`, a one-minute
deadline formerly became 100 ns. The lazy `Expirable.hasExpired` gate was correct; the native
timer was the affected mirror. Custom tickers can reach this boundary, and
`JCacheFixture.START_TIME` exercises the full long range.

Keep millisecond granularity (the native timer may fire up to about 1 ms later), core's
`MAXIMUM_EXPIRY` clamp, and call-site `Math.max(0L, ...)` handling of expire-now. Keep the ticker
read rather than using core's `currentTime`: dropping it was reverted because auto-increment
tickers shift observed times. Pin: `JCacheExpiryTest.nativeDeadline_nearNanosecondSaturation`.

Loader creation expiry uses the same ±1 correction as write/access expiry when a finite deadline
collides with sentinel `0` or `Long.MAX_VALUE`. Pins:
`CacheLoaderTest.load_adjustedTimeSentinelZero` / `load_adjustedTimeSentinelMax`.

### Access expiry

`getAccessExpireTime` evaluates the policy; `setAccessExpireTime` writes the held wrapper's
timestamp on every access path. Only lock-free reads call `setVariableExpiration` to update the
native timer by key. Writes already refresh it through core's `expireAfterUpdate`, including a
same-wrapper return. Calling policy `setExpiresAfter` inside their compute violates the policy
API's atomic-scope restriction and can enter maintenance while holding a bin lock.
Read-path anchors: `getAndFilterExpiredEntries`, `EntryIterator.hasNext`, and
`LoadingCacheProxy.getOrLoad`.

A replacement between a read and its by-key timer update can receive the old read's native
deadline. The old wrapper alone receives the timestamp, so this does not permit a stale value
through its lazy-expiry check. `getExpiryForAccess()` is parameterless; standard Accessed/Touched
policies use the same access and creation duration. A custom access duration shorter than creation
can cause early native eviction and EXPIRED for the replacement. This race is accepted; do not
add a bin lock or identity guard to every access-expiry read.

An accepted timestamp-race report combined zero access expiry, an eternal entry, and a concurrent
processor READ to produce `EntryProcessorException`. Its `postProcess` path predates pre-processor
expiry reconciliation and is not a current reproducer; the lock-free boundary remains intentional.

The iterator stamps access expiry in `hasNext()` when staging the entry. Deferring to `next()`
widens its expiry hole or makes `hasNext()` promise an unavailable entry; the iterator contract
already allows `next()` to return null after expiry. Calling `hasNext()` without `next()` extends
one entry's deadline, an accepted cost. TCK: `CacheExpiryTest.iteratorNextShouldCallGetExpiryForAccessedEntry`.

## Native extensions

Returning the same wrapper from a query still reaches core's native-write metadata. Failed
`putIfAbsent` (including keep-existing loadAll), a NONE-action processor, and eternal-policy
failed conditional writes/processor READ can reset native write time and cancel refresh. Thus
polling may defer vendor `refresh.after-write` or eager native TTL. This is accepted: JCache
expiry comes from `ExpiryPolicy`, while those Caffeine settings are optional extensions.

Do not expose `RemapHints` as a public no-op escape. Treating an unchanged value as no write
would change `asMap().compute` for all users and leave write-deque reorder paired with a stale
timestamp. A `computeIfAbsent` plus expired fallback splits the atomic operation and does not
solve invoke. These repairs were rejected.

A throwing extension `Weigher` (`setWeigherFactory` / `setMaximumWeight`) or native `Expiry`
(`setExpiryFactory` / `ExpiryAdapter`) runs in core after the adapter's remapping
function has written through and published the event, so it can abort storage after those
effects. Standard `ExpiryPolicy` is different: its runtime exceptions are caught by the adapter.
The extension case remains accepted misuse of callbacks whose core contract forbids throwing.
Notifications/writer effects cannot be rolled back, and core has no post-metadata hook. Attempted
creation/update notifications are accepted; store/cache discrepancies need external reconciliation.
Do not move publication outside compute, which would lose per-key order. Unlike invoke,
put/getAndPut do not blanket-clear synchronous futures after arbitrary extension failure; a
pending notification reaching the next operation is also within this accepted boundary. A gate
must still be released from `finally` so dispatch can drain.

## Entry processors

`EntryProcessorEntry.Action` records the dominant operation. Preserve these distinctions:

| Sequence / terminal action | Resulting effects |
|---|---|
| Read-through `getValue()` / LOADED | Miss, no put; follows `Cache.get` and the RI LOAD case |
| Remove an absent or expired entry / DELETED | Writer delete if write-through; no REMOVED or removal count |
| Load, then remove / LOADED → DELETED | Writer delete; intentionally differs from RI's cancellation |
| Create, then remove / CREATED → NONE | Full no-op |
| Load, set a value, then remove / LOADED → CREATED → NONE | Full no-op, including writer and events |
| Update a present entry / UPDATED | Writer must succeed before put count, UPDATED, and store |

The RI counts/fires a removal with null for absent processor removal, unlike its own `remove(K)`.
Caffeine follows the listener table's requirement that an entry was removed; the statistics
table's wording is inconsistent. TCK covers remove-on-present only. For a read-through load,
pin the miss/no-put behavior with `CacheProxyTest.invoke_readThroughLoad_recordsMissNotPut`.

`invoke` reconciles a lazily expired prior before the processor: publish EXPIRED, count eviction,
and expose absence. If the processor or writer then throws any `Throwable`, commit removal of
that expired prior, await its synchronous listener, and rethrow after compute via
`processorFailure` (`Error` unchanged; other failures as `EntryProcessorException`). Otherwise
the already-published expiry would be orphaned and could fire again. `postProcess` is expiry-free;
READ/UPDATED require a live prior, so do not re-read the clock or restore an expiry check there.

Failures inside processor invocation, including write-through, are wrapped in
`EntryProcessorException`; the spec's “Exceptions in EntryProcessors” section includes failures
from the caching implementation itself. Single-key writer failure therefore surfaces as
`EntryProcessorException(CacheWriterException)`. The RI exposes a raw writer exception because
its writer is outside the catch; cache2k wraps and Ehcache 3 exposes `CacheWritingException`.
TCK accepts all as `CacheException`; explicit spec text governs this corner.

Pre-processor key-copy failure in single-key invoke remains a raw `CacheException`, consistent
with the API's general cache-failure surface. `invokeAll`, however, must isolate each key's
runtime failure in its `EntryProcessorResult`: preserve an existing EPE, wrap a non-EPE once,
and continue. Otherwise one uncopyable key aborts the batch and discards completed results.
RI, Ehcache 3, cache2k, and Hazelcast also isolate per-key failures (some double-wrap EPE).
Pin: `CacheProxyTest.invokeAll_perKeyFailure_isolatedNotAborted`.

Both invoke variants forward an explicitly null varargs array unchanged; only the key/keys and
processor require null rejection. RI, Ehcache 3, cache2k, Hazelcast, and Infinispan forward null;
Coherence indirectly reads its length. Normal omitted varargs remain an empty nonnull array.
Pins: `CacheProxyTest.invoke_nullArgumentsArray_forwarded` and
`invokeAll_nullArgumentsArray_forwarded`.

Two spec-aligned differences from the RI retain coverage gaps: remove then getValue returns
null without loading, and a null read-through load remains consumed as absent rather than
reloading on each getValue. Existing remove/read tests have no loader; repeated reads after a
null load lack a dedicated API regression test.

## Read-through and event ordering

Read-through `getAll` materializes loaded values with core `put`, replacing a value written
concurrently during the load, and `JCacheLoaderAdapter.loadAll` publishes CREATED for the loaded
value. This accepted choice treats the load as a fresh read of the system of record and preserves
notification for resource-tracking listeners. Do not align it with
`loadAll(replaceExistingValues=false)`, whose explicit contract is to keep existing values.
That separate API uses `loadAllAndKeepExisting` / putIfAbsent. Two CREATED events without an
intervening UPDATED are an accepted concurrent-load outcome.

The listener contract does promise per-key event ordering for synchronous and asynchronous
listeners, and says listeners fire after the cache mutation. The accepted read-through behavior
diverges from those promises: loader publication precedes core bulk storage, so CREATED can
precede EXPIRED for an expired prior even without a concurrent writer. Single-key loading first
discards the expired entry. Do not describe the contract itself as lacking an ordering promise.

Recorded evidence: on 2026-09-10 the default executor produced the inversion in 11 of 20 runs;
the direct executor ordered it because `getAllPresent` reaped inline. The accepted rationale is
the timing/transport behavior of distributed providers, while preserving the contract difference:
Hazelcast 5.7's per-entry cache-event factories did not set the publication order key (bulk
removal used the key-set hash); Coherence localcache dispatched EXPIRED before loading, but its
partitioned expiry used a separate synthetic-event listener and delivered asynchronously even to
synchronous registrations. The RI accumulates per-operation events in one JVM and orders this
case. Arrival order in a distributed replica also cannot cover lost-node or split-brain events;
the recorded decision accepts reconciliation by transition rather than imposing a new adapter
ordering guarantee. These observations are not a claim that the spec permits the divergence.

If a concrete requirement justifies repair, the recorded direction is core `getAll`: discard
expired entries in the miss set before loading, as single-key loading does. The adapter's quiet
accessors expose no expired mapping for a conditional removal. Treat publication-before-storage
and reload-before-prior-expiry as the same accepted design choice, not independent new findings.

## Statistics

### Operation timing

- Loading get/getAll opens a per-thread timing scope after recording a miss.
  `JCacheLoaderAdapter` contributes only time spent in `delegate.load`/`loadAll`; the outer
  operation records total minus loader time once, from `finally`. Copying, expiry evaluation,
  publication, and failure handling stay included. Native loads through
  `unwrap(LoadingCache.class)` have no scope and do not alter JCache get time.
- Do not restore a negative global pre-credit. An output-copy failure or native load without
  an enclosing JCache read left that credit unbalanced and made `AverageGetTime` negative.
  RI, Hazelcast, Infinispan, and cache2k avoid negative means, although some include loader time.
  Pins: `CacheLoaderTest.load_outputCopyFailure_keepsGetTimeNonNegative`,
  `load_failure_excludesLoaderTime`, `load_copierTime_includedInGetTime`, and
  `nativeLoad_doesNotChangeJCacheGetTime` (single and bulk variants).
- Successful plain getAll ends its timer after `copyMap`, matching get and loading reads.
  RI and Coherence include return conversion; Hazelcast's bulk per-key timer and cache2k's
  core load-time metric differ. The MXBean's execution-time definition and internal parity
  support inclusion. Pin: `CacheProxyTest.readOp_outputCopyTime_includedInGetTime`.
  Failed-read hit accounting remains distinct: plain get and the RI count a hit only after
  successful output conversion.
- PutAll starts timing before preparing `CopiedEntry` inputs; all copies still finish before
  writeAll and only successful stores count as puts. RI starts before conversion; Coherence
  delegates to its copy-inclusive put, while Hazelcast times below serialization and cache2k
  reports no put mean. Pin: `CacheProxyTest.writeOp_inputCopyTime_includedInPutTime` across
  put/putAll, create/update, and statistics enabled/disabled.
- `CacheGets = hits + misses`. Caffeine divides each average duration by its own operation
  counter; the RI's three averages all divide by gets, a known RI bug to avoid copying.

### Commit and failure accounting

Writer failure suppresses the operation's put/removal count as well as its mutation event and
store. This includes invoke UPDATED, whose writer must precede the count. Parity pins:
`CacheWriterTest.writeOp_failingWriter_noPutsRecorded`, `writeOp_writerSucceeds_recordsPut`, and
`removeOp_failingWriter_noRemovalsRecorded` across direct, batch, processor, and iterator paths.

After a committed effect, capture a synchronous listener failure with `awaitSynchronousFailure`,
record the required counters/timers using the operation's existing start-state rules, and then
`rethrowListenerFailure`. Direct operations follow bulk/invoke/loading siblings. Returned-value
copying in getAndRemove/getAndReplace happens after accounting, as in getAndPut, so copier failure
does not erase the committed effect. The spec does not prescribe this ordering against listener
failure; required counters and sibling parity justify it. This covers the specified listener
exception, not arbitrary `Error`; enable/disable races remain spec-undefined. Pins:
`EventDispatcherTest.synchronousListenerFailure_committedMutationRetainsStatistics` and
`synchronousExpiredListenerFailure_retainsGetStatistics`.

## Write-through

Single-key writer calls and their cache mutations share the per-key compute lock. This follows
`CacheWriter`'s non-batch atomicity contract. In particular remove/getAndRemove must use `compute`,
not computeIfPresent, because delete must run even for absence. A writer call before compute lets
a same-key put interleave, leaving cache and store inconsistent. Preserve `publishToWriter` so
bulk loops can reuse the mutation without repeating writer calls. Pin:
`CacheWriterTest.removeThrough_racingSameKeyPut_noStoreCacheDivergence`.
RI, Ehcache 3, Hazelcast, cache2k, and Coherence partitioned serialize these effects; recorded
Coherence localcache had the old unlocked writer/removal window.

Bulk writeAll/deleteAll runs once before per-key computations. The spec exempts batch methods
from cross-key atomicity; a racing single-key operation can invert cache/store order across
that window. This is accepted for both putAll and removeAll. RI locks all keys beyond the spec;
Ehcache 3 and Coherence partitioned batch like Caffeine, while cache2k/Infinispan/Coherence local
loop per-key through their persistence SPIs. Do not replace batching with per-key writer calls
solely for RI parity. The Integration contract also bounds write-through guarantees to the cache
being the application's only writer to the external resource.

Treat a non-throwing deleteAll as full success. Honor its residual collection only on a partial
failure, when it identifies entries that failed. The RI's residual-on-success interpretation
made removeAll a no-op for non-clearing writers and was rejected. Pin:
`CacheWriterTest.removeAll_nonClearingWriter_stillEmptiesCache`.

The batch exemption does not permit writes the adapter later refuses because copying failed.
Copy all putAll keys/values before writeAll and reuse `CopiedEntry` in the store loop. A copier
failure then writes/caches nothing. Pins: `CacheProxyTest.putAll_writeThrough_copierThrows_doesNotWriteToTheStore`
and `EventDispatcherTest.putAll_copierFails_abortsBeforeAnyCommit`. If a throwing native extension
still aborts a loop after partial writer failure, preserve that `CacheWriterException` as
suppressed, not replaced. Pin: `CacheProxyTest.putAll_writerPartiallyFails_storeThrows_retainsWriterFailure`.

No-argument removeAll delegates to the native map key set, which filters natively expired entries.
Those expired-but-unreaped keys are omitted from deleteAll, unlike the RI. This remains an
accepted reading of an existing mapping, with no TCK assertion resolving it.

## Events and callbacks

### Dispatch and commit

Append events inside the mutating compute to preserve per-key ordering, but stage listener
execution until it returns. `beginComputation` marks the publishing thread; the first publish
lazily creates a gate. `endComputation` releases the gate from `finally`, holding the mark through
release because a caller-runs executor may dispatch there. A throwing Weigher/native Expiry must
not leave the chain blocked. No listeners means no gate allocation. Do not stage an event outside
a computation, such as an unwrap-driven load, because it has no matching release.

This staging makes ordinary listeners observe committed mappings. RI dispatches after put under
its key lock; Ehcache 3 releases a StoreEventSink after compute; Hazelcast publishes after record
mutation; Infinispan forwards post events; Coherence uses post-mutation MapEvents. Recorded cache2k
listeners run before its final value write. The read-through publication exception is described
above. Pins: `EventDispatcherTest.publish_listenerObservesTheCommittedMutation` (observes through
the native cache because callback JCache re-entry is refused),
`publish_computationThrowsAfterPublishing_doesNotWedgeTheKey`, and
`CacheProxyTest.unwrap_nativeLoad_dispatchesAndDoesNotStallTheKey`.

Every publication uses a dependent CompletableFuture stage, including the first event for a key.
A source `supplyAsync` calls `executor.execute` synchronously; rejection inside the cache compute
can abort the cache mutation after the writer succeeds. A dependent stage captures rejection in
its future, so commit completes and the synchronous caller receives `CacheEntryListenerException`
afterward. This fixed the earlier first-event/successor difference under the non-batch writer
atomicity rule; the rejecting executor is still misconfigured. The “first” branch is common
because idle-key cleanup empties the slot. Pin:
`CacheProxyTest.put_writeThrough_eventExecutorRejects_doesNotSplitTheStore`.

The queue needs no extra lock: append is an atomic map compute, cleanup is conditional
`remove(key, future)`, and its preceding identity get is only a fast path. A successor installed
before cleanup survives; cleanup before a successor permits a new chain only after the old one
completed. Await/ignore pending synchronous futures on relevant exits and clear in finally.
Bulk operations must drain already-published events even when the loop fails. putAll copying
now happens before any commit, covered by
`EventDispatcherTest.putAll_copierFails_abortsBeforeAnyCommit`; the earlier mid-loop copier test
was removed, but later failures still require draining.

An accepted report described duplicate EXPIRED, but exactly-once eviction counts, when a listener
ran before executor rejection aborted a reap. It predates dependent-stage publication. Preserve
the accepted executor-failure boundary, but require a current throwing path before claiming that
mechanism remains reproducible.

Native size/weight eviction publishes quiet REMOVED; native EXPIRED publishes quiet EXPIRED.
Refresh reload publishes quiet UPDATED, EXPIRED for zero update expiry, or REMOVED on a miss.
Quiet means no synchronous caller await: it informs resource-tracking listeners without blocking
the evicting/refresh thread. The ecosystem generally omits eviction events (RI never evicts).
Clearing natively expired residents can produce quiet EXPIRED and eviction counts through core's
removal cause, even though ordinary explicit clear removals are silent. Closed-cache delivery is
separately suppressed by dispatch's closed check.

### Listener failures

Filters run inside compute and decide whether an event is published. A filter runtime failure
is logged and returns false from `GuardedCacheEntryEventFilter.evaluate`; it must not abort a
mutation after the writer or earlier registrations already took effect. This differs from
post-commit listener failure. RI commits before propagating filter failures; Hazelcast/cache2k
filter at delivery. Pin: `EventDispatcherTest.publishCreated_filterThrows`.

A synchronous listener's `CacheEntryListenerException` passes through; other listener runtime
exceptions are wrapped in it. `javax.cache.event` package-info and `CacheEntryListener` require
propagation. Listener `Error` is logged and rethrown unchanged, not wrapped. Ordinary listener
failure travels as a chain result so it does not break subsequent same-key delivery;
`awaitSynchronous` throws the first failure and suppresses extras. Exceptional executor futures
also become `CacheEntryListenerException`. Async/quiet failures are logged because there is no
synchronous caller. Mutation remains committed. Spec, RI, cache2k, Hazelcast, and Infinispan
support propagation; recorded Ehcache 3 and Coherence implementations swallow/log instead.

Pins: `EventDispatcherTest.put_syncListenerThrows_propagatesToCaller`,
`publishCreated_asyncListenerThrows_swallowed`,
`publishCreated_syncListenerThrows_subsequentEventStillDelivered`,
`awaitSynchronous_listenerException`, `awaitSynchronous_listenerExceptions_suppressed`, and
`EventTypeAwareListenerTest`.

A listener failure must not replace the operation's own failure. Use `awaitAndSuppressFailure`
on the primary failure; a bare finally-await can otherwise replace a copier exception with an
expired listener's exception. Plain getAll must follow loading getAll and the bulk/invoke paths.
Pin: `EventDispatcherTest.getAll_copierThrows_retainsPrimaryFailure`. See statistics above for
accounting before rethrowing a post-commit listener exception.

### Callback re-entry

`requireOperable()` refuses operations on the publishing thread while its computation mark is
set, with `IllegalStateException: Recursive cache operation`. Filter, processor, loader, writer,
expiry-policy, and copier callbacks inside a computation can otherwise acquire another bin while
holding one; caller-runs listeners can await the dispatch future executing themselves. Keep the
mark through gate release and refuse reads too: lazy expiry makes get/containsKey/getAll compute,
so a read-only exemption would depend on whether a key happened to expire.

The mark is per cache/thread, not a general listener ban. Batch writeAll/deleteAll runs before
the per-key loop and may use the cache (`CacheWriterTest.removeAll_racingInsert`). An eviction
filter on an asynchronous maintenance thread has no mark and may read the cache; inline
maintenance inherits an existing mark. Adding a mark there can abort publication before other
listeners receive their events, so do not broaden it merely for symmetry.

The spec permits implementation-specific deadlock detection. Two accepted hazards remain:
cross-cache listener cycles and a synchronous listener dispatched on another thread that operates
on its own key, chains behind itself, then awaits itself. The latter was reproduced with the gate
both enabled and disabled; moving execution outside compute did not create it. Do not extend the
mark to dispatch threads. Pins: `EventDispatcherTest.publish_listenerUsesTheCache_isRejected`
and `invoke_processorUsesTheCache_isRejected`. The probe included a positive control and found
no re-entry in the then 493 TCK / 603 unit tests.

## Copying and listener configuration

### Copy boundaries and failures

`CacheProxy.copyOf` passes through `NullPointerException`, `IllegalStateException`,
`ClassCastException`, and `CacheException`, the API's declared failure types; it wraps other
runtime exceptions in CacheException across reads, writes, getAnd operations, and iteration.
A user's readObject throwing ISE therefore surfaces raw intentionally. The primitive requires a
nonnull argument; nullable prior-value returns guard it explicitly. Loader copying retains its
contextual `CacheLoaderException`, including the TCK-required wrapping. Pin:
`CacheProxyTest.copierFailure_wrappedInCacheException`.

`JavaSerializationCopier` reports nonserializable values as CacheException, matching its
deserialize path and the cache API. Do not restore `UncheckedIOException` or follow the RI's
serialize-side `IllegalArgumentException`; cache2k uses CacheException, while Hazelcast also
differs. Pins: `JavaSerializationCopierTest.serializable_fail` and
`CacheWriterTest.putIfAbsent_nonSerializableValue_doesNotWrite`.

`JCacheLoaderAdapter.loadAll` copies values but stores the loader-returned key without another
copy. Requested keys are already copied on input, and application returns (`copyMap`, iterator
EntryProxy) copy on output. This is not equivalent to storing an uncopied caller key in put;
the loader's internal key is not otherwise exposed through those returns. Its CREATED event is
within the event-aliasing exception below. Do not add a loader key copy for false put/load parity.

### Event aliasing

Store-by-value events expose stored values: CREATED/UPDATED use the stored copy, and old/removed/
expired values come from `Expirable.get()`. A listener can therefore mutate the cached value.
Events also carry uncopied keys; ordinary put publication exposes the caller key, not its stored
copy, so mutation there cannot corrupt the cache's stored-key lookup. Preserve this distinction
when discussing loader events, which can expose the loader's stored instance.

This is accepted: the spec's copy guarantee addresses application mutation, the TCK
`StoreByValueTest` does not check listener payloads, and copying each event would tax every
listener-bearing operation. cache2k also exposes stored values; the RI supplies caller instances
for create/update and deserializes for expiry/removal as a consequence of its serialized store.
Do not add event copies without a concrete requirement.

The key is also the asynchronous dispatch queue's identity. Mutating its hash while dispatch is
pending can strand the completed slot in the old bin. Recorded 2026-09-10: 100 mutated keys kept
100 slots, later publication did not reclaim them, and deregistration/close cleared them. Direct
execution retained none; synchronous writes await before caller mutation. Such caller mutation
is legal under store-by-value (`StoreByValueTest.get_Existing_MutateKey` uses Date), but the
recorded seven-provider comparison found no equivalent protection: cache2k's
`AsyncDispatcher.keyQueue` and Infinispan's `latchesByEventSource` also use key hashes; RI has no
per-key state, Ehcache copies the key into its store, and Hazelcast, Redisson, and Ignite deliver
deserialized listener keys. The report was accepted as an unsupported usage edge, not reclassified
here as invalid application input.

If this needs repair, split dispatch identity from listener payload. Passing the stored
`copiedKey` to both would let listener mutation corrupt the cache lookup, introducing a worse
aliasing path. Preserve this rejected-repair boundary.

### Registration identity and configuration leaves

Registration and deregistration normalize listener settings through a defensive
`MutableCacheEntryListenerConfiguration` copy. Identity uses its specified factory/flag field
equality, not a custom caller configuration's equals. Two field-equal custom objects that call
themselves unequal therefore register once (the RI fires both). The stable copy prevents caller
mutation from changing a registration key; deregister must perform the same normalization.
Do not restore raw-config keys or the old deregistration-key mismatch.

`getConfiguration()` returns a read-only configuration with an unmodifiable listener iterable,
but retains its live mutable listener-setting leaves. This accepted shallow-immutability reading
is stricter than the RI's live mutable configuration and comparable to Ehcache 3's shared leaves.
Dispatch is insulated by Registration's own copy; leaf mutation can affect reporting and later
deregistration matching. TCK only pins isolation from changes to the original create configuration.

Copying every leaf was built and rejected: MCELC's instanceof-based equals did not match a
user-implemented original configuration, while it matched Registration's copy. Deregister then
removed dispatch state but left the configuration entry, so the listener remained listed, was
not closed, and could not be registered again. Do not repeat that repair.

Runtime registration records configuration first so true duplicates fail before factories run.
If a factory throws, roll that entry back so retry succeeds. If filter construction fails after
listener construction, close the listener and suppress any close failure onto the primary.
Pins: `CacheProxyTest.registerCacheEntryListener_factoryThrows_isRetryable` and
`registerCacheEntryListener_filterFactoryThrows_closesTheListener`.

## Configuration

HOCON `application.conf` can supply caches before programmatic creation. Vendor
`CaffeineConfiguration` and shipped `reference.conf` default store-by-value to false; standard
`MutableConfiguration` defaults true and its flag is reapplied by `resolveConfigurationFor`.
This vendor-only difference is intentional; `StoreByValueTest` covers the standard surface.

Validate resolved readThrough/writeThrough dependencies once in `CacheFactory.createCache`,
before building any ticker, executor, scheduler, copier, policy, listener, loader, or writer.
A missing required factory is an invalid configuration and throws IllegalArgumentException
without publishing the name or causing factory side effects. False flags remain valid without
factories. A supplied factory returning null is a separate initialization issue. After validation,
`isReadThrough()` alone selects the loading proxy; do not restore an extra factory-present gate
that silently substitutes a non-loading proxy for invalid configuration.

This follows `MutableConfiguration.setReadThrough`/`setWriteThrough` and
`CacheManager.createCache`. RI tolerates missing factories; Ehcache 3's
`ConfigurationMerger.initCacheLoaderWriter` rejects them. TCK's `CacheMXBeanTest.testCustomConfiguration`
uses false flags and does not resolve the split. HOCON read-through with a null loader must fail
from getCache, not degrade silently. Pins: `CacheManagerTest.isReadThrough`,
`invalidThroughConfiguration_hasNoCreationSideEffects`, and `createCache_minimalConfiguration`.

`TypesafeConfigurator.from` ignores only `ConfigException.BadPath`, returning Optional.empty
because valid JCache names need not fit Typesafe's path grammar. Wrap other ConfigExceptions
(Missing/WrongType) in CacheException with their cause, as configuration failures through the
cache API; do not restore raw Typesafe exceptions. Pin:
`TypesafeConfigurationTest.from_malformedSetting`. Separate existing paths remain unchanged:
type-resolution CNFE becomes ISE, and bad factory-class RuntimeException originates in the
spec's own FactoryBuilder.

`maximumWeight` without a weigher throws ISE, even though createCache's generic validation
clause uses IAE. These are vendor-extension properties; this difference remains accepted.
Pin: `CacheManagerTest.maximumWeight_noWeigher`.

### Classloaders

`TypesafeConfigurator.addKeyValueTypes` uses `Class.forName(name, true, tccl)`, falling back to
the adapter loader for a null TCCL. This retains initialization and matches the spec's
`Caching.getDefaultClassLoader` / FactoryBuilder idiom for customization classes. In OSGi,
CacheManagerImpl temporarily sets TCCL to the manager loader on create/get paths. Destroy/close
does not resolve class names and needs no swap; a user close callback's TCCL assumptions remain
the user's responsibility. Ehcache 3 does not swap on these paths.

Passing the manager loader directly only for types was rejected: outside OSGi it would resolve
types differently from the spec's own TCCL-based factories. Do not add manager-loader plumbing
to FactoryCreator alone. Pin: `TypesafeConfigurationTest.resolvesTypesViaContextClassLoader`.

The provider's WeakHashMap registry and manager's weak loader reference are best-effort.
An otherwise empty manager is collectible (`CacheManagerTest.classLoader_readThrough_notRetained`),
but application values and factory/policy/loader/writer/listener instances can retain their
defining loader through the value side, even with no cache entries. The test's Mockito::mock
factory belongs to the test loader, so it does not establish absence of this application pin.
Weakening registry values would allow live managers to disappear. The explicit
`CachingProvider.close(ClassLoader)` lifecycle operation is implemented; use it for unloading.

### Management

JMX ObjectName sanitization replaces `[,:=\n*?]` with a dot, following the RI. Distinct names or
manager URIs can collide (a:b and a=b both become a.b): the second registration is skipped by
isRegistered and destroying either unregisters the shared name. This accepted consequence is
also present in RI's `MBeanServerRegistrationUtility`. Switching to ObjectName.quote would break
operator tooling; do not do so solely to eliminate the collision.

enableManagement/enableStatistics on an unknown name is a no-op; RI raw-NPEs. The spec's null-name
NPE and closed-cache ISE do not mandate the RI's behavior for absence, and closed caches are
removed from the registry before these paths can see them. Pins:
`CacheManagerTest.enableManagement_absent` / `enableStatistics_absent`.

## Lifecycle

### Executor ownership and asynchronous work

A configured ExecutorService is cache-owned and is shut down on close (`PMD.CloseResource`
suppression documents this). The default ForkJoinPool common pool ignores shutdown. To share an
executor, supply a plain Executor such as `shared::execute`: it bypasses the ExecutorService
shutdown check and, being non-AutoCloseable, the trailing tryClose. A singleton ExecutorService
returned raw by a factory does not opt out of ownership; do not add a shutdown flag.
The JCache spec does not specifically require executor shutdown; its named Closeable list is
loader, writer, listeners, and expiry policy. Ownership is the adapter's resource policy.

`inFlight` tracks explicit asynchronous loadAll work, including its CompletionListener
notification, with a bounded 10-second close await. `loadAllAndNotify` returns the notification
future; admission, synchronous submission-failure handling, and retirement stay in loadAll.
Compose onto `dispatcher.chainSynchronous()` so completion includes notification. Do not move it
to an untracked continuation or join the chain in the load body: a single-thread executor must
be released to run the listener dispatch. A stuck listener can exhaust the timeout, reported as
TimeoutException. The bounded await does not promise that timed-out work has stopped.

Native background refresh is best-effort and is not added to inFlight or awaited through
`policy().refreshes()`. Blocking close on arbitrary user-executor refresh work was rejected.
Closed-cache event suppression comes from `EventTypeAwareListener.dispatch` checking the source's
isClosed, independently of this barrier; an owned executor's shutdown also rejects new work.

### Racing operations and shutdown

The 1.1.1 “Closing a Cache” contract rejects future operational use but does not synchronize
already-running operations; “Consistency” leaves concurrent behavior implementation-dependent,
and close need not destroy contents. An operation that passed its entry check may therefore
finish after close's invalidateAll. Retained local contents are eventually reclaimed with the
cache. Do not add operation-versus-close locking for this accepted boundary.

Straggler loads, invoke, or background refresh can reach a closed loader/expiry policy.
Recorded refresh handling catches/logs failures and records load failure; policy runtime
failures use their defaults. Close's trailing invalidateAll clears refresh ownership, preventing
a later owned-refresh commit; a refresh committed before the sweep is removed by it. This
refresh-specific guard does not imply all in-progress operations are canceled. In-flight user
I/O remains the user's lifecycle responsibility.

Executor rejection reaches synchronous callers after commit; see [dispatch and commit](#dispatch-and-commit)
for draining requirements and the superseded first-event rejection mechanism. Throwing
[native extensions](#native-extensions) retain their accepted pending-notification residual.

### Destroy and iteration

destroyCache clears before closing; close retains its trailing invalidateAll as a concurrency
sweep. The required clear→close sequence does not require retaining the registry entry until
both complete: removing it first agrees with Ehcache 3, Infinispan, Coherence, and Hazelcast;
RI keeps it until close. Pin: `CacheProxyTest.destroyCache_clearsBeforeClosing`.

Explicit clear removals do not notify listeners/writers: JCache uses core's evictionListener,
not removalListener. Natively expired residents are the previously documented exception because
their cause is EXPIRED rather than EXPLICIT; do not claim clear is unconditionally event-silent.

iterator.remove delegates to remove(K), removing the last-returned key even if its value was
replaced since next. It checks closure and expiry through that operation: an entry expired in
between emits EXPIRED/eviction, not REMOVED/removal. cache2k, Infinispan, Coherence, and Hazelcast
also delegate; RI retains an unconditional inline REMOVED path (source comparison 2026-07-20).
