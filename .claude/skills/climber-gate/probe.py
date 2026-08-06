#!/usr/bin/env python3
"""Probe one (trace, size) against the climber variants and the reference anchors.

Runs product.Caffeine once per variant (capturing the window trajectory via
-Dcaffeine.climber.debug), plus one anchor run with linked.Lru + opt.Clairvoyant + a static
WindowTinyLfu percent-main sweep (the ceiling). Prints a verdict table and a compact trajectory
summary per variant (start -> min/max -> end window fraction, plus pin detection: the fraction of
adaptations spent within 1.2x of the floor while the ceiling window is far above it).

Run from the Caffeine repo root (or set CAF_TREE); JAVA_HOME must supply a launcher JDK if the
shell does not. The variant/trajectory columns only light up under the study's working-tree debug
harness; on a stock tree the default variant is the shipped climber and anchors work as-is.

Pass an EMPTY --variants for anchors alone — the LRU-only characterization hill-climber.md
§6 requires when freezing a holdout, since a cell is spent once product.Caffeine has touched it.

Usage: probe.py trace.lirs --size 8192 [--variants reactive,tiered] [--runs 1] [--traj]
       probe.py trace.lirs --size 8192 --variants ''    # characterize without burning the cell
"""
import argparse, os, re, statistics, sys

import run as R

CAF = R.WT
WINDOWS = [0.01, 0.05, 0.10, 0.20, 0.30, 0.40, 0.50, 0.70, 0.85, 0.90]


def gradle(size, trace, args):
    # run.py owns the invocation so that both instruments configure the simulator identically;
    # they had drifted on the mandated admission flag, which silently ran them against different
    # admission policies within one battery
    return R.gradle(size, trace, args)


def fail(what, r):
    """Reports an unparseable run instead of dying on a None format later."""
    sys.stderr.write(f"\n!! {what}: no parseable result; simulator output tail follows\n")
    sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:] + "\n")


def anchor_run(trace, size):
    args = ["-Dcaffeine.simulator.policies.0=linked.Lru",
            "-Dcaffeine.simulator.policies.1=opt.Clairvoyant",
            "-Dcaffeine.simulator.policies.2=sketch.WindowTinyLfu"]
    for i, w in enumerate(WINDOWS):
        args.append(f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.2f}")
    r = gradle(size, trace, args)
    lru = opt = None
    best = (-1.0, None)
    for ln in r.stdout.splitlines():
        if ln.startswith("linked.Lru,"):
            lru = float(ln.split(",")[1])
        elif ln.startswith("opt.Clairvoyant,"):
            opt = float(ln.split(",")[1])
        else:
            m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),", ln)
            if m and float(m.group(2)) > best[0]:
                # the policy label already prints the window percent: 100*(1-percentMain)
                best = (float(m.group(2)), int(m.group(1)))
    if (lru is None) or (best[1] is None):
        fail(f"anchor run {os.path.basename(trace)} @ {size}", r)
    return lru, opt, best


def variant_run(trace, size, variant, dump=None):
    args = [ "-Dcaffeine.simulator.policies.0=product.Caffeine",
             f"-Dcaffeine.climber.variant={variant}", "-Dcaffeine.climber.debug=true"]
    r = gradle(size, trace, args)
    hr = None
    for ln in r.stdout.splitlines():
        if ln.startswith("product.Caffeine,"):
            hr = float(ln.split(",")[1])
    wins = []
    climb_lines = []
    for ln in r.stderr.splitlines():
        m = re.match(r"climb max=(\d+) win=(\d+) hr=([\d.]+)", ln)
        if m and int(m.group(1)) == size:
            wins.append(int(m.group(2)) / size)
            climb_lines.append(ln)
    if dump and climb_lines:
        with open(dump, "w") as f:
            f.write("\n".join(climb_lines) + "\n")
    if hr is None:
        fail(f"{variant} on {os.path.basename(trace)} @ {size}", r)
    return hr, wins


def traj_summary(wins, size, floor=0.02):
    if not wins:
        return "no adaptations"
    pin_band = floor * 1.2
    pinned = sum(1 for w in wins if w <= pin_band) / len(wins)
    top_band = 0.75
    topped = sum(1 for w in wins if w >= top_band) / len(wins)
    return (f"n={len(wins)} start={wins[0]:.3f} min={min(wins):.3f} max={max(wins):.3f} "
            f"end={wins[-1]:.3f} mean={statistics.mean(wins):.3f} "
            f"@floor={pinned:.0%} @top={topped:.0%}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--size", type=int, required=True)
    ap.add_argument("--variants", default="hybrid")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--traj", action="store_true", help="print the full trajectory")
    ap.add_argument("--skip-anchor", action="store_true", help="variant runs only (reuse cached anchors)")
    ap.add_argument("--dump", default=None, help="write raw climb debug lines to this file")
    args = ap.parse_args()

    if args.skip_anchor:
        lru = opt = ceil = float("nan")
        cwin = -1
        print(f"{os.path.basename(args.trace)} @ {args.size}:  (anchors skipped)")
    else:
        lru, opt, (ceil, cwin) = anchor_run(args.trace, args.size)
        if (lru is None) or (cwin is None):
            print(f"{os.path.basename(args.trace)} @ {args.size}:  ANCHORS FAILED "
                  f"(LRU={lru}, ceiling={cwin}) — see the tail above")
            lru, opt, ceil = (float("nan") if v is None else v for v in (lru, opt, ceil))
        else:
            print(f"{os.path.basename(args.trace)} @ {args.size}:  LRU={lru:.2f}  "
                  f"ceiling={ceil:.2f} @win {cwin}%  Belady={opt:.2f}")
    for v in [v for v in args.variants.split(",") if v]:
        hrs, last_wins = [], []
        for _ in range(args.runs):
            hr, wins = variant_run(args.trace, args.size, v, dump=args.dump)
            if hr is not None:
                hrs.append(hr)
                last_wins = wins
        m = statistics.mean(hrs) if hrs else float("nan")
        spread = (max(hrs) - min(hrs)) if len(hrs) > 1 else 0.0
        gap = m - ceil
        flag = "  <<< TRAP?" if gap < -3.0 else ""
        # a dropped run shrinks N silently, which is enough to flip a bimodal cell's verdict
        n = f"  n={len(hrs)}/{args.runs}" + ("  <<< RUNS DROPPED" if len(hrs) < args.runs else "")
        print(f"  {v:10s} hr={m:6.2f}±{spread:.2f}  gap→ceil={gap:+6.2f}{flag}{n}")
        print(f"             traj: {traj_summary(last_wins, args.size)}")
        if args.traj and last_wins:
            print("             " + " ".join(f"{w:.2f}" for w in last_wins))


if __name__ == "__main__":
    main()
