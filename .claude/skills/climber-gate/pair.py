#!/usr/bin/env python3
"""Paired-by-seed deltas between two arms of a sweep.csv.

A seeded sweep is not a repeated measurement of one basin. Each arm is independently reproducible,
so two arms are compared seed by seed and the spread of an arm's own column says nothing about
whether the difference is real. Equal seeds do not align draws to requests when the arms reach
randomized admission contests on different schedules. This is not request-indexed
common-random-number pairing unless both the draw count and request-index digest match.
`phases_d050` reads a 12.9pp spread across eight seeds with the code held fixed, so retain the
identity and direction of every per-seed difference rather than comparing column spreads.

    pair.py sweep.csv armA armB [--size 4096]

Prints per-cell: mean delta (B - A), the per-seed deltas, and how many seeds moved each way.

Input is the long-format CSV a study's sweep runner writes, one row per (label, size, arm),
with parallel space-separated `seeds` and `runs` fields. Legacy files without seed identities
are rejected because a missing run would shift every subsequent positional pair. This is not
gate.py's per-cell mean output, which carries no per-seed values to pair.
"""
import argparse
import csv
import math
import statistics
from collections import defaultdict


REQUIRED_COLUMNS = {"label", "size", "arm", "n", "runs", "seeds"}
JAVA_LONG_MIN = -(1 << 63)
JAVA_LONG_MAX = (1 << 63) - 1


def parse_seeded_runs(row):
    """Return the row's hit rates keyed by seed, rejecting ambiguous identities."""
    context = f"{row.get('label', '?')}@{row.get('size', '?')} {row.get('arm', '?')}"
    seed_values = row["seeds"].split()
    run_values = row["runs"].split()
    if not seed_values or not run_values:
        raise ValueError(f"{context}: seeds and runs must both be non-empty")
    if len(seed_values) != len(run_values):
        raise ValueError(
            f"{context}: {len(seed_values)} seeds but {len(run_values)} run values")

    try:
        expected = int(row["n"])
    except ValueError as error:
        raise ValueError(f"{context}: invalid n={row['n']!r}") from error
    if expected != len(seed_values):
        raise ValueError(f"{context}: n={expected} but found {len(seed_values)} seeds")

    seeded = {}
    for seed_value, run_value in zip(seed_values, run_values):
        try:
            seed = int(seed_value)
            hit_rate = float(run_value)
        except ValueError as error:
            raise ValueError(
                f"{context}: invalid seed/run pair {seed_value!r}:{run_value!r}") from error
        if seed < JAVA_LONG_MIN or seed > JAVA_LONG_MAX:
            raise ValueError(f"{context}: seed {seed} is outside the signed 64-bit range")
        if seed in seeded:
            raise ValueError(f"{context}: duplicate seed {seed}")
        if not math.isfinite(hit_rate):
            raise ValueError(f"{context}: non-finite hit rate for seed {seed}")
        seeded[seed] = hit_rate
    return seeded


def read_pairs(path, arm_a, arm_b, size_filter=None):
    """Read complete arm pairs from a long-format seeded sweep."""
    runs = defaultdict(dict)
    with open(path, newline="", encoding="utf-8") as csv_file:
        reader = csv.DictReader(csv_file)
        missing = REQUIRED_COLUMNS - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"missing required CSV columns: {', '.join(sorted(missing))}")
        for row in reader:
            if size_filter and row["size"] != str(size_filter):
                continue
            if row["arm"] not in (arm_a, arm_b):
                continue
            cell = (row["label"], row["size"])
            if row["arm"] in runs[cell]:
                raise ValueError(f"{row['label']}@{row['size']}: duplicate arm {row['arm']}")
            runs[cell][row["arm"]] = parse_seeded_runs(row)

    pairs = []
    for (label, size), arms in sorted(runs.items()):
        missing = {arm_a, arm_b} - set(arms)
        if missing:
            raise ValueError(f"{label}@{size}: missing arm {', '.join(sorted(missing))}")
        seeds_a = set(arms[arm_a])
        seeds_b = set(arms[arm_b])
        if seeds_a != seeds_b:
            only_a = ",".join(map(str, sorted(seeds_a - seeds_b))) or "-"
            only_b = ",".join(map(str, sorted(seeds_b - seeds_a))) or "-"
            raise ValueError(
                f"{label}@{size}: seed sets differ ({arm_a}-only={only_a}; "
                f"{arm_b}-only={only_b})")
        deltas = [(seed, arms[arm_b][seed] - arms[arm_a][seed]) for seed in sorted(seeds_a)]
        pairs.append((label, size, deltas))
    if not pairs:
        raise ValueError("no matching cells")
    return pairs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--size", default=None)
    args = ap.parse_args()

    try:
        pairs = read_pairs(args.csv, args.a, args.b, args.size)
    except ValueError as error:
        ap.error(str(error))

    print(f"{'cell':<24} {'size':>6} {args.b + '-' + args.a:>10} {'win/tie/loss':>13}  per-seed")
    for label, size, seeded_deltas in pairs:
        d = [delta for _, delta in seeded_deltas]
        n = len(d)
        w = sum(1 for x in d if x > 0.05)
        l = sum(1 for x in d if x < -0.05)
        print(f"{label:<24} {size:>6} {statistics.mean(d):>+10.2f} "
              f"{f'{w}/{n - w - l}/{l}':>13}  "
              + " ".join(f"s{seed}={delta:+.2f}" for seed, delta in seeded_deltas))


if __name__ == "__main__":
    main()
