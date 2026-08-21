#!/usr/bin/env python3
"""Run a resumable seeded sweep with explicit seed identity.

This is the long-format producer consumed by pair.py and byworkload.py. Each row carries parallel
`seeds` and `runs` fields; a failed child aborts the cell before any of its arm rows are written.

    sweep.py cells.json out.csv --arms hybrid,reactive --seeds 1,2,3 [--anchors]

cells.json: [{"label": ..., "trace": ..., "size": 4096, "fmt": "lirs"}, ...]
"""
import argparse
import csv
import json
import os
import statistics

import run as R
from pair import parse_seeded_runs


FIELDS = [
    "label", "size", "arm", "n", "mean", "spread", "lru", "ceil", "ceilwin", "runs",
    "seeds",
]


def load_done(path, expected_seeds):
    """Return completed rows after verifying the output's schema and seed identity."""
    done = set()
    if not os.path.exists(path):
        return done
    with open(path, newline="", encoding="utf-8") as csv_file:
        reader = csv.DictReader(csv_file)
        if reader.fieldnames != FIELDS:
            raise ValueError(f"{path}: expected CSV header {','.join(FIELDS)}")
        for row in reader:
            key = (row["label"], row["size"], row["arm"])
            if key in done:
                raise ValueError(f"{row['label']}@{row['size']}: duplicate arm {row['arm']}")
            if row["arm"] != "_anchor":
                seeded = parse_seeded_runs(row)
                if set(seeded) != set(expected_seeds):
                    raise ValueError(
                        f"{row['label']}@{row['size']} {row['arm']}: existing seed set "
                        "does not match --seeds")
            done.add(key)
    return done


def measure(trace, size, fmt, arms, seeds):
    """Measure every arm at every seed, interleaved by seed, or fail the whole cell."""
    results = {arm: [] for arm in arms}
    for seed in seeds:
        for arm in arms:
            hit_rate, _ = R.variant(trace, size, arm, fmt, seed=seed)
            if hit_rate is None:
                raise RuntimeError(f"missing hit rate for arm={arm} seed={seed}")
            results[arm].append((seed, hit_rate))
    return results


def validate_cells(cells):
    """Reject duplicate output identities before launching any simulator process."""
    identities = set()
    for cell in cells:
        identity = (str(cell["label"]), str(cell["size"]))
        if identity in identities:
            raise ValueError(f"duplicate cell {identity[0]}@{identity[1]}")
        identities.add(identity)


def write_arm(writer, label, size, arm, values, lru, ceiling, ceiling_window):
    """Write one complete seeded arm row."""
    hit_rates = [hit_rate for _, hit_rate in values]
    writer.writerow([
        label, size, arm, len(values), round(statistics.mean(hit_rates), 2),
        round(max(hit_rates) - min(hit_rates), 2), lru, ceiling, ceiling_window,
        " ".join(f"{hit_rate:.2f}" for hit_rate in hit_rates),
        " ".join(str(seed) for seed, _ in values),
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cells")
    parser.add_argument("out")
    parser.add_argument("--arms", default="hybrid")
    parser.add_argument("--anchors", action="store_true")
    parser.add_argument("--seeds", required=True, help="comma-separated signed 64-bit seed IDs")
    args = parser.parse_args()

    try:
        arms = R.parse_variants(args.arms)
        seeds = R.parse_seeds(args.seeds)
        if not arms:
            raise ValueError("--arms must contain at least one arm")
        done = load_done(args.out, seeds)
    except ValueError as error:
        parser.error(str(error))

    with open(args.cells, encoding="utf-8") as cells_file:
        cells = json.load(cells_file)
    try:
        validate_cells(cells)
    except ValueError as error:
        parser.error(str(error))
    fresh = not os.path.exists(args.out)
    with open(args.out, "a", newline="", encoding="utf-8") as csv_file:
        writer = csv.writer(csv_file)
        if fresh:
            writer.writerow(FIELDS)
            csv_file.flush()
        for cell in cells:
            label = cell["label"]
            size = cell["size"]
            fmt = cell.get("fmt", "lirs")
            trace = cell["trace"]
            if not os.path.exists(trace):
                print(f"SKIP {label}: missing {trace}", flush=True)
                continue

            pending = [arm for arm in arms if (label, str(size), arm) not in done]
            need_anchor = args.anchors and (label, str(size), "_anchor") not in done
            if not pending and not need_anchor:
                continue

            lru = ceiling = ceiling_window = ""
            if args.anchors:
                lru, (ceiling, ceiling_window) = R.anchors(trace, size, fmt)
                lru = round(lru, 2) if lru is not None else ""
                ceiling = round(ceiling, 2) if ceiling is not None else ""

            results = measure(trace, size, fmt, pending, seeds) if pending else {}
            if need_anchor:
                writer.writerow([
                    label, size, "_anchor", 0, "", "", lru, ceiling, ceiling_window, "", "",
                ])
                print(f"{label}@{size} LRU={lru} ceiling={ceiling}@{ceiling_window}%", flush=True)
            for arm in pending:
                write_arm(writer, label, size, arm, results[arm], lru, ceiling, ceiling_window)
                hit_rates = [hit_rate for _, hit_rate in results[arm]]
                mean = statistics.mean(hit_rates)
                spread = max(hit_rates) - min(hit_rates)
                print(f"{label}@{size} {arm:9s} {mean:6.2f} +/-{spread:.2f}  "
                      f"(n={len(hit_rates)})", flush=True)
            csv_file.flush()


if __name__ == "__main__":
    main()
