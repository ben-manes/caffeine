---
name: audit-third-party-contracts
description: Verify every third-party and sharp-edged JDK API usage against the contract the upstream documentation actually states
context: fork
agent: auditor
disable-model-invocation: true
---

Audit uses of external libraries and sharp-edged JDK APIs against the contract
the upstream documentation actually states — not the contract the call site
assumes. This lens is distinct from the cache-correctness lenses: the bugs are
in the seam between our code and someone else's semantics (dispose-on-error,
merge-less collectors, empty timed buffers, live-view iteration windows).

Scope: `examples/*/src/main/java/`, `simulator/src/main/java/`,
`jcache/src/main/java/`, `guava/src/main/java/`. The core is nearly
dependency-free; include it only for JDK sharp edges.

## Step 1: Enumerate touchpoints

From `gradle/libs.versions.toml` and each module's build file, list the
third-party libraries in scope. Grep each module for their package imports,
plus these JDK sharp edges: Collectors.toMap/groupingBy, CompletableFuture
composition and cancellation, live collection views held across a time window,
ServiceLoader/TCCL-dependent lookups, Object.wait/notify handshakes.

## Step 2: Extract the assumed contract

For each touchpoint, state what the call site implicitly assumes about:

- **Error path**: what happens to the pipeline/subscription/future when user
  code or the library throws? Is the failure surfaced or swallowed?
- **Degenerate input**: duplicate keys, empty batch, null element, zero
  duration, single element
- **Cancellation/disposal**: is it terminal? who observes it? can the
  structure be reused after?
- **Threading**: which thread runs the callback; what ThreadLocal, ordering,
  or reentrancy assumptions ride on that?
- **Resource lifecycle**: close/dispose idempotency, unsubscribe/leak behavior

## Step 3: Verify against the upstream contract

Quote the authoritative source — library javadoc for the version pinned in
`libs.versions.toml`, the reactive-streams specification rule, or JDK javadoc.
WebFetch the docs when unsure; do not rely on recall. A finding must cite the
upstream sentence that contradicts the call site.

High-yield contract families:

- **Reactive streams (RxJava/Reactor)**: onError is terminal and disposes; a
  subscriber without an error handler routes to a global hook and silently
  unsubscribes; timed buffers emit empty lists on every period; a cancelled
  sink rejects all future emissions; a subject with no observers drops
  emissions silently
- **Collectors.toMap** throws IllegalStateException on duplicate keys without
  a merge function
- **CompletableFuture**: exceptions in *Async stages surface only via the
  returned future; cancel() does not interrupt the running task; a throw
  inside whenComplete replaces/suppresses in non-obvious ways
- **Live vs snapshot views**: a live keySet/values passed to another operation
  observes concurrent mutations; removeAll(null) throws NPE
- **ORM/config libraries (Hibernate, Typesafe Config)**: session/entity
  lifecycle and reload semantics the integration assumes

## Step 4: Construct the violating scenario

For each contradiction, produce the concrete input or event sequence and the
observable damage (hung future, silent data loss, permanent pipeline death,
corrupted secondary structure, useless periodic load). Findings without a
constructible scenario are suspicions — label them per the auditor contract.

Classify findings with the `external-contract` category from
`.claude/docs/finding-taxonomy.md`. Also check the module's tests: if the
failing scenario is untestable because tests only exercise happy paths
(distinct keys, no failures, non-empty batches), report the test gap alongside
the bug.
