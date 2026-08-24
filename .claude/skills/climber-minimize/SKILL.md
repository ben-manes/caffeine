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
terrain it acted on (the guard rail's veto was 11:1 in 2026-08-18 and 1.86:1 on that same cell
set in 2026-08-23), and a step can become **inert** without anyone noticing, because nothing
fails when a branch stops mattering.

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
most of the rest were wired 2026-08-18, `noreturncover` and `nowidecover` in 2026-08.

| arm | the step it removes |
|---|---|
| `cornerprobe` | *restores* the upper-corner probe deleted 2026-08-21, so its sign reads inverted |
| `nostarve` | any blind corner arms a starvation probe |
| `noladder` | a completed experiment deepens its rung |
| `noscale` | deep rungs walk 2x/4x the flat stride |
| `nocommit` | deep rungs commit the walk past the stray zone |
| `norepeat` | a confirm that re-finds lost ground escalates instead of rewarding |
| `nowedge` | a confirm the density arm reverses escalates instead of rewarding |
| `nofollow` | a park's first audit follows the walk that confirmed it |
| `noshield` | a fresh park is shielded from crash-scale weather |
| `noveto` | the guard rail returns the window to the anchor |
| `noretest` | a veto's return re-tests the claim that sent it, on arrival |
| `noreturncover` | a veto's return's landing and settle samples wait for that retest instead of standing the anchor down |
| `nowidecover` | a retreat's cover runs without a held park (the 2026-08-21 widening) |
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
- **PRICED** — a trade. Report the ratio and the worst row. Most steps land here, and "priced;
  it stays" is a complete answer. A ratio under 1:1 is a candidate, not a verdict: the guard
  rail's veto reads 0.3:1 on the battery and buys 6.4pp on a planted cell.
- **NEGATIVE** — removing the step helps everywhere. That is a defect report, not a simplification.
  Hand it to `/audit-adaptivity`.
- **INERT** — bit-identical on every cell. This is the interesting one, and it is **not** a
  licence to delete.

**Where the battery is blind: the start.** Every cell in it starts the cache where the product
does, at 1%. A step that defends a window the machine has been driven *away from* therefore has
almost nothing to act on, and its ratio collapses without the mechanism changing. Before treating
a sub-1:1 ratio as a candidate, re-run the step's own cells with the window planted
(`CAF_EXTRA=-Dcaffeine.climber.startwin=0.55`, or `climber-gate/startwin.py` for the sweep). The
guard rail's veto is the worked case: 0.3:1 on the battery, +6.4pp on `mainsat` planted at 55%.

**Why inert is not dead.** The corpus is a sample of workloads that were interesting enough for
someone to capture, plus constructions aimed at defects already imagined. A step that changes
nothing on that sample may still be the only thing standing between a real deployment and a
5pp hole, and nobody would ever file that bug, because a user cannot see a hit rate they did not
get. So an inert arm earns a second pass, not a patch:

1. **Count the firings, then prove the state unreachable.** `ablate.py` does the counting for
   you: it runs ship once per cell with `-Dcaffeine.climber.counts`, which dumps
   `STEPFIRE <step>=<n>` at exit, and folds the totals into the verdict. A branch that executes
   and changes no outcome is inert, which is weak; a branch that never executes on a *narrow* set
   is unexercised, which is weaker still, and the tool says so. `norepeat` never fires on
   `demoflood` and is worth 18.61 on `absolve_p8`, which is why the DEAD verdict is gated on the
   `full` preset and everything short of it prints PROVISIONAL.

   A zero count on the full set is still a fact about a **sample**, though, and a delete needs a
   fact about the **machine**. Close that gap with an argument from the gates themselves: read the
   conditions that guard the site and show they cannot hold together, or probe the machine's own
   readings for the state rather than the cells for the outcome. The worked example is external —
   the WaveCounter port's ADR-0055 rejected an input by probing 15,693 governor readings for the
   target state, finding it zero times, **and then** showing its two gates mutually exclusive by
   construction. The probe alone would have licensed the same delete on much thinner evidence.
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

