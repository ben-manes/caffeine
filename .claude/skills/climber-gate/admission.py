#!/usr/bin/env python3
"""Admission precision and yield across a static window sweep.

Runs the instrumented static anchor (`admission_harness.py apply <worktree>`) and reads the
per-region measure the report proposed taking from Merlin's Figure 20: how often an admission
is right, and how much it earns when it is.

The reason to want it is that the shipped climber steers on *density* — hits per unit of region
capacity — and density is a product:

    density = precision x yield x (entries per unit capacity)

Two regions can carry the same density because one is often slightly right and the other is
rarely but hugely right, and the steering signal cannot distinguish those. This measure can.

Every row is conservation-checked: the window and main hit counts must sum to the hit count the
policy reports, or the row is refused. That check is not decoration — the first version of the
harness silently dropped every protected-region hit and still printed plausible precisions.

Usage:  CAF_TREE=<instrumented-worktree> admission.py <out.csv> <manifest>
"""
import csv, os, re, subprocess, sys

WT = os.environ["CAF_TREE"]
WINDOWS = [0.01, 0.05, 0.20, 0.50, 0.80, 0.95]
HR_RE = re.compile(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),[\d.]+,(\d+),")


def sweep(fmt, path, size):
    args = [f"{WT}/gradlew", "simulator:run", "-q",
            f"-PjvmArgs={os.environ.get('ADM_HEAP', '-Xmx16g')}",
            f"-Dcaffeine.simulator.maximum-size={size}",
            f"-Dcaffeine.simulator.files.paths.0={fmt}:{os.path.expanduser(path)}",
            "-Dcaffeine.simulator.admission.0=Always",
            "-Dcaffeine.simulator.report.format=csv",
            "-Dcaffeine.simulator.policies.0=sketch.WindowTinyLfu",
            "-Dcaffeine.admission.measure=true"]
    args += [f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.4f}"
             for i, w in enumerate(WINDOWS)]
    r = subprocess.run(args, capture_output=True, text=True, cwd=WT)
    hits, hr = {}, {}
    for ln in r.stdout.splitlines():
        m = HR_RE.match(ln)
        if m:
            hr[int(m.group(1))] = float(m.group(2))
            hits[int(m.group(1))] = int(m.group(3))
    out = {}
    for ln in r.stderr.splitlines():
        if not ln.startswith("ADMISSION "):
            continue
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        pct = int(round(100 * float(d["winpct"])))
        f = {k: float(v) for k, v in d.items()}
        total = int(f["winHits"]) + int(f["mainHits"])
        if pct in hits and total != hits[pct]:
            sys.stderr.write(f"  REFUSED w{pct}: attributed {total} != reported {hits[pct]}\n")
            continue
        f["hr"] = hr.get(pct)
        out[pct] = f
    if not out:
        sys.stderr.write((r.stdout[-800:] + r.stderr[-800:]))
    return out


COLS = ["cell", "size", "window", "hr", "winPrec", "winYield", "winDensity",
        "mainPrec", "mainYield", "mainDensity", "mainRejected", "mainAdmitted"]


def main():
    out, manifest = sys.argv[1], sys.argv[2]
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(COLS)
        for line in open(manifest):
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            label, fmt, path, size = line.split()
            data = sweep(fmt, path, int(size))
            for pct in sorted(data):
                d = data[pct]
                w.writerow([label, size, pct,
                            None if d["hr"] is None else round(d["hr"], 2),
                            round(d["winPrec"], 4), round(d["winYield"], 2),
                            round(d["winDensity"], 3), round(d["mainPrec"], 4),
                            round(d["mainYield"], 2), round(d["mainDensity"], 3),
                            int(d["mainRejected"]), int(d["mainAdmitted"])])
                fh.flush()
            print(f"{label}: {len(data)}/{len(WINDOWS)} windows", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
