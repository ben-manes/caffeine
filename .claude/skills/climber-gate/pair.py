#!/usr/bin/env python3
"""Paired-by-seed deltas between two arms of a sweep.csv.

A seeded sweep is not a repeated measurement of one basin — each seed is its own deterministic
draw, so two arms are compared seed by seed and the spread of an arm's own column says nothing
about whether the difference is real. `phases_d050` reads a 12.9pp spread across eight seeds with
the code held fixed; what matters is the sign of the per-seed difference.

    pair.py sweep.csv armA armB [--size 4096]

Prints per-cell: mean delta (B - A), the per-seed deltas, and how many seeds moved each way.

Input is the long-format CSV a study's sweep runner writes — one row per (label, size, arm)
with `runs` holding the space-separated per-seed hit rates — NOT gate.py's per-cell means,
which carry no per-seed values to pair.
"""
import argparse, csv, statistics
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--size", default=None)
    args = ap.parse_args()

    runs = defaultdict(dict)
    for row in csv.DictReader(open(args.csv)):
        if args.size and row["size"] != str(args.size):
            continue
        if row["arm"] in (args.a, args.b) and row["runs"]:
            runs[(row["label"], row["size"])][row["arm"]] = [float(x) for x in row["runs"].split()]

    print(f"{'cell':<24} {'size':>6} {args.b + '-' + args.a:>10} {'win/tie/loss':>13}  per-seed")
    for (label, size), got in runs.items():
        if args.a not in got or args.b not in got:
            continue
        va, vb = got[args.a], got[args.b]
        n = min(len(va), len(vb))
        d = [vb[i] - va[i] for i in range(n)]
        w = sum(1 for x in d if x > 0.05)
        l = sum(1 for x in d if x < -0.05)
        print(f"{label:<24} {size:>6} {statistics.mean(d):>+10.2f} "
              f"{f'{w}/{n - w - l}/{l}':>13}  " + " ".join(f"{x:+.2f}" for x in d))


if __name__ == "__main__":
    main()
