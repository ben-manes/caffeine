---
name: climber-minimize
description: Price each algorithmic step of the window climber by removing it, to find steps that no longer earn their keep and branches that no longer fire
argument-hint: "[quick|standard|full|<cells>]"
context: fork
allowed-tools: Read, Grep, Glob, Bash, Write
---

# Climber minimization

The other climber skills ask whether a change is good. This one asks whether a **step should
exist**. It removes one algorithmic step at a time and reports what the machine loses, so a
mechanism that has stopped paying for itself can be found rather than waited for.

It exists because the machine grows by repair. Every round adds a rule that fixes a workload,
and nothing in the process asks the older rules to re-justify themselves. Two things follow, and
both have been observed: a step's recorded price goes **stale** as later repairs change the
terrain it acted on (`nocorner` was priced at `balloonflip` +3.76 in 2026-08-15 and reads +0.00
today), and a step can become **inert** without anyone noticing, because nothing fails when a
branch stops mattering.

**Its usual output is "priced, kept", not a deletion.** Pricing a mechanism is the point; removing
one is the rare case. Run it before a release, or after the machine has taken several repairs in
a row.

## How to run

The arms live in `climber-gate/harness.py`, so a wired worktree is required:

```bash
git worktree add --detach <wt> <commit>
python3 .claude/skills/climber-gate/harness.py apply <wt>
CAF_TREE=<wt> python3 ablate.py <traces-dir> all quick 1 data/quick.csv       # smoke, ~130 runs
CAF_TREE=<wt> python3 ablate.py <traces-dir> all standard 1,2 data/std.csv    # the decision pass
CAF_TREE=<wt> python3 ablate.py <traces-dir> all full 1,2 data/full.csv       # hours
```

`standard` is the preset a keep-or-prune decision needs: the constructed families plus the whole
real corpus. Traces come from `climber-gate/SKILL.md`'s generation block. Arms are rotated inside
each seed, so every arm sees the same machine state and the same admission draws.

## The arms

Each is a single disable at the step's own site. `noaudit` and the tier arms predate this skill;
the rest were wired 2026-08-18.

| arm | the step it removes |
|---|---|
| `nocorner` | the upper corner arms a starvation probe |
| `nostarve` | any blind corner arms a starvation probe |
| `noladder` | a completed experiment deepens its rung |
| `noscale` | deep rungs walk 2x/4x the flat stride |
| `nocommit` | deep rungs commit the walk past the stray zone |
| `norepeat` | a confirm that re-finds lost ground escalates instead of rewarding |
| `nowedge` | a confirm the density arm reverses escalates instead of rewarding |
| `nofollow` | a park's first audit follows the walk that confirmed it |
| `noshield` | a fresh park is shielded from crash-scale weather |
| `noveto` | the guard rail returns the window to the anchor |
| `nofreeze` | an up-probe is judged against probation frozen at the arm |
| `noaudit` | the whole equilibrium-audit layer |

Add an arm whenever a step lands: a rule that arrives without one cannot be re-priced later, which
is how the set decayed to two before 2026-08-18. `harness.py`'s FLAGS block and the table above are
the two places to edit.

## Reading the result, and the trap in it

**Never adjudicate on a battery mean.** The mean is the one summary that hides what makes a
mechanism worth keeping: a step that buys a great deal on a few workloads and costs a little on
many is insurance, and insurance always looks bad on average. `ablate.py` reports the asymmetry
instead — total gain across the cells an arm helps, total cost across the cells it hurts, the
worst single row, and how many cells the arm leaves bit-identical. The audit layer's own case is
the worked example: +268pp across the rows it helps against −13pp across the rows it hurts, about
21:1, on a mean of +4.91 that says much less (`hill-climber.md` §8 item 2).

The verdicts:

- **LOAD-BEARING** — gains nowhere, losses somewhere. Keep, and update the recorded price.
- **PRICED** — a trade. Report the ratio and the worst row. `nocorner`'s original verdict was
  this, and it stays: "Priced; it stays" is a complete answer.
- **NEGATIVE** — removing the step helps everywhere. That is a defect report, not a simplification.
  Hand it to `/audit-adaptivity`.
- **INERT** — bit-identical on every cell. This is the interesting one, and it is **not** a
  licence to delete.

**Why inert is not dead.** The corpus is a sample of workloads that were interesting enough for
someone to capture, plus constructions aimed at defects already imagined. A step that changes
nothing on that sample may still be the only thing standing between a real deployment and a
5pp hole, and nobody would ever file that bug, because a user cannot see a hit rate they did not
get. So an inert arm earns a second pass, not a patch:

1. **Count the firings.** `ablate.py` does this for you: it runs ship once per cell with
   `-Dcaffeine.climber.counts`, which dumps `STEPFIRE <step>=<n>` at exit, and folds the totals
   into the verdict. A branch that never executes **on the full cell set** is dead and can go on
   that evidence alone — a reachability fact, not a measurement. A branch that executes and
   changes no outcome is inert, which is weaker, and a branch that never executes on a *narrow*
   set is neither: it is unexercised, and the tool says so. `norepeat` never fires on `demoflood`
   and is worth 18.61 on `absolve_p8`, which is why the DEAD verdict is gated on the `full`
   preset and everything short of it prints PROVISIONAL.
