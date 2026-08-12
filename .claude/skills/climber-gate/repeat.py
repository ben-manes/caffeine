#!/usr/bin/env python3
"""Separates "the trace is too short for this maximum" from "the machine cannot descend".

A density sample is 4 x maximum requests, so DS1 at a 1M maximum holds ten decisions and at 4M
holds two. A plant that is not recovered there may only be showing that no controller can act in
two decisions. Replaying the same trace back to back multiplies the decisions without changing
the workload, so if the plant is recovered at 4 passes the deficit is a sample budget and not a
descent failure. The extra passes start warm, which is the right thing here: it removes the fill
phase from every pass but the first, so the descent is measured on a settled cache.

Usage: repeat.py <out.csv> [--passes 4] [--starts 0.4,0.8] [--seeds 1]
"""
import argparse, csv, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
CELLS = [
    ("ds1_1M", f"{T}/arc/DS1.lis.xz", "arc", 1051635),
    ("ds1_4M", f"{T}/arc/DS1.lis.xz", "arc", 4194304),
    ("s3_400k", f"{T}/arc/S3.lis.xz", "arc", 400000),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--passes", type=int, default=4)
    ap.add_argument("--starts", default="default,0.4,0.8")
    ap.add_argument("--seeds", default="1")
    ap.add_argument("--arms", default="hybrid")
    args = ap.parse_args()
    seeds = [int(s) for s in args.seeds.split(",")]

    with open(args.out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "passes", "arm", "start", "hr", "sd", "traj"])
        for label, path, fmt, size in CELLS:
            if not os.path.exists(path):
                print(f"SKIP {label}", flush=True)
                continue
            repeats = [f"-Dcaffeine.simulator.files.paths.{i}={fmt}:{os.path.abspath(path)}"
                       for i in range(1, args.passes)]
            for start in args.starts.split(","):
                plant = [] if start == "default" else [f"-Dcaffeine.climber.startwin={start}"]
                for arm in args.arms.split(","):
                    hrs, first = [], []
                    for i, seed in enumerate(seeds):
                        hr, lines = R.variant(path, size, arm, fmt, debug=True, seed=seed,
                                              extra=repeats + plant)
                        if hr is not None:
                            hrs.append(hr)
                            if i == 0:
                                first = lines
                    if not hrs:
                        print(f"{label} {start} {arm}: FAILED", flush=True)
                        continue
                    hr = statistics.mean(hrs)
                    traj = R.summarize(first, size)
                    w.writerow([label, size, args.passes, arm, start, round(hr, 2),
                                round(max(hrs) - min(hrs), 2), traj])
                    fh.flush()
                    print(f"{label:9s} x{args.passes} {arm:8s} start={start:7s} "
                          f"hr={hr:6.2f}  {traj}", flush=True)


if __name__ == "__main__":
    main()