## The 2026-08-23 baseline (`f3fad1bdb`, `full` preset, seeds 1 and 2)

91 cells, the whole gate battery plus the whole real corpus, at two seeds against all fourteen
arms, with the firing counts from a ship run of each cell. `buys` is what the step is worth where
it acts; `spends` is what it costs elsewhere.

| step | fires | buys | spends | ratio |
|---|---|---|---|---|
| the return's retest | 13 | 10.00 | 0.30 | **33:1** |
| a repeat confirm escalates | 16 | 39.31 | 1.76 | **22:1** |
| a park's first audit follows its walk | 26 | 60.71 | 3.00 | **20:1** |
| the frozen probation baseline | 104 | 191.21 | 11.39 | **17:1** |
| the audit layer | — | 921.93 | 68.37 | **13.5:1** |
| the return and retreat cover | — | 44.12 | 4.73 | **9.3:1** |
| a reversed confirm escalates | 43 | 71.69 | 9.41 | **7.6:1** |
| deep rungs commit the walk | 86 | 12.48 | 2.34 | **5.3:1** |
| a blind corner arms a probe | 551 | 300.37 | 61.07 | **4.9:1** |
| the fresh-park shield | 103 | 60.77 | 14.71 | **4.1:1** |
| the refractory ladder | 192 | 193.82 | 49.35 | **3.9:1** |
| deep rungs stride wider | 678 | 76.05 | 20.56 | **3.7:1** |
| the guard rail's veto | 14 | 0.94 | 3.36 | **0.3:1** |
| the deleted upper corner's probe | 0 | 4.32 | 9.61 | **0.4:1**, restored |

**Nothing is dead, and nothing is inert.** `corner` reads 0 because that step no longer exists in
the tree; every other site fires and every arm moves at least one cell, so there are no free
deletions to argue about.

**Both 2026-08-18 candidates are closed, in opposite directions.** Restoring the upper corner's
probe now costs 2.2 for every 1 it returns, and 27:1 against on the 2026-08-18 cell set itself,
so the 2026-08-21 deletion holds on the evidence that flagged it. The walk's commitment depth,
0.23:1 at N=8 then and the other candidate for deletion, reads 5.3:1 here.

**The guard rail's veto is the one price that inverted, and it is the worked example of why the
battery is not the whole answer.** Re-priced on the 2026-08-18 cell set at the same seed it fell
from 11:1 to 1.86:1, and over the full battery it reads 0.3:1. At N=8 across the eight cells
either rail arm moves it reads 0.33:1, with 10.95 of its 12.87pp cost on `sidecliff` alone
(−1.34 to −1.42 on all eight seeds); `cp_w015` splits by basin rather than pricing anything,
+0.26 on five seeds against −0.26 to −0.37 on three. But **every battery cell starts where the
product starts it**, and a rail whose job is to return the window to an anchor has little to
defend from a 1% start. Planted, it is worth **+6.3 to +6.6pp on four of four seeds** on `mainsat`
at a 55% window (32.42–32.68 against `noveto`'s 25.90–26.65) and +0.15 to +0.52 at 70%. It stays,
and the finding is about the instrument: **add a planted cell before running this skill again**,
or the rail reads deletable on a battery that never asks it to work.

**A step's price can also be split.** `noreturncover` removes the whole of `isReturnTest`, which
is two things: the held-park retreat cover that predates 2026-08-21 and the widening that let it
run without a held park. `nowidecover` scopes the cover back to a held park with the return half
kept, and it is bit-identical on 66 of 68 rows, costing 0.07 and 0.08 on `cp_w081`. So the 44.12pp
is the older cover and the return half, which is why the moat and `hazefloor` rows move under
`noreturncover` while the commit that landed the return half read them bit-identical. When an arm
removes more than one thing, split it before quoting its ratio as one step's price.

## Reporting

A per-arm table with the four statistics and a verdict, the cells each arm moved, and — for
anything inert — the firing count and which of the four passes above was applied. Record a
mechanism's price in `hill-climber.md` §5 when it is priced and kept, so the next run can see
whether it has gone stale.
