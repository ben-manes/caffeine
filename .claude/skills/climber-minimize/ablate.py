#!/usr/bin/env python3
"""Price each algorithmic step of the window climber by removing it.

  CAF_TREE=<wired worktree> ablate.py <traces-dir> [arms] [cells] [seeds] [out.csv]

Arms are the mechanism ablations wired into `climber-gate/harness.py`; `all` expands to every
one of them. Cells are gate labels, real-corpus labels, or one of the presets below. Arms are
rotated inside each seed, as the gate does, so every arm sees the same machine state and the
same admission draws.

The output is one row per (cell, seed) with every arm's hit rate, and a summary that reports,
per arm, the statistics a keep-or-prune decision is actually made on: how many cells the arm
leaves bit-identical, the total gain across the cells it helps, the total cost across the cells
it hurts, and the worst single row. It deliberately does not report a mean. A mechanism that
buys a lot on a few cells and costs a little on many is insurance, and a mean is the one summary
that hides exactly that.
"""
import csv, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../climber-gate"))
import run as R
import gate as G
import real as REAL

# Every mechanism ablation harness.py wires. Keep this list in step with its FLAGS block.
ARMS = ["nocorner", "nostarve", "noladder", "noscale", "nocommit", "norepeat",
        "nowedge", "nofollow", "noshield", "noveto", "noretest", "nofreeze", "noaudit"]

# Cell presets. `quick` is a smoke pass over the families most mechanisms touch; `standard`
# adds the rest of the constructed families and the whole real corpus, which is what a
# keep-or-prune decision needs; `full` is the entire battery and takes hours.
QUICK = ["demoflood", "moat_h4000", "whisper", "norank_rep_r6", "mixture_d050",
         "straywall2", "crestpast", "hazefloor", "absolve", "scarburst"]
STANDARD = QUICK + ["moat_h3000", "moat_h5000", "moat_h7800", "whisper_mod_p6",
                    "whisper_mod_a12", "crashnoise_a12", "bandtrap2", "balloonflip",
                    "shieldtrap_s11", "norank_flood_j100", "absolve_p8", "ghostclaim",
                    "mixture_d010_long", "phases_d050", "blindlock_blind", "slowswap_ramp",
                    "regimeramp", "posjam_d0"] + [c[0] for c in REAL.CELLS]


def cell_table(traces):
    table = {}
    for label, name, size, _runs in G.CELLS:
        table[label] = (os.path.join(traces, name), "lirs", size)
    for label, path, fmt, size in REAL.CELLS:
        table[label] = (path, fmt, size)
    return table


def firings(path, size, fmt):
    """Returns {step: count} for one ship run with the firing counters on.

    This is the reachability half of the verdict. A step whose site is never reached cannot
    change an outcome, so an arm that reads inert while its step never fires is DEAD; one whose
    step fires and still changes nothing is INERT, which is a far weaker claim.
    """
    r = R.gradle(size, path, ["-Dcaffeine.simulator.policies.0=product.Caffeine",
                              "-Dcaffeine.climber.variant=hybrid",
                              "-Dcaffeine.climber.counts=true"], fmt)
    for ln in r.stderr.splitlines():
        if ln.startswith("STEPFIRE"):
            return {kv.split("=")[0]: int(kv.split("=")[1]) for kv in ln.split()[1:]}
    return {}


