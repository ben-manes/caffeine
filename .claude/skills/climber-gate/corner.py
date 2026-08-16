#!/usr/bin/env python3
"""What do the corner starvation probes cost, and how do they end?

Reads a directory of harness trajectory dumps (`census.py run`, or `run.py --dump`) and reports,
per cell over the seeds present, every starvation probe armed at a corner: the rung it armed at,
its ending (crash, fail, confirm), the walk's depth, and the ending sample's hit rate against the
mean of the eight non-walk samples before the arm, i.e. what that sample cost.

  corner.py <dumpdir> [variant] [top|floor]

`top` (default) is the upper corner, ARMdn at a window >= 75% of the maximum; `floor` is ARMup at
<= 25%. Measured 2026-08-15 over the battery at seeds 1-8: the upper corner's 166 probes ended
126 crash / 23 fail / 6 confirm and cost 4,430 sample-pp on their ending samples (the walk
overshoots a cliff by a stride and pays a whole sample), the floor's 773 probes 582 / 123 / 51.
The upper-corner probe earns +0.2 on the two real cells where it fires (cp_w015, arc_ConCat), so
it stays; its cost lives on constructed cliff and phase terrain (`hill-climber.md` section 7,
2026-08-15, H7).
"""
import collections, os, statistics, sys


def parse(fn):
    return [dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
            for ln in open(fn) if ln.startswith("climb ")]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dumpdir = sys.argv[1]
    variant = sys.argv[2] if len(sys.argv) > 2 else "hybrid"
    top = (sys.argv[3] if len(sys.argv) > 3 else "top") == "top"
    per = collections.defaultdict(list)
    for fn in sorted(os.listdir(dumpdir)):
        if not fn.endswith(".traj") or f"_{variant}_s" not in fn:
            continue
        cell = fn.split(f"_{variant}_s")[0]
        L = parse(os.path.join(dumpdir, fn))
        if not L:
            continue
        mx = int(L[0]["max"])
        n = len(L)
        i = 0
        while i < n:
            d = L[i]
            armed = ((d["mode"] == "ARMdn" and int(d["win"]) >= mx - (mx >> 2)) if top
                     else (d["mode"] == "ARMup" and int(d["win"]) <= (mx >> 2)))
            if armed:
                j = i + 1
                while j < n and L[j]["mode"].startswith("walk"):
                    j += 1
                end = L[j]["mode"] if j < n else "END"
                pre = [float(x["hr"]) for x in L[max(0, i - 8):i]
                       if not (x["mode"].startswith("walk") or x["mode"] in ("Crash", "Fail", "undo"))]
                base = statistics.mean(pre) if pre else float(d["hr"])
                endcost = (base - float(L[j]["hr"])) if j < n else 0.0
                depth = abs(int(d["win"]) - int(L[j - 1]["win"])) if j - 1 > i else 0
                per[cell].append((int(d["rung"]), end, round(100.0 * depth / mx), round(100 * endcost, 1)))
                i = j
            i += 1
    tot = collections.Counter()
    totcost, totn = 0.0, 0
    print(f"{'cell':22s} {'arms':>4s} {'crash':>5s} {'fail':>4s} {'conf':>4s} {'endcost':>8s}  rungs@arm")
    for cell, v in sorted(per.items()):
        ends = collections.Counter(e for _, e, _, _ in v)
        cost = sum(c for _, _, _, c in v)
        rungs = collections.Counter(r for r, _, _, _ in v)
        print(f"{cell:22s} {len(v):4d} {ends.get('Crash', 0):5d} {ends.get('Fail', 0):4d} "
              f"{ends.get('CONFIRM+steer', 0):4d} {cost:8.1f}  {dict(sorted(rungs.items()))}")
        tot.update(ends)
        totcost += cost
        totn += len(v)
    print(f"TOTAL arms={totn} endings={dict(tot)} ending-sample cost={totcost:.1f} sample-pp")


if __name__ == "__main__":
    main()
