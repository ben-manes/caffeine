#!/usr/bin/env python3
"""ATTACK: "posjam" -- positional jamming of the equilibrium-audit clock.

The 2026-07-30 adversary round closed a RATE jam (a periodic crash-scale hit-rate
swing zeroed the audit clock) by re-defining stillness as a property of the WINDOW
POSITION: `stableSamples` counts samples whose window moved less than
STABLE_BAND_FRACTION (2%) of the maximum.  That makes the clock immune to rate
events -- and exposes it to POSITION events.

A workload that makes the density arm command a >2%-of-max step every sample keeps
`stableSamples` pinned at 0, so `stableSamples >= auditWait` is never true and NO
audit ever arms.  The window then reverts to whatever pure density does, which on
the whisper family is a floor pin ~9pp below LRU.

Construction: the whisper base (hot core in main + a window-only reuse band + a
whisper dose that keeps the window just above the starvation bar, so no blind-corner
probe arms either) with the whisper dose MODULATED on the request index at the
sample cadence.  A loud sample drives windowDensity/mainDensity above e^0.667 so the
proportional step exceeds the 2% band; the following quiet sample collapses the ratio
and the window is driven back to the floor -- another >2% move.  Neither the optimal
window nor the ceiling moves: the whisper pairs (reuse distance 100) are caught by any
window at or above the floor, and the band (distance 3000) sets the optimum either way.

Control (--mode flat) emits the SAME total whisper volume at a constant rate, so the
only difference between attack and control is the modulation.
"""
import argparse
import random

N = 4_000_000
HOT_BASE, HOT_N = 1_000_000, 3000
BAND_BASE, BAND_D = 5_000_000, 3000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 9_000_000

W_HOT, W_BAND = 0.631, 0.1434


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", default="jam", choices=["jam", "flat"])
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--hi", type=float, default=0.030, help="whisper draw rate in a loud sample")
    ap.add_argument("--lo", type=float, default=0.0015, help="whisper draw rate in a quiet sample")
    ap.add_argument("--period", type=int, default=2, help="modulation period, in samples")
    ap.add_argument("--duty", type=float, default=0.5, help="fraction of the period that is loud")
    ap.add_argument("--phase", type=int, default=0, help="phase offset, in requests")
    ap.add_argument("--period-req", type=int, default=0,
                    help="modulation period in REQUESTS (overrides --period)")
    ap.add_argument("--jitter", type=float, default=0.0,
                    help="per-cycle relative jitter on the period and burst length")
    ap.add_argument("--seed", type=int, default=20260801)
    args = ap.parse_args()

    sample = 4 * args.max                      # the density tier's sample period, in requests
    period = args.period_req if args.period_req else args.period * sample
    burst = int(args.duty * period)
    mean = args.duty * args.hi + (1.0 - args.duty) * args.lo

    rnd = random.Random(args.seed)
    pending = {}
    band_id = whisp_id = noise_id = 0
    out = []

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    # jittered schedule: burst onsets at randomized intervals with randomized lengths
    jrnd = random.Random(args.seed ^ 0x5eed)
    schedule_on = []
    if args.jitter > 0:
        t = 0
        while t < N:
            per = max(1, int(period * (1 + jrnd.uniform(-args.jitter, args.jitter))))
            bl = max(1, int(burst * (1 + jrnd.uniform(-args.jitter, args.jitter))))
            schedule_on.append((t, t + bl))
            t += per
    marks = bytearray(N)
    for a, b in schedule_on:
        for i in range(a, min(b, N)):
            marks[i] = 1

    loud = quiet = 0
    for pos in range(N):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        if args.mode == "flat":
            w = mean
        else:
            inburst = (marks[pos] == 1) if args.jitter > 0 \
                else (((pos + args.phase) % period) < burst)
            w = args.hi if inburst else args.lo
            loud += inburst
            quiet += not inburst
        r = rnd.random()
        if r < W_HOT:
            out.append(HOT_BASE + rnd.randrange(HOT_N))
        elif r < W_HOT + W_BAND:
            k = BAND_BASE + band_id
            band_id += 1
            out.append(k)
            schedule(pos + BAND_D, k)
        elif r < W_HOT + W_BAND + w:
            k = WHISP_BASE + whisp_id
            whisp_id += 1
            out.append(k)
            schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1

    with open(args.out, "w") as f:
        f.write("\n".join(map(str, out)))
        f.write("\n")
    print(f"{args.out}: {len(out)} requests  band={band_id} whisper={whisp_id} "
          f"noise={noise_id} mean_dose={mean:.4f} sample={sample}")


if __name__ == "__main__":
    main()
