---
name: audit-jdk-seam
description: Trace each JDK facility the cache relies on into the latest OpenJDK sources, and find where Caffeine's usage meets implementation behaviour the javadoc does not promise
argument-hint: "[JDK class or module to focus on, e.g. ForkJoinPool or jcache; default: all]"
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the seam between Caffeine and the JDK's implementation, read from the latest OpenJDK
sources. The other lenses stop at the API boundary and check a call site against the javadoc.
This one follows the call into the JDK and asks what that code does with Caffeine's particular
usage, on master and on each supported release.

The model case is #2021. `PerformCleanupTask` extended `ForkJoinTask`, and the cache submitted
the same instance on every schedule. `ForkJoinPool` claims a slot with a CAS on the slot's
content and then writes `base` as an absolute value, so a worker stalled between its recheck and
the CAS can match the same object a lap later and move `base` back. The documented reuse idiom,
`reinitialize()` then `fork()`, fails the same way, so it is a JDK bug too. Smaller initial
queues from JDK 17 made it reachable, and JDK 22 made it permanent by throwing on overflow.
Audits that checked the task against the `ForkJoinTask` contract passed it, and an audit of
`ForkJoinPool` alone had no reason to submit one task twice. Look for that shape: a legal usage
pattern, an implementation that does not expect it, and a release that changed the odds.

Focus requested: `$ARGUMENTS`. Empty means the core first, then the jcache and guava adapters.
The simulator and the examples are out of scope.

The steps fill the auditor's phases: Steps 1 to 3 are Phase 1, Step 4 is Phase 1.5 and Step 5 is
Phase 3.5. Phases 0, 2, 3 and 4 run as the agent defines them, except that Phase 0's attack plan
comes after the inventory and before any JDK source is read, naming the rows whose usage an
implementation is least likely to expect.

## Ground rules

These amend the auditor's Evidence Boundaries for this run.

- The OpenJDK checkout this run creates is source under audit. Answer a question about JDK
  internals by reading it; the escalation for "information outside the source tree" does not
  cover anything in the checkout. Cite JDK code as `<path>:<line>` with the tag or `master`.
- The workspace is `.local/experiments/jdk-seam-<YYYY-MM-DD>/`, created by this run. The
  checkout, the inventory, query results, downloaded JDKs and witnesses go there. The report goes
  to the auditor's usual path, `.local/audits/<model>/audit-jdk-seam.md`, and is created first.
- Network access is read-only: the OpenJDK repository, `gh api` for its history, the JBS search,
  and `jdk.java.net` for an EA build. Never file, comment on or email anything upstream. An
  upstream report is a draft in the report, for the maintainer to send.
- The shell keeps no variables between commands, so spell out the workspace path in each, and use
  `git -C <dir>` rather than `cd`. The shell is zsh, where `$tag:src/...` applies the `:s` modifier;
  loop over tags with `git show "${tag}:src/..."`.

## Step 0: Fetch the sources

```bash
.claude/skills/audit-jdk-seam/fetch-openjdk.sh .local/experiments/jdk-seam-<date>
```

