#!/usr/bin/env python3
"""The 4096 -> 4097 cliff, grouped by WORKLOAD rather than pooled over cells.

Epoch slices of one trace are not independent evidence: six metaStorage epochs agreeing is
replication within one workload, not six workloads agreeing. Pooling them inflates the apparent
base of the reachability claim, so the count that matters is workloads-with-a-cliff over
workloads-measured, with the per-workload spread visible.

    byworkload.py scan.csv [scan2.csv ...] [--bar 1.0]

Input is the long-format CSV a threshold-scan runner writes — one row per (label, size, arm)
with per-seed hit rates in `runs` and `_anchor` rows carrying an `lru` column — NOT gate.py's
output, which has neither the 4096/4097 sizes nor the anchor rows.
"""
import argparse, csv, re, statistics
from collections import defaultdict


def workload(label):
    """Strip an epoch suffix: mstore_e3 -> mstore, w56_e0 -> w56, c2k_web07 -> c2k_web07."""
    return re.sub(r"_e\d+$", "", label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csvs", nargs="+")
    ap.add_argument("--bar", type=float, default=1.0, help="pp a cliff must reach to count")
    args = ap.parse_args()

    runs = defaultdict(dict)
    anchors = defaultdict(dict)
    for p in args.csvs:
        for r in csv.DictReader(open(p)):
            if r["arm"] == "hybrid" and r["runs"]:
                runs[r["label"]][int(r["size"])] = [float(x) for x in r["runs"].split()]
            elif r["arm"] == "_anchor" and r["lru"]:
                anchors[r["label"]][int(r["size"])] = float(r["lru"])

    cells = {}
    for label, sizes in runs.items():
        if 4096 in sizes and 4097 in sizes:
            a, b = sizes[4096], sizes[4097]
            n = min(len(a), len(b))
            cells[label] = [b[i] - a[i] for i in range(n)]

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
