#!/usr/bin/env python3
"""Anchors-only screen for hostile-start workbench cells.

A cell is a workbench for the descent question when (a) the static-window optimum is small, so the
shipped 1% start is already the answer and only a descent can recover a plant, and (b) the curve
falls a long way by an 80% window, so the plant is a real handicap rather than a relabeling.

No `product.Caffeine` process is started, so nothing here spends a cell (`hill-climber.md` §6).
The frozen holdouts (arc P3/P4/P6/P7, MergeP@64k, cp w015/w044@16k) are excluded by omission.

Usage: screen.py <out.csv>
"""
import csv, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces")
POOL = [
    ("arc_S1", f"{T}/arc/S1.lis.xz", "arc", 65536),
    ("arc_S2", f"{T}/arc/S2.lis.xz", "arc", 65536),
    ("arc_S3", f"{T}/arc/S3.lis.xz", "arc", 65536),
    ("arc_P8", f"{T}/arc/P8.lis.xz", "arc", 65536),
    ("arc_P1", f"{T}/arc/P1.lis.xz", "arc", 65536),
    ("arc_P11", f"{T}/arc/P11.lis.xz", "arc", 65536),
    ("arc_P14", f"{T}/arc/P14.lis.xz", "arc", 65536),
    ("arc_ConCat", f"{T}/arc/ConCat.lis.xz", "arc", 65536),
    ("arc_MergeS", f"{T}/arc/MergeS.lis.xz", "arc", 262144),
    ("arc_spc1", f"{T}/arc/spc1likeread.lis.xz", "arc", 262144),
    ("cp_w050", f"{T}/cloud_physics/w050.xz", "cloud-physics", 123038),
    ("cp_w097", f"{T}/cloud_physics/w097.xz", "cloud-physics", 16384),
    ("cp_w060", f"{T}/cloud_physics/w060.xz", "cloud-physics", 16384),
    ("cp_w098", f"{T}/cloud_physics/w098.xz", "cloud-physics", 16384),
    # msr: `prn_0` is the one member of this family stored uncompressed. `proj_4` is spent at the
    # S6 merge gate and `hm_1@64k` is a frozen holdout, so neither is listed.
    ("msr_prn_0", f"{T}/snia/msr_cambridge/prn_0.csv", "snia-cambridge", 65536),
    ("msr_proj_0", f"{T}/snia/msr_cambridge/proj_0.csv.gz", "snia-cambridge", 65536),
    ("msr_prxy_0", f"{T}/snia/msr_cambridge/prxy_0.csv.gz", "snia-cambridge", 65536),
    ("msr_mds_0", f"{T}/snia/msr_cambridge/mds_0.csv.gz", "snia-cambridge", 65536),
    ("lcs_cs", f"{T}/all-trc/cs.trace.xz", "lirs", 8192),
    ("lcs_ps", f"{T}/all-trc/ps.trace.xz", "lirs", 8192),
    ("wiki_1191a", f"{T}/wikibench/wiki.1190153705.gz", "wikipedia", 65536),
]


def main():
    with open(sys.argv[1], "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "lru", "ceiling", "ceiling_win", "w1", "w80", "spread",
                    "workbench"])
        for label, path, fmt, size in POOL:
            if not os.path.exists(path):
                print(f"SKIP {label}: missing {path}", flush=True)
                continue
            lru, static = R.curve(path, size, fmt)
            if (lru is None) or not static:
                print(f"SKIP {label}: no anchors", flush=True)
                continue
            best = max(static, key=lambda k: static[k])
            w1, w80 = static.get(1), static.get(80)
            spread = None if (w1 is None or w80 is None) else round(w1 - w80, 2)
            ok = (best <= 5) and (spread is not None) and (spread >= 3.0)
            row = [label, size, round(lru, 2), round(static[best], 2), best, w1, w80, spread,
                   "YES" if ok else ""]
            w.writerow(row)
            fh.flush()
            print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
