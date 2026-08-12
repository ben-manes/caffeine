#!/usr/bin/env python3
"""Checks that the descent rate is the density law's gain, not a mitigation getting in the way.

For each sample of a planted run it recomputes the steering error from the counters the debug line
already carries and compares `DENSITY_GAIN * |error| * maximum` against the window motion actually
observed. If the two agree, the descent is gain-limited and the fix space is the gain, the sample
period or an accelerator; if the predicted step is much larger than the observed one, something is
suppressing it (a park, a refractory hold, a cap) and the fix space is that instead.

`steeringError` floors each density at `0.125 * bar / capacity`, and `bar = max(4, requests>>10)`,
so the floors are reproduced here rather than using the raw ratio.

Usage: mechanism.py <trace> <size> <fmt> <start> [seed]
"""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

GAIN = 0.03
MAX_STEP = 0.30
STEERING_FLOOR = 0.125


def main():
    trace, size, fmt, start = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    seed = int(sys.argv[5]) if len(sys.argv) > 5 else 1
    _, lines = R.variant(trace, size, "hybrid", fmt, debug=True, seed=seed,
                         extra=[f"-Dcaffeine.climber.startwin={start}"])
    print(f"{'s':>3s} {'mode':>12s} {'win%':>6s} {'wh':>10s} {'mh':>10s} {'error':>7s} "
          f"{'predicted':>9s} {'observed':>9s}")
    prev = None
    for ln in lines:
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        maximum, win = int(d["max"]), int(d["win"])
        wh, mh = int(d["wh"]), int(d["mh"])
        requests = None                                  # not in the line; bar uses the sample
        bar = max(4, (4 * maximum) >> 10)                # the sample period is 4 x maximum
        wd = max(wh / max(1, win), STEERING_FLOOR * bar / max(1, win))
        md = max(mh / max(1, maximum - win), STEERING_FLOOR * bar / max(1, maximum - win))
        import math
        error = math.log(wd / md)
        pred = min(MAX_STEP * maximum, abs(error) * GAIN * maximum)
        pred = pred if error >= 0 else -pred
        obs = "" if prev is None else f"{100 * (win - prev) / maximum:+9.2f}"
        print(f"{d['s']:>3s} {d['mode']:>12s} {100 * win / maximum:6.2f} {wh:10d} {mh:10d} "
              f"{error:7.3f} {100 * pred / maximum:+9.2f} {obs:>9s}")
        prev = win
    _ = requests


if __name__ == "__main__":
    main()