2. **Name the shape it was for.** `hill-climber.md` §3 lists every family and what defeats it. A
   step whose family is still in the list and still passes is doing its job on a cell that the
   preset skipped; widen the cells before concluding anything.
3. **Check the graveyard.** §5 records what a step replaced. A step that is inert because a later
   rule subsumed it is a genuine prune; a step that is inert because its trap was retired is a
   prune plus a note that the trap should come back.
4. **Spend a holdout on the prune, not on the decision.** The battery is what the decision was
   made on, so it cannot also verify it. `climber-gate/SKILL.md` records which holdouts are
   unspent.

**One more asymmetry worth stating.** A wrong keep costs complexity, which is visible and
recoverable. A wrong prune costs hit rate on a workload nobody is measuring, which is neither. The
bar for removing a step should be higher than the bar for keeping one, and this skill is built to
be run often and to delete rarely.

## The 2026-08-18 baseline (`e1f23f4d8`, `standard` preset, seed 1)

31 cells — the constructed families plus the whole real corpus — against all twelve arms, with
the firing counts from a ship run of each cell. Kept so the next run can see what has gone stale.
`buys` is what the step is worth where it acts; `costs` is what it spends elsewhere.

| step | fires | buys | costs | ratio |
|---|---|---|---|---|
| the frozen probation baseline | 38 | 36.51 | 0.83 | **44:1** |
| the audit layer | — | 206.12 | 8.15 | **25:1** |
| the refractory ladder | 119 | 42.61 | 2.03 | **21:1** |
| the fresh-park shield | 48 | 22.65 | 1.37 | **17:1** |
| a park's first audit follows its walk | 14 | 21.17 | 1.33 | **16:1** |
| deep rungs stride wider | 430 | 15.53 | 1.11 | **14:1** |
| the guard rail's veto | 17 | 7.53 | 0.66 | **11:1** |
| a blind corner arms a probe | 968 | 101.60 | 18.63 | **5.5:1** |
| a repeat confirm escalates | 6 | 18.61 | 0.00 | — |
| a reversed confirm escalates | 16 | 9.09 | 0.00 | — |
| deep rungs commit the walk | 100 | 0.52 | 0.48 | **1.1:1** |
| the upper corner arms a probe | 567 | 0.64 | 3.08 | **0.2:1** |

**Nothing is dead** — every step's site fires, so there are no free deletions. Ten of the twelve
are clearly load-bearing. Two are not:

- **The upper corner's probe is the one candidate.** It spends 3.08 across six cells and buys 0.64
  across two, so it costs about five times what it returns. That is the same shape the 2026-08-15
  study recorded ("the probe earns a little where it runs on real traces and pays 0.5–3.8pp on
  constructed cliff and phase terrain — priced; it stays"), but the constructed half of its price
  has moved: `balloonflip` was +3.76 then and is +0.00 now. Its keep rests on `cp_w015` −0.35, one
  corpus cell.
- **The walk's commitment depth reads 1.1:1** — 0.52 bought against 0.48 spent, inside the noise
  of a single seed.

**Seeded at N=8 (2026-08-18, `data/candidates8.csv`, arms rotated inside each seed, over the ten
cells either arm moves), both get worse, not better:**

| step | buys | costs | ratio | its value | its cost |
|---|---|---|---|---|---|
| the upper corner's probe | 0.25 | 2.81 | **0.09:1** | `cp_w050` 0.12, `cp_w015` 0.08, `arc_ConCat` 0.05 | `norank_rep_r6` 2.13, `phases_d050` 0.42 |
| the walk's commitment depth | 0.71 | 3.13 | **0.23:1** | `bandtrap2` 0.54, `cp_w050` 0.16 | `phases_d050` 2.76 |

So each costs four to eleven times what it returns, and the corner probe's whole remaining value is
0.25pp spread over three corpus cells. Note that `phases_d050` dominates both costs and is a row
§8 item 2 flags as unreadable from an unseeded mean; these are seeded and rotated, which is the
instrument that entry prescribes, but a repair that changed only `phases_d050` would flip both
verdicts and should be suspected first.

**Neither is removed on this evidence.** The remaining step is the refutation pass: point
`/audit-regret`'s search at ship versus the ablated arm rather than at the machine versus its
ceiling. If a directed search cannot find a workload the step defends, that is far stronger than
"the battery happened not to contain one"; if it finds one, the result is a new gate row instead of
a deletion.

Two findings the run produced on its own: `demoflood` no longer demonstrates the frozen probation
baseline it is named for (`nofreeze` is bit-identical there; the evidence moved to
`norank_rep_r6` at −15.77), and three arms that read INERT on the `quick` preset buy 20.94, 18.61
and 2.40 on their home cells, which is cell selection rather than dead branches.

## Reporting

A per-arm table with the four statistics and a verdict, the cells each arm moved, and — for
anything inert — the firing count and which of the four passes above was applied. Record a
mechanism's price in `hill-climber.md` §5 when it is priced and kept, so the next run can see
whether it has gone stale.
