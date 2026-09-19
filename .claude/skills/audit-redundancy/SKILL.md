---
name: audit-redundancy
description: Audit for provably redundant work or state, where clarity is the payoff and no defect or measured speedup is required
argument-hint: "[module or path to focus on, default: all source modules]"
context: fork
agent: auditor
disable-model-invocation: true
---

Find work or state that can be removed, with a proof that behavior is preserved. The payoff is a
clearer implementation: a correctness defect and a measured speedup are both optional here, and
neither may be inferred from the fact that a deletion still compiles. `/audit-performance` owns
the claim that something costs measurable time; this lens owns the claim that something does
nothing. A shorter expression that makes ownership or control flow harder to follow is not an
improvement, and neither is one that trades a cheap guard for an unconditionally expensive path,
so a proposal argues its clarity rather than assuming it. A complete pass may find nothing.

Scope: `$ARGUMENTS`, otherwise the auditor's module map plus `caffeine/src/javaPoet/`. Trace
outside that scope as far as a proof needs, without starting a second discovery sweep there.

**Report-only.** The transformation ships as a patch described in the report, never applied to
the tree; see the auditor's *Evidence Boundaries*. Record the commit and whether the tree was
dirty, since a proof is about one source snapshot.

## Entry points

**The suppression inventory is the cheapest entry point**, and the one no other lens looks at.
`.claude/rules/errorprone.md` keeps suppressions rare and prefers fixing over suppressing, so a
redundancy-flavored suppression left in main source is a pre-built candidate. Grep
`@SuppressWarnings` across the module for `RedundantCollectionOperation`, `RedundantUnmodifiable`,
`UnnecessaryLocalVariable`, `UnnecessaryReturnStatement`, `UnusedVariable`, `UnusedReturnValue`,
`ConstantValue`, `EmptyMethod`, `StatementWithEmptyBody`, `FieldCanBeFinal` and
`RedundantSuppression`. A suppression is a candidate, not a finding, and not a rejection either.
Adjudicate each one in the report from its own source, rather than taking the annotation's word
for it or this skill's. Exactly one carries a standing ruling, the `discardRefresh` prescreen
described below, and that ruling is in `ruled-out.md` where Phase 1.5 will reach it.

Do not seed from a prior report. `.local/audits/` is off limits under the auditor's *Evidence
Boundaries*, and that includes the performance reports whose unpriceable rows land in this lens.
Rediscover them.

Then the shapes that survive a refactor in this codebase:

- **Flag-guarded branches that cannot disagree.** These predicates are stable for the life of a
  cache, but only some are fixed by class selection. The generators emit `return true` for
  `evicts()`, `expiresAfterWrite()`, `refreshAfterWrite()`, `collectKeys()` and
  `collectValues()`. `expiresAfterAccess()` and `expiresVariable()` are emitted as
  `timerWheel == null` and `timerWheel != null` on the same class, and `isAsync` is a plain field
  on `BoundedLocalCache`, so those three vary per instance. A condition re-testing what the
  enclosing branch already implied is a candidate; one that is constant only in some variants, or
  only in some instances of one variant, is not. Reading the generator is what tells them apart.
- **Generator-emitted fields and methods with no reader in any variant.**
  `caffeine/src/javaPoet/java/.../Add*.java` decides what each `Node` and cache subclass carries.
  Regenerate before concluding a member is unused: a search over `caffeine/src/main/` alone
  cannot see a consumer that only exists in an emitted class.
- **Re-derivation of a result the frame already holds.** A repeated `node.getValue()`, a key
  reference rebuilt from the node, a ticker read on a path that already carries `now`. A
  lock-free probe in front of a locking operation is a guard and not a duplicate, so read the
  next section first. Most other candidates of this shape fail proof item 3, which is the point
  of checking it.
- **Locals overwritten before use, values recomputed where the earlier one still holds, and
  mutable carriers duplicating what the operation already returned.**
- **Adapter delegation that adds nothing.** In `guava/` and `jcache/`, a configured branch whose
  arms reach the same delegate call with the same arguments; in `simulator/`, a conversion or
  parse repeated per event that the settings object already holds.
- **Tautologies and unused private members**, anywhere in scope.

## What a finding looks like

Two from this codebase, both real, and neither one a defect.

**A constructor argument every consumer overwrote.** `ComputeContext` took `now` from
`expirationTicker().read()` at construction, at every compute entry point, while the compute
lambdas in `remap` and `doComputeIfAbsent` assign `ctx.now` from the ticker again before reading
it. The parameter was dead: one ticker read per compute call that nothing observed. Dropping it
also frees `computeIfAbsent` to move its own read inside the `node != null` branch, removing the
read from the miss path outright. The proof is item 2 done exit by exit, and it is the whole
finding: every path that reaches a read of `ctx.now` assigns it first, including the ones where
the assignment rides inside a `&&` operand so short-circuiting cannot skip it, and the paths that
never assign it never read it. Letting the field default to zero is safe only because that
enumeration is complete. The savings are path-specific, `computeIfPresent` keeps its own
optimistic read, and no measured gain is required to justify any of it.

**The same edit twice, needing two different proofs.** Two `discardRefresh` call sites re-derived
the key reference from a node while the enclosing frame already held it, once as a lambda
parameter and once as a `lookupKey`. That reads as one transformation applied at two sites. In
`removeNode` the lambda parameter is the object handed to `computeIfPresent` a line earlier, so
the substitution is identity and the proof is one sentence. In `put` the lookup key is a
different object from the node's `WeakKeyReference`, and the equivalence rests on
`LookupKeyReference` using `System.identityHashCode` and reference equality, plus the lookup key
holding its referent strongly so the weak reference cannot clear inside the frame. Write the
proof per site: a transformation that repeats is not a proof that repeats.