def summarize(rows, arms, fired=None, complete=False):
    """Prints the keep-or-prune statistics per arm. Ship is the baseline column."""
    per_arm = {a: [] for a in arms}
    for r in rows:
        base = r.get("hybrid")
        if base in (None, ""):
            continue
        for a in arms:
            if r.get(a) not in (None, ""):
                per_arm[a].append((r["cell"], float(r[a]) - float(base)))
    cells = {r["cell"] for r in rows}
    corpus = {c[0] for c in REAL.CELLS}
    # a verdict that would license a prune is provisional until the real corpus has been run:
    # every recorded counter-example to a "remove it" reading has come from there
    provisional = "" if (corpus & cells) else " (PROVISIONAL — no real corpus in this run)"
    # "never fired" is only evidence of death over a cell set wide enough to exercise the step.
    # On a narrow set it means unexercised, and saying otherwise invites deleting a step that a
    # cell one preset over depends on: `norepeat` never fires on demoflood and is worth 18.61 on
    # absolve_p8. Even on the full set the count is a fact about the sample, so DEAD names the
    # remaining work rather than licensing the delete: the gates have to be shown unsatisfiable.
    if not complete:
        provisional = provisional or " (PROVISIONAL — partial cell set)"
    width = max(len(a) for a in arms)
    print(f"\n{'arm':<{width}}  {'same':>5} {'helped':>7} {'hurt':>6} "
          f"{'+total':>8} {'-total':>8} {'worst':>8}  verdict")
    for a in arms:
        deltas = per_arm[a]
        if not deltas:
            continue
        same = sum(1 for _, d in deltas if abs(d) < 0.005)
        up = [d for _, d in deltas if d >= 0.005]
        down = [(c, d) for c, d in deltas if d <= -0.005]
        gain, cost = sum(up), sum(d for _, d in down)
        worst = min(down, key=lambda x: x[1]) if down else ("", 0.0)
        # the ratio is the mechanism's, not the ablation's: what the step buys per unit it
        # spends, the way the audit layer's own 21:1 is quoted
        step = a[2:]  # the arms are named `no<step>`
        hits = None if (fired is None) else fired.get(step)
        if same == len(deltas):
            if (hits == 0) and complete:
                verdict = "DEAD — its site never fired on any cell; prove it unreachable, then delete"
            elif hits == 0:
                verdict = "UNEXERCISED — its site never fired here" + provisional
            elif hits:
                verdict = f"INERT — fired {hits}x and changed nothing" + provisional
            else:
                verdict = "INERT on these cells" + provisional
        elif not down:
            verdict = "NEGATIVE — removing it only helps" + provisional
        elif not up:
            verdict = "LOAD-BEARING — costs nothing here"
        else:
            verdict = f"PRICED {abs(cost / gain):.1f}:1, worst {worst[0]}"
        print(f"{a:<{width}}  {same:>5} {len(up):>7} {len(down):>6} "
              f"{gain:>+8.2f} {cost:>+8.2f} {worst[1]:>+8.2f}  {verdict}")
    print("\nThe ablation's sign is reversed from the mechanism's: an arm that gains by removing\n"
          "a step means the step costs that cell. Read `+total` as what the step is spending and\n"
          "`-total` as what it is buying.")


def main():
    traces = sys.argv[1]
    arms = (ARMS if (len(sys.argv) < 3 or sys.argv[2] == "all")
            else sys.argv[2].split(","))
    preset = sys.argv[3] if len(sys.argv) > 3 else "quick"
    cells = {"quick": QUICK, "standard": STANDARD,
             "full": [c[0] for c in G.CELLS] + [c[0] for c in REAL.CELLS]}.get(
                 preset, preset.split(","))
    seeds = [int(s) for s in sys.argv[4].split(",")] if len(sys.argv) > 4 else [1, 2]
    out = sys.argv[5] if len(sys.argv) > 5 else "ablate.csv"

    table = cell_table(traces)
    columns = ["hybrid"] + arms
    done = set()
    if os.path.exists(out):
        for r in csv.DictReader(open(out)):
            done.add((r["cell"], int(r["seed"])))
    fh = open(out, "a", newline="")
    w = csv.writer(fh)
    if not done:
        w.writerow(["cell", "size", "seed"] + columns)
        fh.flush()

    for cell in cells:
        if cell not in table:
            print(f"SKIP {cell}: not a known cell", flush=True)
            continue
        path, fmt, size = table[cell]
        if not os.path.exists(path):
            print(f"SKIP {cell}: missing {path}", flush=True)
            continue
        for seed in seeds:
            if (cell, seed) in done:
                continue
            row = [cell, size, seed]
            for arm in columns:
                hr, _ = R.variant(path, size, arm, fmt, seed=seed)
                row.append("" if hr is None else hr)
            w.writerow(row)
            fh.flush()
            print(" ".join(str(x) for x in row), flush=True)
    fh.close()

    # one ship run per cell with the counters on, summed: the reachability half of the verdict
    total = {}
    for cell in cells:
        if cell not in table:
            continue
        path, fmt, size = table[cell]
        if not os.path.exists(path):
            continue
        for step, n in firings(path, size, fmt).items():
            total[step] = total.get(step, 0) + n
    if total:
        print("\nfirings under ship, summed over the cells: "
              + " ".join(f"{k}={v}" for k, v in sorted(total.items())))
    full = set(c[0] for c in G.CELLS) | set(c[0] for c in REAL.CELLS)
    summarize(list(csv.DictReader(open(out))), arms, total,
              complete=full.issubset(set(cells)))


if __name__ == "__main__":
    main()
