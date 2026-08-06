#!/usr/bin/env python3
"""Interleaved arm comparison on real density-tier cells from the local corpus.

Cells are drawn from the 50-cell corpus / 205-cell study corpus (routinely measured, spent).

The ARC cells declare the `arc` reader. They were `lirs` until 2026-08-04, which the reader
silently yields nothing for, so every ARC row this script wrote was blank and the "real corpus
10/10 ties" bar on record is the ten cloud-physics cells alone.

Excluded on purpose: the merge-gate holdout (wiki_1190, wiki_1192, umass_websearch3, msr_proj_4,
lcs_tencentPhoto) and `arc/P3`, which belongs to the derived-guard study's unspent holdout.

Usage: real.py <arm1,arm2,...> <out.csv> [runs]
"""
import csv, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
CELLS = [
    ("cp_w015", f"{T}/cloud_physics/w015.xz", "cloud-physics", 16384),
    ("cp_w038", f"{T}/cloud_physics/w038.xz", "cloud-physics", 16384),
    ("cp_w044", f"{T}/cloud_physics/w044.xz", "cloud-physics", 16384),
    ("cp_w050", f"{T}/cloud_physics/w050.xz", "cloud-physics", 16384),
    ("cp_w058", f"{T}/cloud_physics/w058.xz", "cloud-physics", 16384),
    ("cp_w060", f"{T}/cloud_physics/w060.xz", "cloud-physics", 16384),
    ("cp_w081", f"{T}/cloud_physics/w081.xz", "cloud-physics", 16384),
    ("cp_w097", f"{T}/cloud_physics/w097.xz", "cloud-physics", 16384),
    ("cp_w098", f"{T}/cloud_physics/w098.xz", "cloud-physics", 16384),
    ("cp_w100", f"{T}/cloud_physics/w100.xz", "cloud-physics", 16384),
    ("arc_P8", f"{T}/arc/P8.lis.xz", "arc", 65536),
    ("arc_S3", f"{T}/arc/S3.lis.xz", "arc", 65536),
    ("arc_ConCat", f"{T}/arc/ConCat.lis.xz", "arc", 65536),
]


def main():
    arms = sys.argv[1].split(",")
    out = sys.argv[2]
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 5
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