## The proof

Write the smallest before/after transformation, name the work or state it removes, and say what
becomes easier to follow. Then establish all four:

1. **Every consumer.** Readers, writers, callers, overrides, and generated variants. Check
   reflection, serialization, the generators' emitted references, initialization side effects,
   and external visibility before calling a definition unused. A search with no matches does not
   settle it when any of those can supply a consumer. Prefer LSP `findReferences` and
   `goToImplementation` over grep for this, against a populated `caffeine/build/generated/`.
2. **Every exit.** Normal return, absence/null, early return, exception, and retry. Preserve
   callback count and order, exception identity and propagation, statistics, notifications, and
   cleanup obligations. Name the gate that makes the equivalence hold.
3. **When it is observed.** A value read before a lock acquire, a user callback, a publication,
   or a future completion is not interchangeable with a later read of the same thing. Check
   weak and soft reference lifetime, obtrusion, reentrancy, coalescing, and supersession. A
   reference already returned into a local can be stable exactly where a fresh lookup would not
   be, which is what makes a "duplicate" lookup load-bearing.
4. **Ownership and ordering.** Lock ordering, VarHandle access modes, node lifecycle
   transitions, reference-key representation, and registration ownership all survive. An outcome
   flag can identify *which* caller acted even when a second caller would compute the same value.

## Shapes that look redundant and are not

**A cheap guard in front of an expensive operation is not duplicated work.**
`BoundedLocalCache.discardRefresh` tests `containsKey` before `remove` on the refresh map under
`@SuppressWarnings("RedundantCollectionOperation")`, and the suppression is correct.
`ConcurrentHashMap.remove` reaches `replaceNode`, which returns without locking only when the
target bin is empty; a key that is absent but collides into an occupied bin still takes the bin
monitor and walks the chain to find nothing. `containsKey` is a lock-free read. So the prescreen
trades one cheap probe for a pessimistic lock the map would otherwise take on every miss, and on
the hit path it is not wasted either, since it warms the bin that `remove` then touches. Doing
one cheap thing that avoids later work beats unconditionally doing the expensive thing. Price a
guard against what it skips, never against its line count, and treat a `containsKey`, `get` or
`isEmpty` before a mutating call as a guard until the locking behavior of that call says
otherwise.

**A call that looks interchangeable with a plainer one may carry a side effect a distant guard
reads.** `Pacer.schedule`'s reschedule arm must call `cancel()` rather than `future.cancel(...)`,
because the immediate-scheduler recursion guard is `future == null && nextFireTime != 0L` and
only `cancel()` reaches it. `ruled-out.md` §Core records that one as "do not simplify it back".
Expect more of this shape, and expect proof item 3 to be what catches it.

The auditor's *Project-Specific Context* lists further patterns that read as waste and are not.
At Phase 1.5 take the module's rule file and its `ruled-out.md` section together; a standing
"do not simplify" ruling lands directly on this lens, and the entry is the thing to argue past
rather than rediscover. Use `git log -L` to explain a leftover, but blame alone does not
establish that anyone intended it to be dead.

## Phases, validation, and output

Run the auditor's phases unchanged. Phase 0's pre-mortem asks where a removal would change
behavior, not where a defect hides. In Phase 3 the evaluator gets the transformation, the path
table, the assumptions, and the evidence limits with no source access: ask it for one input,
exceptional exit, or legal schedule under which the two forms differ, and whether the proposed
code actually reads better. Resolve every substantive challenge by re-reading source.

Phase 3.5 does not fire, because these findings are `low` and the auditor's pricing gate is for
`high` and `critical`. What substitutes is narrower: compile the transformed snapshot in
isolation and report that as compilation, not as validation. Keep three kinds of evidence apart
and never let one stand in for another: source equivalence, tests actually executed, and
measured runtime effects. Existing tests are coverage pointers until run. Do not promise fewer
allocations, a smaller object, or faster code from syntax alone. A performance or correctness
claim is a separate finding in its own category and owes the full Phase 3.5 witness. For focused
validation follow `.claude/rules/testing.md`, and prefer a public-API pin over a test that
mirrors the implementation detail being removed.

Report to the auditor's Phase 4 path (`AUDIT_REPORT_PATH`, else
`.local/audits/<model>/audit-redundancy.md`) and section headers, with these substitutions:

- Category `redundancy`, severity `low`, confidence describing the proof's coverage rather than
  an assumed speedup (`.claude/docs/finding-taxonomy.md`).
- Replace **Invariant/contract violated** with **Equivalence proof**: the assumptions, the gates
  the equivalence rests on, and the strongest counterexample attempted. Do not invent a broken
  contract to fill the field.
- Replace **Priced** with **Validated**: whether the transformed snapshot compiled, which
  existing tests name the path and whether they were run, and what was not measured.
- Keep proposed, rejected, and unresolved candidates in separate sections. A plausible
  unresolved counterexample belongs in the medium-confidence section with the missing fact and
  the next check, not among the proposals.
- Report generator and construction cleanups separately from anything on a read, write, or drain
  path.

Leave the shared consolidated queue alone; this is a discovery run and consolidation is a
separate pass. A zero-finding report still records the inspected scope, the work that survived
the challenges with the property that keeps it necessary, and the coverage limits.
