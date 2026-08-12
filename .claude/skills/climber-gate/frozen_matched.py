#!/usr/bin/env python3
"""Frozen-window reference measured with the PLANT's region geometry.

`run.py`'s static anchor sweep varies `percent-main` only and leaves `percent-main-protected` at
0.80, so its 80%-window point is a cache with 4% probation and 16% protected. The plant's default
geometry instead holds probation at its shipped 19.8% (what `increaseWindow` produces, since it
conserves window and protected), leaving ~0.2% protected. Those are different caches, so the
sweep's `static@start` is not a like-for-like do-nothing floor for a planted run.

This re-measures the frozen point with the plant's own split, so `hr(plant) - frozen` is a
statement about one cache. It costs one simulator run per (cell, window).

Usage: frozen_matched.py <out.csv>
"""
import csv, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
PROBATION = 0.20 * 0.99          # the shipped probation share, held across a plant
CELLS = [
    ("ds1_1M", f"{T}/arc/DS1.lis.xz", "arc", 1051635),
    ("ds1_2M", f"{T}/arc/DS1.lis.xz", "arc", 2097152),
    ("s3_100k", f"{T}/arc/S3.lis.xz", "arc", 100000),
    ("s3_400k", f"{T}/arc/S3.lis.xz", "arc", 400000),
]
WINDOWS = [0.10, 0.20, 0.40, 0.80]


def main():
    with open(sys.argv[1], "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "window", "protected_of_main", "frozen_climber_split",
                    "frozen_resize_split"])
        for label, path, fmt, size in CELLS:
            if not os.path.exists(path):
                print(f"SKIP {label}", flush=True)
                continue
            _, resize = R.curve(path, size, fmt, WINDOWS)
            for win in WINDOWS:
                main_share = 1.0 - win
                prot = max(0.0, (main_share - PROBATION) / main_share)
                # percent-main is read with getDoubleList, so it must be given indexed
                # (`percent-main.0`); a scalar throws WrongType out of the policy registry.
                r = R.gradle(size, path,
                             ["-Dcaffeine.simulator.policies.0=sketch.WindowTinyLfu",
                              f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.0="
                              f"{main_share:.4f}",
                              f"-Dcaffeine.simulator.window-tiny-lfu.percent-main-protected="
                              f"{prot:.4f}"], fmt)
                hr = None
                for ln in r.stdout.splitlines():
                    m = re.match(r"sketch\.WindowTinyLfu \(\d+%\),([\d.]+),", ln)
                    if m:
                        hr = float(m.group(1))
                if hr is None:
                    sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
                row = [label, size, win, round(prot, 4), hr, resize.get(int(round(100 * win)))]
                w.writerow(row)
                fh.flush()
                print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
