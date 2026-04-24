---
name: audit-contract-drift
description: Find places where documented API contracts and the implementation diverge
context: fork
agent: auditor
disable-model-invocation: true
---

Most audits read code; this one reads documentation first and then traces each
promise to every implementation path that should honor it. The bugs caught here
are not concurrency or arithmetic defects — they are quiet contradictions
between what the javadoc promises and what the code does.

Build a list of behavioral promises from public API docs, then trace each one
to every code path that should honor it.

Sources of contracts to enumerate:

- `Caffeine.java` — every `<b>Note:</b>` and `<b>Warning:</b>` block in a
  builder method's javadoc. Pay particular attention to:
  - `weakKeys`, `weakValues`, `softValues` — equality semantics flip to
    identity (`==`) for the configured strength
  - `expireAfter*`, `refreshAfterWrite` — duration constraints, what
    "expired" means at boundaries
  - `maximumSize`, `maximumWeight` — eviction triggering and amortization
  - `removalListener`, `evictionListener` — when and how notifications fire
- `Cache.java`, `LoadingCache.java`, `AsyncCache.java`, `AsyncLoadingCache.java`
  — javadoc of every method, especially "must", "will", "guarantees",
  "for any reason" language.
- `Policy.java` — every method's documented behavior.
- `Weigher.java`, `Expiry.java`, `RemovalListener.java`, `RemovalCause.java`
  — user-facing contracts referenced by the cache.

For each contract, record the exact wording, the configurations that activate
it, and the set of operations affected.

Then trace each promise through every code path that should honor it:

- Direct API calls on `Cache` / `LoadingCache` / `AsyncCache`
- `asMap()` view methods (`size`, `isEmpty`, `containsKey`, `containsValue`,
  `get`, `put`, `remove(k)`, `remove(k,v)`, `replace`, `compute*`, `merge`,
  `equals`, `hashCode`)
- `asMap().keySet()` / `values()` / `entrySet()` — `contains`, `remove`,
  `removeAll`, `retainAll`, `removeIf`, `iterator`, `spliterator`
- Bulk operations (`putAll`, `getAll`, `getAllPresent`, `invalidateAll`)
- `AsyncCache.synchronous()` round-trip — does the sync view honor the same
  contract as the async cache?
- Serialization round-trip — does the deserialized cache honor the same
  promises about expiration durations, loader presence, etc.?

A path that uses a different equivalence, ordering, or visibility from what
the contract promises is a **contract drift** finding. So is a path that
silently downgrades a configuration on round-trip.

Common drift patterns to check explicitly:

- **Identity vs. equals on weak/soft caches**: `weakKeys`/`weakValues`/
  `softValues` promise identity, but view collections that use
  `o.equals(value)` or `Collection.contains(value)` may apply the user's
  equality semantics instead.
- **Cardinality vs. presence asymmetry**: methods documented as treating
  in-flight async values as absent must align with `size`, `isEmpty`,
  `equals`, `hashCode`, and iterators on the same view.
- **Notification "for any reason"**: `removalListener` documented to fire
  for any cause, but async paths may drop notifications for null or
  exceptional completions.
- **Cross-version serialization**: serialized fields renamed or defaulted
  differently across versions can silently lose configuration or throw
  on round-trip.
- **Same-instance return semantics**: e.g., `compute(k, (k,v) -> v)` is
  documented one way but updates timestamps/weight regardless.
- **`size()` documented as estimate**: this is an explicit out — verify it
  is actually documented as an estimate everywhere it diverges from logical
  presence.
- **Synchronous vs. asynchronous view divergence**: the `synchronous()`
  view's `asMap()` may treat in-flight entries inconsistently across query,
  mutation, and cardinality methods.

Each finding should anchor both sides of the drift — the source of the
contract (file + javadoc snippet) and the divergent implementation (file +
method) — and include a minimal user-observable scenario where the docs and
the code disagree.
