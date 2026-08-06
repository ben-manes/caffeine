#!/usr/bin/env python3
"""D3's decisive test, without touching BoundedLocalCache.

At a range of STATIC window sizes, measure the hit rate and both candidate steering errors:

  average  error = ln( (H_w/C_w) / (H_m/C_m) )              <- what the density arm steers on
  marginal error = ln( (H_tail/C_tail) / (H_prob/C_prob) )  <- what D3 proposes

Then compare where each error crosses zero (its rest point) against where the hit rate peaks
(the optimum). The density arm's rest point is average-share equalization, which is only the
optimum when each region's hit curve is linear; the claim under test is that the marginal form
rests closer to the peak. No controller is involved — this is a property of the signals.

Requires a worktree wired by `harness.py apply`, pointed at by CAF_TREE — the counters live in
the anchor policy, not in the climber. Check `harness.py verify` before believing a run; NEVER
commit the wired tree.

Usage:
  CAF_TREE=<patched-worktree> marginal.py <out.csv> <manifest>   # TAIL=<delta>, default 0.2

The manifest holds one cell per line: `label fmt path size`.
"""
import csv, math, os, re, subprocess, sys

WT = os.environ.get("CAF_TREE", os.path.dirname(os.path.abspath(__file__)) + "/../../..")

# Stops at 0.80 on purpose: `increaseWindow` donates only the protected allocation, so the window
# tops out at `maximum - probation` (~80.2%) and anything past that is unreachable — which is the
# right range for a rest-point question. Set WINDOWS=0.9,0.95 to extend when the question is
# instead where the hit rate actually peaks; six of twenty-one cells measured 2026-08-08 peak
# above the ceiling, and a sweep that stops at 0.80 reports the wrong peak for them.
WINDOWS = [float(w) for w in os.environ["WINDOWS"].split(",")] if os.environ.get("WINDOWS") else [
    0.01, 0.02, 0.03, 0.05, 0.075, 0.10, 0.15,
    0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80]


def sweep(fmt, path, size, tail):
    args = [f"{WT}/gradlew", "simulator:run", "-q",
            f"-Dcaffeine.simulator.maximum-size={size}",
            f"-Dcaffeine.simulator.files.paths.0={fmt}:{os.path.expanduser(path)}",
            "-Dcaffeine.simulator.admission.0=Always",
            "-Dcaffeine.simulator.report.format=csv",
            "-Dcaffeine.simulator.policies.0=sketch.WindowTinyLfu",
            "-Dcaffeine.marginal.measure=true", f"-Dcaffeine.marginal.tail={tail}"]
    for i, w in enumerate(WINDOWS):
        args.append(f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.4f}")
    r = subprocess.run(args, capture_output=True, text=True, cwd=WT)
    hr = {}
    for ln in r.stdout.splitlines():
        m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),", ln)
        if m:
            hr[int(m.group(1))] = float(m.group(2))
    out = {}
    for ln in r.stderr.splitlines():
        if not ln.startswith("MARGINAL "):
            continue
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        pct = int(round(100 * float(d["winpct"])))
        f = {k: float(d[k]) for k in ("capW", "capM", "capP", "capT", "hw", "ht", "hp", "hq")}
        hm = f["hp"] + f["hq"]
        eps = 1e-9
        avg = math.log((f["hw"] / max(1.0, f["capW"]) + eps)
                       / (hm / max(1.0, f["capM"]) + eps))
        marg = math.log((f["ht"] / max(1.0, f["capT"]) + eps)
                        / (f["hp"] / max(1.0, f["capP"]) + eps))
        out[pct] = (hr.get(pct), avg, marg, f["hw"], f["ht"], f["hp"], f["hq"])
    if not out:
        sys.stderr.write((r.stdout[-1500:] + r.stderr[-1500:]))
    return out


def crossing(points):
    """Window % where the error series crosses zero DOWNWARD (interpolated), else None.

    Only a + -> - crossing is a rest point. The step carries the error's sign, so the controller
    is driven toward a downward crossing and away from an upward one; accepting either direction
    reported repellers as rest points and understated the average form's loss (fixed 2026-08-04).
    A cell that never crosses is not missing data — the arm is driven to the structural ceiling.
    """
    pts = sorted(points)
    for (w0, e0), (w1, e1) in zip(pts, pts[1:]):
        if (e0 > 0) and (e1 <= 0):
            return w0 + (w1 - w0) * (e0 / (e0 - e1)) if e0 != e1 else w0
    return None


def main():
    out, manifest = sys.argv[1], sys.argv[2]
    tail = os.environ.get("TAIL", "0.2")
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "tail", "peak_win", "peak_hr", "rest_avg", "rest_marg",
                    "err_avg", "err_marg", "hr_at_avg", "hr_at_marg", "loss_avg", "loss_marg"])
        for line in open(manifest):
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            label, fmt, path, size = line.split()
            data = sweep(fmt, path, int(size), tail)
            if not data:
                print(f"FAIL {label}", flush=True)
                continue
            hrs = {k: v[0] for k, v in data.items() if v[0] is not None}
            peak = max(hrs, key=lambda k: hrs[k])
            ra = crossing([(k, v[1]) for k, v in data.items()])
            rm = crossing([(k, v[2]) for k, v in data.items()])

            def hr_at(x):
                if x is None:
                    return None
                near = min(hrs, key=lambda k: abs(k - x))
                return hrs[near]
            ha, hm_ = hr_at(ra), hr_at(rm)
            row = [label, size, tail, peak, round(hrs[peak], 2),
                   None if ra is None else round(ra, 1), None if rm is None else round(rm, 1),
                   round(data[peak][1], 3), round(data[peak][2], 3),
                   None if ha is None else round(ha, 2), None if hm_ is None else round(hm_, 2),
                   None if ha is None else round(hrs[peak] - ha, 2),
                   None if hm_ is None else round(hrs[peak] - hm_, 2)]
            w.writerow(row)
            fh.flush()
            print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
