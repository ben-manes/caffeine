#!/usr/bin/env python3
"""Descent rate: how far, per density sample, the window falls from a hostile plant.

The hit-rate deficits a plant produces scale with the maximum, because a density sample is
4 x maximum requests while the step the density law commands is a fraction of the maximum. This
measures the rate itself, which is the size-independent quantity: percentage points of the maximum
per sample over the descent, and the sample count a full descent to the 2% floor would need.

Run against a harness worktree (`CAF_TREE`), which supplies both `-Dcaffeine.climber.startwin` and
the per-sample trajectory the rate is read from.

Usage: descent.py <out.csv> [--starts 0.4,0.8] [--seeds 1]
"""
import argparse, csv, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
CELLS = [
    ("s3_25k", f"{T}/arc/S3.lis.xz", "arc", 25000),
    ("s3_100k", f"{T}/arc/S3.lis.xz", "arc", 100000),
    ("s3_400k", f"{T}/arc/S3.lis.xz", "arc", 400000),
    ("ds1_1M", f"{T}/arc/DS1.lis.xz", "arc", 1051635),
    ("ds1_2M", f"{T}/arc/DS1.lis.xz", "arc", 2097152),
]


def windows(lines, size):
    out = []
    for ln in lines:
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        out.append(int(d["win"]) / size)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--starts", default="0.4,0.8")
    ap.add_argument("--seeds", default="1")
    args = ap.parse_args()
    starts = [float(s) for s in args.starts.split(",")]
    seeds = [int(s) for s in args.seeds.split(",")]

    with open(args.out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "start", "seed", "samples", "end_window", "fell", "undone",
                    "pp_per_sample", "samples_to_floor", "requests_to_floor_x_max", "path"])
        for label, path, fmt, size in CELLS:
            if not os.path.exists(path):
                print(f"SKIP {label}", flush=True)
                continue
            for start in starts:
                for seed in seeds:
                    _, lines = R.variant(
                        path, size, "hybrid", fmt, debug=True, seed=seed,
                        extra=[f"-Dcaffeine.climber.startwin={start}"])
                    wins = windows(lines, size)
                    if len(wins) < 2:
                        print(f"{label} start={start}: {len(wins)} samples, no rate", flush=True)
                        continue
                    # The characteristic descent step is the MEDIAN of the falling steps, not the
                    # plant-to-low-point average. Trajectories here are not monotone — a probe's
                    # undo restores the plant in one capped move and the walk starts over — and
                    # averaging across a restart reports a descent five times slower than the one
                    # the samples actually show (`s3_25k`@80% falls a clean 6pp/sample and the
                    # average reads 0.47). A first-monotone-run rule fails the other way, since a
                    # single rising sample at the plant truncates it to nothing.
                    steps = sorted(a - b for a, b in zip(wins, wins[1:]) if b < a)
                    rate = steps[len(steps) // 2] if steps else 0.0
                    fell = sum(steps)
                    undone = sum(b - a for a, b in zip(wins, wins[1:]) if b > a)
                    to_floor = (start - 0.02) / rate if rate > 0 else float("inf")
                    row = [label, size, start, seed, len(wins), round(wins[-1], 4),
                           round(fell, 4), round(undone, 4), round(100 * rate, 2),
                           round(to_floor, 1), round(4 * to_floor, 1),
                           " ".join(f"{x:.2f}" for x in wins[:24])]
                    w.writerow(row)
                    fh.flush()
                    print(f"{label:9s} start={start} seed={seed} n={len(wins):4d} "
                          f"end={wins[-1]:.3f} rate={100 * rate:5.2f}pp/sample "
                          f"fell={100 * fell:6.1f} undone={100 * undone:6.1f} "
                          f"toFloor={to_floor:5.1f} samples ({4 * to_floor:6.1f} x maximum "
                          f"requests)", flush=True)
                    print(f"    {' '.join(f'{x:.2f}' for x in wins[:24])}", flush=True)


if __name__ == "__main__":
    main()