The script makes a sparse, blobless clone of master (about 40 MB, under a minute) holding
`java/util/concurrent`, `java/lang/ref` and `java/lang/invoke`. Cone mode also checks out the
files directly inside each parent directory, which brings in `java/lang/*.java` (`Thread`,
`VirtualThread`, `ThreadLocal`, `System`) and `java/util/*.java`. It fetches the tags
`jdk-11-ga`, `jdk-17-ga`, `jdk-21-ga`, `jdk-25-ga` and the newest GA, plus any tag named after
the workspace argument (an EA build's, such as `jdk-28+19`). Record the master commit it prints
in the report header.

Read an older release with `git -C <checkout> show jdk-21-ga:<path>`, and see what moved with
`git -C <checkout> diff jdk-25-ga HEAD -- <path>`. When a seam's behaviour lives elsewhere, add
the path with `git -C <checkout> sparse-checkout add <path>`: `src/java.management/` for
jcache's JMX, or HotSpot (`src/hotspot/share/`) for reference processing, intrinsics and field
layout. Name each added path in the report.

For witnesses, list the local JDKs (`ls ~/.gradle/jdks`, `java -version`) and build the jar once
with `./gradlew :caffeine:jar`, which does not depend on `test`. When a finding's path on master
differs from the newest local GA, download the current EA build from `https://jdk.java.net/<N>/`
into its own directory in the workspace, fetch its `jdk-<N>+<build>` tag, and diff the cited file
between that tag and master.

## Step 1: Inventory the seam

Build the inventory from source and write it to `<workspace>/inventory.md` before analysing any
row. Searches must be exhaustive; a capped grep drops rows without saying so.

- Every JDK type imported or named from `java.util.concurrent` (with `atomic` and `locks`),
  `java.lang.invoke`, `java.lang.ref`, `java.lang.reflect`, `java.lang.management` and
  `javax.management`, plus `Thread`, `ThreadLocal`, `ThreadLocalRandom`, `System.Logger`,
  `ServiceLoader` and the context class loader.
- Every class that extends or implements a JDK type, including `References` and the generated
  nodes (trace a generated class to its `AddX` generator).
- Every `MethodHandles` or reflective lookup, `VarHandle` fence, `Thread.onSpinWait`, and
  `synchronized` block.
- Every default the cache picks for the user (`ForkJoinPool.commonPool()`), every JDK-backed
  option it offers (`Scheduler.systemScheduler()` on `CompletableFuture.delayedExecutor`), and
  every comparison against one.

Each row records the call site, the JDK method the call lands in, and the **usage pattern**: what
Caffeine does that an ordinary caller does not. That column drives the audit, so be specific. Its
shapes include one instance submitted, enqueued or completed more than once; a future completed
by a party that did not create it; a call back into a JDK object from code that object invoked; a
lock held across the call; reliance on which thread runs a callback; reliance on a default such
as pool parallelism or a delayer thread; a subclass overriding hooks; a reference whose clearing
or enqueueing order matters.

Read the project's record of JDK interactions as attack surface, not as conclusions:
`gh search issues --repo ben-manes/caffeine 'JDK-' --limit 1000` lists the issues that mention
the JDK (more than 100, so keep the limit high). The ones a JDK behaviour caused are
seams that have broken once; check whether master changed anything along them.

## Step 2: Read the implementation along Caffeine's path

Order the rows by how unusual the usage pattern is, how bad a failure would be, and how much the
JDK file has changed since `jdk-21-ga` (Step 3's commit list). For each, trace the exact path
Caffeine's call takes through master, not the class in general, and answer each question with
JDK `file:line` evidence or a reason it does not apply.

1. **Identity and reuse.** Does the implementation key anything on object identity: a CAS on a
   slot by reference, an `==` check, an identity-keyed table, state kept in the object itself?
   An instance Caffeine reuses can then alias an earlier use of itself.
2. **Capacity and defaults.** Initial and maximum sizes, what happens at the limit (overwrite,
   throw, block or spin), counters that can wrap, and how those values moved across the tags. A
   default that shrank can make a rare event reachable. Defaults the JDK picks per host count too:
   `CompletableFuture` ran a common pool below parallelism 2 as a thread per task through 24 and
   uses its single worker from 25, so on 1-2 CPUs Caffeine's maintenance, its async loads and a
   loader's own default futures all share one thread.
3. **Who runs Caffeine's code.** Does the JDK call Caffeine back (a task, a dependent stage, a
   remapping function, an exception handler) on the caller, a pool worker, a shared daemon thread
   or a virtual thread's carrier? What does the JDK hold at that moment, and what does re-entry
   from that code do to the JDK object? Helping counts: a pool worker blocked in
   `CompletableFuture.join()` runs queued `AsynchronousCompletionTask`s on its own stack
   (`helpAsyncBlocker`), so a refresh or async load the worker submitted can complete inside a
   user's computation and re-enter the map under its bin lock.
4. **Locks and blocking.** Caffeine code running under a JDK lock (a `ConcurrentHashMap` bin lock
   during a compute, AQS state), virtual-thread pinning across releases, and a
   `StackOverflowError` or `OutOfMemoryError` inside a lock's acquire or release.
5. **Memory effects.** For each happens-before edge Caffeine needs from a JDK object, is it a
   documented memory consistency effect (the `java.util.concurrent` package documentation or the
   class javadoc), or a property of the current implementation's fences?
6. **Failure paths.** Rejection, interrupt, cancellation, shutdown, and a callback that throws:
   what state does the JDK object keep, and does Caffeine's recovery assume more?
7. **Reference processing.** The order in which a reference is cleared, enqueued and observed by
   `get()`, per collector; what `clear()` and `enqueue()` do when Caffeine calls them; whether
   master can refuse a referent, as with identity-free value objects.
8. **Deprecation and restriction.** A method Caffeine calls that master deprecates for removal, a
   reflective or `MethodHandles` access the platform now warns about or restricts, and a system
   property or default whose meaning changed.

Append each row's answers to the report as they are found.

## Step 3: Drift and the JDK's bug record

For each JDK file on a traced path, list the commits since the oldest tag compared. OpenJDK
commit subjects begin with their JBS id:

```bash
gh api --paginate 'repos/openjdk/jdk/commits?path=<file>&since=2023-09-19T00:00:00Z' \
  --jq '.[] | "\(.sha[:10]) \(.commit.committer.date[:10]) \(.commit.message|split("\n")[0])"'
```

Read the issue behind every commit that touches Caffeine's path, backouts included, and diff the
tags to find which releases carry it. Ask in both directions: does master break something the old
releases honoured, and does a release Caffeine still supports lack a fix its code now assumes?

Then sweep the JBS for bugs Caffeine's usage could reach, open ones and those fixed since the
oldest LTS compared, with the paging helper:

```bash
.claude/skills/audit-jdk-seam/jbs.py 'project = JDK AND component = core-libs AND Subcomponent = "java.util.concurrent" AND resolution = Unresolved'
.claude/skills/audit-jdk-seam/jbs.py 'project = JDK AND component = core-libs AND Subcomponent = "java.util.concurrent" AND resolution = Fixed AND resolved >= 2023-09-19'
.claude/skills/audit-jdk-seam/jbs.py 'project = JDK AND resolution = Unresolved AND summary ~ "WeakReference"'
```

`java.util.concurrent` and `java.lang.invoke` are verified subcomponent names; for anything else,
search `summary ~ "<Class>"`. A query that returns nothing may name a field value wrongly, so
confirm with a looser query before treating it as an absence. A fixed bug matters when its
affected versions include a release Caffeine supports and its fix versions do not. Save the raw
output under `<workspace>/jbs/`, and give every bug checked a one-line reachability verdict in the
report so a re-run need not repeat it.

## Step 4: Re-check the recorded JDK premises

After Steps 1 to 3 are written down, read the design context the auditor's Phase 1.5 names, and
grep `.claude/docs/` and `.claude/rules/` for JDK claims: `JDK-[0-9]{7}`, `JDK [0-9]+`,
`ForkJoin`, `CompletableFuture`, `ConcurrentHashMap`, `commonPool`, `virtual thread`. Every
workaround or ruling that rests on JDK behaviour is a claim about the JDK, #2021's record
included. Check each against master and the tags, and look up the current status of every JBS id
it cites. A premise that master changed is a finding even when the ruling's conclusion still
holds.

## Step 5: Witness

A seam finding needs a run on a real JDK, with the build string of every JDK it ran on.

- Start with a public-API reproducer on a stock JDK, a single-file program run as
  `java -cp caffeine/build/libs/caffeine-<version>.jar Repro.java`. When the JDK's own API reaches
  the defect without Caffeine, as `reinitialize()` did for #2021, build that program too; it is
  the upstream report's reproducer.
- When the window is too narrow to hit, widen it inside the JDK rather than in Caffeine. Copy the
  class from the tag matching the binary you will run (master's source only with the EA build),
  add a stall or yield inside the window, then compile and run it:

  ```bash
  javac --patch-module java.base=<src> -d <out> <src>/java/util/concurrent/ForkJoinPool.java
  java --patch-module java.base=<out> -cp <caffeine jar> Repro.java
  ```

  Run a control arm with the same patch and without the usage pattern (a fresh task for each
  submission, for instance), so the stall itself cannot be the cause.
- For a hang, have the reproducer dump its own threads from a watchdog
  (`Thread.getAllStackTraces()`) rather than attaching a tool to the process.
- Compile with `javac` and run with `java -cp` when the claim depends on what the JVM has
  initialized: the source launcher runs javac in-process and can initialize `CompletableFuture`
  and the common pool first. A JDK helping path stops at the first queued task it cannot run, so a
  Caffeine task queued earlier on the same worker (maintenance after a write) hides a helping
  effect; start such a scenario from a quiesced pool.
- Run on the newest local GA and on the oldest release the finding claims, and use the project's
  jcstress setup, not a stress loop, for a weak-memory claim.
- Witness sub-agents may run `java` in parallel. Re-measure any timing taken during a fan-out on
  its own, and let only one of them invoke Gradle at a time.

Add one line to the Phase 3 evaluator's prompt: check that every witness ran on a JDK whose
source is the code the finding cites. Reading master and running an older release is this lens's
characteristic measurement error.

## Classification

Label every finding with one of these, alongside the taxonomy's severity and category
(`external-contract` for a misuse, or `liveness` or `state-corruption` for the harm):

- **reachable JDK bug**: a JDK defect that Caffeine's legal usage triggers. Give the Caffeine-side
  mitigations with what each costs, and an upstream draft: the public-API reproducer, the
  affected releases, the JDK `file:line`, and a fix sketch when one is evident.
- **unreachable JDK bug**: found along the way. One entry; a draft only when it is clear-cut.
- **implementation reliance**: Caffeine depends on behaviour that the javadoc does not promise
  and master still has. Name the change that would break it.
- **drift**: the behaviour differs across supported releases and Caffeine assumes one of them.
- **stale premise**: a recorded workaround or ruling whose JDK premise has changed.

The report adds to the auditor's sections: the OpenJDK commit, tags and added paths read, the JDK
builds run, the inventory, the drift notes per JDK file, the JBS verdicts, and the upstream
drafts.
