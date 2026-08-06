#!/usr/bin/env python3
"""Run one (trace, size) cell through the simulator and report HR + trajectory.

The tree defaults to this repository; point CAF_TREE at an experiment worktree to
measure a candidate. `--variants`/`--dump` are meaningful only when that tree carries
the skill's harness (`harness.py apply <worktree>`; a stock build ignores the properties
and emits no trajectory); against a stock tree just omit them.

Pass an EMPTY --variants to run the anchors alone. That mode is load-bearing: hill-climber.md
§6 selects a holdout by LRU-only characterization, and a cell is disqualified once any
product.Caffeine process has touched it. Before 2026-08-02 the variant list was never allowed to
be empty, so --anchors always fired one product run and silently burned the cell it was
characterizing.

Usage:
  run.py <trace> --size N [--variants hybrid,noaudit,prefix] [--runs 1 | --seeds 1,2,3]
         [--fmt lirs] [--anchors] [--dump PREFIX]
  run.py <trace> --size N --anchors --variants ''        # characterize without burning the cell
"""
import argparse, os, re, statistics, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WT = os.environ.get("CAF_TREE", os.path.abspath(f"{HERE}/../../.."))
WINDOWS = [0.01, 0.02, 0.05, 0.10, 0.20, 0.30, 0.40, 0.50, 0.70, 0.80]


def gradle(size, trace, args, fmt="lirs"):
    base = [f"{WT}/gradlew", "simulator:run", "-q",
            f"-Dcaffeine.simulator.maximum-size={size}",
            f"-Dcaffeine.simulator.files.paths.0={fmt}:{os.path.abspath(trace)}",
            "-Dcaffeine.simulator.admission.0=Always",
            "-Dcaffeine.simulator.report.format=csv"]
    return subprocess.run(base + args, capture_output=True, text=True, cwd=WT)


def anchors(trace, size, fmt="lirs"):
    args = ["-Dcaffeine.simulator.policies.0=linked.Lru",
            "-Dcaffeine.simulator.policies.1=sketch.WindowTinyLfu"]
    for i, w in enumerate(WINDOWS):
        args.append(f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.2f}")
    r = gradle(size, trace, args, fmt)
    lru, best = None, (-1.0, None)
    for ln in r.stdout.splitlines():
        if ln.startswith("linked.Lru,"):
            lru = float(ln.split(",")[1])
        m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),", ln)
        if m and float(m.group(2)) > best[0]:
            best = (float(m.group(2)), int(m.group(1)))
    if lru is None:
        sys.stderr.write(r.stdout[-3000:] + r.stderr[-3000:])
    return lru, best


def variant(trace, size, var, fmt="lirs", debug=False, dump=None, seed=None):
    args = ["-Dcaffeine.simulator.policies.0=product.Caffeine",
            f"-Dcaffeine.climber.variant={var}"]
    if debug:
        args.append("-Dcaffeine.climber.debug=true")
    if seed is not None:
        args.append(f"-Dcaffeine.climber.seed={seed}")
    r = gradle(size, trace, args, fmt)
    hr = None
    for ln in r.stdout.splitlines():
        if ln.startswith("product.Caffeine,"):
            hr = float(ln.split(",")[1])
    lines = [ln for ln in r.stderr.splitlines() if ln.startswith("climb ")]
    if dump and lines:
        with open(dump, "w") as f:
            f.write("\n".join(lines) + "\n")
    if hr is None:
        sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
    return hr, lines


def summarize(lines, size):
    if not lines:
        return "no trajectory (stock build or debug off)"
    wins, modes = [], {}
    for ln in lines:
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        wins.append(int(d["win"]) / size)
        modes[d["mode"]] = modes.get(d["mode"], 0) + 1
    audits = sum(v for k, v in modes.items() if k.startswith("AUDIT"))
    conf = modes.get("AUDITCONFIRM", 0)
    arms = sum(v for k, v in modes.items() if k.startswith("ARM"))
    return (f"n={len(wins)} start={wins[0]:.3f} min={min(wins):.3f} max={max(wins):.3f} "
            f"end={wins[-1]:.3f} mean={statistics.mean(wins):.3f} "
            f"@floor={sum(1 for w in wins if w <= 0.024) / len(wins):.0%} "
            f"audits={audits - conf} confirms={conf} probes={arms}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--size", type=int, required=True)
    ap.add_argument("--variants", default="hybrid")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--fmt", default="lirs")
    ap.add_argument("--anchors", action="store_true")
    ap.add_argument("--dump", default=None)
    ap.add_argument("--seeds", default=None,
                    help="comma-separated admission seeds; runs one pass per seed instead of "
                         "--runs, so paired arms are bit-reproducible (hill-climber.md §6)")
    args = ap.parse_args()
    seeds = [int(s) for s in args.seeds.split(",")] if args.seeds else None

    name = os.path.basename(args.trace)
    if args.anchors:
        lru, (best, bw) = anchors(args.trace, args.size, args.fmt)
        if (lru is None) or (bw is None):
            # a weighted trace drops linked.Lru by design, so an absent anchor is not always a
            # failure; either way it is reported rather than formatted as a float
            print(f"{name} @ {args.size}:  ANCHORS INCOMPLETE  LRU={lru}  ceiling={bw}")
        else:
            print(f"{name} @ {args.size}:  LRU={lru:.2f}  ceiling={best:.2f} @win {bw}%")
    else:
        print(f"{name} @ {args.size}:")
    for v in [v for v in args.variants.split(",") if v]:
        hrs, last = [], []
        for i, seed in enumerate(seeds if seeds else range(args.runs)):
            hr, lines = variant(args.trace, args.size, v, args.fmt, debug=True,
                                dump=(f"{args.dump}.{v}.traj" if args.dump and i == 0 else None),
                                seed=(seed if seeds else None))
            if hr is not None:
                hrs.append(hr)
                if i == 0:
                    last = lines
        m = statistics.mean(hrs) if hrs else float("nan")
        sp = (max(hrs) - min(hrs)) if len(hrs) > 1 else 0.0
        print(f"  {v:9s} hr={m:6.2f}±{sp:.2f}   {summarize(last, args.size)}")


if __name__ == "__main__":
    main()
