---
name: audit-state-machine
description: Audit explicit state machines (drain status, node lifecycle, async-value lifecycle) for illegal or missed transitions
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the cache's explicit state machines for **illegal transitions**, **missed
transitions** (lost wakeups), and **ABA across transitions**. The snapshot audits
trace fields and methods one at a time; this one builds the full transition table
for each machine and asks whether every reachable interleaving keeps the machine
legal. A missed transition wedges the cache (work buffered, never drained); an
illegal transition resurrects a dead node or strands a future.

The drain/maintenance path was recently changed ("assist maintenance directly when
the write buffer is full"), so Machine 1 is the priority.

## Machine 1: Drain status (priority)

States: `IDLE`, `REQUIRED`, `PROCESSING_TO_IDLE`, `PROCESSING_TO_REQUIRED`.
Transition sites: `afterWrite`, `scheduleAfterWrite`, `scheduleDrainBuffers`,
`maintenance`, `rescheduleCleanUpIfIncomplete`, `performCleanUp`. Access via
`drainStatusOpaque`/`drainStatusAcquire`, `casDrainStatus`,
`setDrainStatusOpaque`/`setDrainStatusRelease`.

Build the table: for each (state, event) pair — a write arrives, a read arrives,
maintenance starts/ends, the pacer fires, the executor rejects, the buffer-full
inline-assist path runs — what is the next state and who drives it? Then attack:

1. **Lost wakeup**: can the machine settle in `IDLE` while work remains buffered? Trace
   the maintenance-exit CAS (`PROCESSING_TO_IDLE → IDLE`) against a concurrent
   `scheduleAfterWrite` that observed `PROCESSING_TO_IDLE` and CAS'd it to
   `PROCESSING_TO_REQUIRED`. Which write loses, and does the fallback
   (`setDrainStatusOpaque(REQUIRED)`) re-arm it?
2. **Double schedule**: can two threads both schedule maintenance for the same epoch,
   or the inline-assist path run concurrently with an executor-scheduled drain?
3. **Opaque vs CAS staleness**: reads are opaque, transitions are CAS/release. For every
   decision that gates *scheduling*, can the opaque read be stale in a way that drops a
   reschedule? Verify the `PROCESSING_TO_IDLE → PROCESSING_TO_REQUIRED` CAS and the
   maintenance-exit re-check close the window on all paths.
4. **Pacer coupling**: `rescheduleCleanUpIfIncomplete` gates on `REQUIRED &&
   !pacer.isScheduled()`. Can `REQUIRED` coexist with no scheduled pacer and no in-flight
   maintenance — i.e. the cache wedged until the next user operation happens to drive it?

## Machine 2: Node lifecycle

States: alive (has value) → retired (marked) → dead (unlinked). Strictly
unidirectional. Sites: `makeDead`, the retire paths, `isAlive`/`isRetired`/`isDead`
(on the generated `Node`), and the `resurrect` path in `remap`/compute.

1. Can any path move `dead → retired`, `dead → alive`, or `retired → alive` *except*
   the sanctioned resurrection (which re-creates within the same `synchronized(node)`)?
   Resurrection that observes a node already made dead is the bug to hunt.
2. On every exception or early-return in the compute and eviction paths, does the node
   land in a legal terminal state — never stuck `retired` with no one left to finish
   `makeDead`?
3. Is weight / region accounting applied exactly once per transition — not twice on a
   retried path, not zero on an exception path?

## Machine 3: Async-value lifecycle

An async entry's value is an incomplete future → completes (value | null | exception).
Sites: `isComputingAsync`, `ASYNC_EXPIRY`, `refreshes()`, the refresh bit in
`writeTime` (`& 1L`).

1. Can an entry be treated as both computing-async and expired/evicted in a way that
   strands the future or the `ASYNC_EXPIRY` timestamp? (Historical: timestamp stuck
   after executor rejection.)
2. The refresh-in-progress bit in `writeTime` and the `refreshes()` map: can they
   disagree — bit set but map entry gone, or vice versa — so a refresh is double-started
   or never cleared?

## Output

For each finding: the interleaving (thread-by-thread), the illegal or missed
transition, the observable consequence (wedged cache, lost notification, stranded
future, resurrected dead node), and a Verification. Verify each interleaving is
JMM-legal, not merely sequentially consistent. If a transition cannot be resolved
statically, ESCALATE with a Fray skeleton — the drain machine is a prime Fray target.
