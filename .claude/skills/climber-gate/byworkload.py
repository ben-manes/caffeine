#!/usr/bin/env python3
"""The 4096 -> 4097 cliff, grouped by WORKLOAD rather than pooled over cells.

Epoch slices of one trace are not independent evidence: six metaStorage epochs agreeing is
replication within one workload, not six workloads agreeing. Pooling them inflates the apparent
base of the reachability claim, so the count that matters is workloads-with-a-cliff over
workloads-measured, with the per-workload spread visible.

    byworkload.py scan.csv [scan2.csv ...] [--bar 1.0]

Input is the long-format CSV a threshold-scan runner writes, one row per (label, size, arm),
with parallel per-seed `runs` and `seeds` fields and `_anchor` rows carrying an `lru` column.
Legacy positional rows are rejected because a missing run would shift the comparison. This is
not gate.py's output, which has neither the 4096/4097 sizes nor the anchor rows.
"""
import argparse
import csv
import re
import statistics
from collections import defaultdict

from pair import parse_seeded_runs


REQUIRED_COLUMNS = {"label", "size", "arm", "n", "runs", "seeds", "lru"}


def workload(label):
    """Strip an epoch suffix: mstore_e3 -> mstore, w56_e0 -> w56, c2k_web07 -> c2k_web07."""
    return re.sub(r"_e\d+$", "", label)


def read_inputs(paths):
    """Read seeded 4096/4097 runs and anchors, rejecting ambiguous rows."""
    runs = defaultdict(dict)
    anchors = defaultdict(dict)
    for path in paths:
        with open(path, newline="", encoding="utf-8") as csv_file:
            reader = csv.DictReader(csv_file)
            missing = REQUIRED_COLUMNS - set(reader.fieldnames or ())
            if missing:
                raise ValueError(
                    f"{path}: missing required CSV columns: {', '.join(sorted(missing))}")
            for row in reader:
                label = row["label"]
                size = int(row["size"])
                if row["arm"] == "hybrid":
                    if not row["runs"]:
                        raise ValueError(f"{label}@{size}: hybrid row has no run values")
                    if size in runs[label]:
                        raise ValueError(f"{label}@{size}: duplicate hybrid row")
                    runs[label][size] = parse_seeded_runs(row)
                elif row["arm"] == "_anchor" and row["lru"]:
                    if size in anchors[label]:
                        raise ValueError(f"{label}@{size}: duplicate anchor row")
                    anchors[label][size] = float(row["lru"])
    return runs, anchors


def cliff_deltas(runs):
    """Return each complete cell's 4097-minus-4096 deltas keyed by seed."""
    cells = {}
    for label, sizes in runs.items():
        has_before = 4096 in sizes
        has_after = 4097 in sizes
        if has_before != has_after:
            missing = 4097 if has_before else 4096
            raise ValueError(f"{label}: missing hybrid row at size {missing}")
        if not has_before:
            continue
        before, after = sizes[4096], sizes[4097]
        before_seeds = set(before)
        after_seeds = set(after)
        if before_seeds != after_seeds:
            only_before = ",".join(map(str, sorted(before_seeds - after_seeds))) or "-"
            only_after = ",".join(map(str, sorted(after_seeds - before_seeds))) or "-"
            raise ValueError(
                f"{label}: 4096/4097 seed sets differ "
                f"(4096-only={only_before}; 4097-only={only_after})")
        cells[label] = [after[seed] - before[seed] for seed in sorted(before_seeds)]
    if not cells:
        raise ValueError("no cells contain both 4096 and 4097 seeded runs")
    return cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csvs", nargs="+")
    ap.add_argument("--bar", type=float, default=1.0, help="pp a cliff must reach to count")
    args = ap.parse_args()

    try:
        runs, anchors = read_inputs(args.csvs)
        cells = cliff_deltas(runs)
    except ValueError as error:
        ap.error(str(error))

    groups = defaultdict(list)
    for label, d in cells.items():
        groups[workload(label)].append((label, d))

    print(f"{'workload':<12}{'cells':>6}{'LRU@4096':>9}{'mean':>8}{'min':>8}{'max':>8}"
          f"{'>=bar':>8}   per-cell")
    hit = 0
    for w, items in sorted(groups.items()):
        means = [statistics.mean(d) for _, d in items]
        over = sum(1 for m in means if m >= args.bar)
        hit += (over > 0)
        lrus = [anchors[l][4096] for l, _ in items if 4096 in anchors.get(l, {})]
        lru = f"{statistics.mean(lrus):.1f}" if lrus else "-"
        print(f"{w:<12}{len(items):>6}{lru:>9}{statistics.mean(means):>+8.2f}{min(means):>+8.2f}"
              f"{max(means):>+8.2f}{f'{over}/{len(items)}':>8}   "
              + " ".join(f"{m:+.2f}" for m in sorted(means)))
    print(f"\nworkloads with a >= {args.bar}pp cliff on at least one cell: "
          f"{hit}/{len(groups)}   (cells: "
          f"{sum(1 for d in cells.values() if statistics.mean(d) >= args.bar)}/{len(cells)})")
    print("LRU@4096 is the workload's mean over its cells — a few-percent workload can still show "
          "a 1pp cliff,\nbut not a large one, so read the bar against it.")


if __name__ == "__main__":
    main()
