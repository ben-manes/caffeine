#!/usr/bin/env python3
"""slowswap: a regime change delivered below the park's release threshold.

Mechanism under test. An audit's confirm PARKS (`Anchor.park`), and while parked the router
returns a literal 0.0 before ever reaching `density.steer`. The park has exactly three exits:

  * a confirming audit (cadence `AuditClock.waitSamples`, which ratchets 32->64->...->512),
  * a starvation confirm (needs a blind corner, which a sighted window never produces),
  * `isWorkloadShift` -> `Anchor.standDown`, which is |hitRate - previousHitRate| >= 5pp,
    a SINGLE-SAMPLE RATE STEP.

The guard rail cannot substitute: while parked the window stands ON the anchor, so
`Anchor.track` takes the `resync` branch and follows the rate down, and `vetoTriggered`
requires `isAwayFrom`. (Measured: the rail fires once in ~6000 density samples of the whole
committed gate battery.)

So the only *automatic* release is a rate step -- while the thing that invalidates a park is a
change in the optimal WINDOW. Round 3 removed exactly this rate-vs-position confusion from the
audit clock ("what must be still is the position, never the rate"); it still governs the park.

The trace:
  prologue  recency pairs at D = --dist * max   -> drives the window wide, audit confirms
  phase 1   frequency: uniform hot set in main  -> optimum returns to the floor; the down-audit
                                                   confirms there and PARKS; later audits fail
                                                   at the correct position, ratcheting the clock
  swap      hot share -> band share, linearly over `--ramp` samples
  phase 3   recency again: optimum is a wide window, but the window is parked at the floor

Dose-matched control: `--ramp 1` delivers the identical regime change as one step, which clears
5pp and stands the anchor down. Same generator, same shares, same reuse distances, same request
count -- only the number of samples the transition is spread over differs. Arms `noaudit` and
`parkbound` remove the park itself on the identical bytes.

A whisper-style pair dose keeps the window earning above the starvation bar throughout, so no
blind corner ever pre-empts the audit branch.

Usage: slowswap.py --ramp 20 --out slowswap_r20.lirs
"""
import argparse, random
from traceio import TraceWriter

HOT_BASE = 1_000_000
BAND_BASE = 5_000_000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 20_000_000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--prologue", type=int, default=14, help="recency prologue, in samples")
    ap.add_argument("--p1", type=int, default=150, help="frequency phase, in samples")
    ap.add_argument("--ramp", type=int, default=20, help="transition length, in samples")
    ap.add_argument("--p3", type=int, default=120, help="closing recency phase, in samples")
    ap.add_argument("--hotn", type=int, default=5000, help="hot-set size (main-resident)")
    ap.add_argument("--dist", type=float, default=0.50, help="band reuse distance, x max")
    ap.add_argument("--share", type=float, default=0.55, help="working-set request share")
    ap.add_argument("--whisper", type=float, default=0.0038)
    ap.add_argument("--seed", type=int, default=20260803)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    sample = 4 * a.max
    band_d = int(a.dist * a.max)
    n = (a.prologue + a.p1 + a.ramp + a.p3) * sample
    rnd = random.Random(a.seed)
    pending = {}
    band_id = whisp_id = noise_id = 0
    out = TraceWriter(a.out)

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    def band_frac(s):
        """Share of the working set that is recency band (vs the frequency hot set)."""
        if s < a.prologue:
            return 1.0
        s -= a.prologue
        if s < a.p1:
            return 0.0
        s -= a.p1
        if s < a.ramp:
            return (s + 1) / a.ramp
        return 1.0

    for pos in range(n):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        bf = band_frac(pos // sample)
        r = rnd.random()
        if r < a.share:
            if rnd.random() < bf:
                k = BAND_BASE + band_id
                band_id += 1
                out.append(k)
                schedule(pos + band_d, k)
            else:
                out.append(HOT_BASE + rnd.randrange(a.hotn))
        elif r < a.share + a.whisper:
            k = WHISP_BASE + whisp_id
            whisp_id += 1
            out.append(k)
            schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1

    out.close()
    print(f"{a.out}: {len(out)} requests ({n // sample} samples), ramp={a.ramp} "
          f"band={band_id} whisper={whisp_id} noise={noise_id}")


if __name__ == "__main__":
    main()
