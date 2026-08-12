#!/usr/bin/env python3
"""Hostile initial windows: can the climber walk a planted window back to a small optimum?

Every other instrument in this directory starts the cache where the product starts it, at a 1%
window. On a frequency-optimal trace that is already the answer, so the row passes without the
climber having to move, and a machine that cannot *descend* would read clean on the whole real
corpus. This sweep plants the window at 5/10/20/40/80% of the maximum and asks whether the run
recovers.

Harness-dependent: `harness.py apply <worktree>` adds `-Dcaffeine.climber.startwin=<frac>`, which
plants the window in `BoundedLocalCache.setMaximumSize`. The plant holds probation's capacity at
its shipped value, so the cell differs from a default start by window POSITION only; that is the
geometry the climber itself produces, since `increaseWindow` conserves window and protected.
Pass `--split resize` for the other geometry, which is what a `setMaximum` resize leaves behind.

Read the result against three references, all in the emitted CSV:

  static@start  a frozen window at the planted position — the do-nothing floor. A run that ties
                this NEVER MOVED, whatever its trajectory says.
  default       the same cell from the shipped 1% start — the recovery target.
  ceiling       the best static window — how much is on the table.

`recov` is the fraction of the default-start advantage over the frozen plant that the run wins
back: (hr - static@start) / (default - static@start). 1.0 is full recovery, 0.0 is stuck at the
plant. It is undefined (blank) where the plant is not a handicap.

Usage: startwin.py <out.csv> [--cells ds1,s3,...] [--seeds 1,2,3] [--split climber|resize]
"""
import argparse, csv, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")

# Frequency-favorable cells, where the shipped 1% start is at or beside the static optimum, so a
# descent is the only thing a hostile plant can be recovered by. The two ARC families are the
# ones the corpus already carries (`floors.py`); the rest widen the workbench across trace
# families and across the reachable size ladder.
CELLS = [
    # Convergence controls: the same two traces at a maximum small enough that the sample period
    # (4 x maximum) leaves plenty of decisions, so "descends too slowly for the trace" separates
    # from "does not descend".
    ("ds1_256k", f"{T}/arc/DS1.lis.xz", "arc", 262144),
    ("s3_25k", f"{T}/arc/S3.lis.xz", "arc", 25000),

    ("ds1_1M", f"{T}/arc/DS1.lis.xz", "arc", 1051635),
    ("ds1_2M", f"{T}/arc/DS1.lis.xz", "arc", 2097152),
    ("ds1_4M", f"{T}/arc/DS1.lis.xz", "arc", 4194304),
    ("ds1_8M", f"{T}/arc/DS1.lis.xz", "arc", 8388608),
    ("s3_100k", f"{T}/arc/S3.lis.xz", "arc", 100000),
    ("s3_200k", f"{T}/arc/S3.lis.xz", "arc", 200000),
    ("s3_400k", f"{T}/arc/S3.lis.xz", "arc", 400000),
    ("s3_800k", f"{T}/arc/S3.lis.xz", "arc", 800000),

    # Two more families, from the 20-cell anchors-only screen (11 qualified: optimum window <= 5%
    # and a >= 3pp fall by an 80% window). `cp_w050` has the widest window response of anything
    # screened, 22.71pp, and is already a `floors.py` cell rather than a fresh holdout; `MergeS` is
    # a different ARC family long enough to hold ~29 decisions at a 262144 maximum, which makes it
    # the second cell that can be barred. Screened and rejected: `cp_w060` peaks at a 70% window,
    # `cp_w097`/`w098` at 10%, `lcs_cs`/`ps` are window-insensitive (0.00pp spread), `wiki_1191a`
    # spreads 2.58.
    ("cp_w050", f"{T}/cloud_physics/w050.xz", "cloud-physics", 123038),
    ("merges_256k", f"{T}/arc/MergeS.lis.xz", "arc", 262144),
]

# Planted windows. Every value is on run.py's static-window grid, so `static@start` is read off
# the same anchor sweep rather than costing its own run. None is the shipped default.
STARTS = [None, 0.05, 0.10, 0.20, 0.40, 0.80]


def cell_rows(label, path, fmt, size, seeds, split, arms):
    lru, static = R.curve(path, size, fmt)
    if lru is None:
        print(f"SKIP {label}: no anchors", flush=True)
        return
    best_hr = max(static.values())
    best_win = max(static, key=lambda w: static[w])
    print(f"{label}@{size}: LRU={lru:.2f} ceiling={best_hr:.2f} @win {best_win}% "
          f"static={ {w: static[w] for w in sorted(static)} }", flush=True)

    default = {}
    for start in STARTS:
        extra = [] if start is None else [f"-Dcaffeine.climber.startwin={start}",
                                          f"-Dcaffeine.climber.startsplit={split}"]
        for arm in arms:
            hrs, first = [], []
            for i, seed in enumerate(seeds):     # arms rotate inside the seed, per the gate's rule
                hr, lines = R.variant(path, size, arm, fmt, debug=True, seed=seed, extra=extra)
                if hr is not None:
                    hrs.append(hr)
                    if i == 0:
                        first = lines
            if not hrs:
                print(f"  start={start} {arm}: FAILED", flush=True)
                continue
            hr = statistics.mean(hrs)
            if start is None:
                default[arm] = hr
            dflt = default.get(arm)
            frozen = static.get(int(round(100 * (start or 0.01))))
            gain = None if (frozen is None or dflt is None) else dflt - frozen
            recov = None if (not gain or gain < 1.0) else (hr - frozen) / gain
            traj = R.summarize(first, size)
            yield {"cell": label, "size": size, "arm": arm,
                   "start": "default" if start is None else start,
                   "hr": round(hr, 2), "sd": round(max(hrs) - min(hrs), 2), "lru": round(lru, 2),
                   "ceiling": round(best_hr, 2), "ceiling_win": best_win,
                   "static_at_start": frozen, "default": None if dflt is None else round(dflt, 2),
                   "recov": None if recov is None else round(recov, 3), "traj": traj}
            print(f"  start={str(start):8s} {arm:8s} hr={hr:6.2f}±{max(hrs) - min(hrs):.2f} "
                  f"frozen={frozen} dDflt={'' if dflt is None else f'{hr - dflt:+.2f}'}"
                  f" recov={'' if recov is None else round(recov, 3)}  {traj}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--cells", default=None, help="comma-separated cell labels; default all")
    ap.add_argument("--seeds", default="1", help="comma-separated admission seeds")
    ap.add_argument("--split", default="climber", choices=["climber", "resize"])
    ap.add_argument("--arms", default="hybrid",
                    help="climber arms; `reactive` is the hit-rate law the density tier replaced, "
                         "which is the anchor for whether a plant regresses against v2")
    args = ap.parse_args()
    seeds = [int(s) for s in args.seeds.split(",")]
    wanted = args.cells.split(",") if args.cells else None
    arms = args.arms.split(",")

    cols = ["cell", "size", "arm", "start", "hr", "sd", "lru", "ceiling", "ceiling_win",
            "static_at_start", "default", "recov", "traj"]
    with open(args.out, "w", newline="") as fh:
        w = csv.DictWriter(fh, cols)
        w.writeheader()
        for label, path, fmt, size in CELLS:
            if wanted and (label not in wanted):
                continue
            if not os.path.exists(path):
                print(f"SKIP {label}: missing {path}", flush=True)
                continue
            for row in cell_rows(label, path, fmt, size, seeds, args.split, arms):
                w.writerow(row)
                fh.flush()


if __name__ == "__main__":
    main()
