#!/usr/bin/env python3
"""The thin-signal floor cells — where refusing the blind-corner backoff should HURT.

`hill-climber.md` §3: these are frequency-ish traces whose floor window earns hits "in the same
band as a trapped window" (w50: 131–437 per 492k sample against a bar of ~480), so they sit in a
declared blind corner with a still window and a floor that is genuinely correct. They are the
cells the refractory ladder exists to protect, and therefore the ones any arm that re-arms
exploration on a held blind sample must be priced against. The gate row is "within noise of the
recorded values"; w50 is recorded at 59.85.

Cells follow the gate's `thin-signal floors` row (w50@123038, S1/S2/S3, DS1@1051635). ARC traces use the `arc` reader; declaring `lirs` for them yields nothing, silently.
Nothing here touches the frozen merge-gate holdout, nor `arc/P3` (the derived-guard study's
unspent holdout).

Usage: floors.py <arm1,arm2,...> <out.csv> [runs]
"""
import csv, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
CELLS = [
    ("cp_w050@123038", f"{T}/cloud_physics/w050.xz", "cloud-physics", 123038),
    ("arc_S1", f"{T}/arc/S1.lis.xz", "arc", 65536),
    ("arc_S2", f"{T}/arc/S2.lis.xz", "arc", 65536),
    ("arc_S3", f"{T}/arc/S3.lis.xz", "arc", 65536),
    ("arc_DS1@1051635", f"{T}/arc/DS1.lis.xz", "arc", 1051635),
]


def main():
    arms = sys.argv[1].split(",")
    out = sys.argv[2]
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "runs"] + [c for a in arms for c in (a, a + "_sd")])
        for label, path, fmt, size in CELLS:
            if not os.path.exists(path):
                print(f"SKIP {label}: missing {path}", flush=True)
                continue
            got = {a: [] for a in arms}
            for _ in range(runs):
                for a in arms:                      # rotate arms inside each run
                    hr = R.variant(path, size, a, fmt)[0]
                    if hr is not None:
                        got[a].append(hr)
            row = [label, size, runs]
            for a in arms:
                v = got[a]
                row += [round(statistics.mean(v), 2) if v else "",
                        round(max(v) - min(v), 2) if len(v) > 1 else 0]
            w.writerow(row)
            fh.flush()
            print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
