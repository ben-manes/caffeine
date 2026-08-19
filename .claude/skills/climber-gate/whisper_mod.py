#!/usr/bin/env python3
"""whisper + a window-irrelevant hit-rate modulation.

The committed `whisper` trace (climber-gate skill) is the sharpest repro of the F4 "sighted
false equilibrium": 0.38% of requests with short reuse keep the window's sample hits just
above the starvation bar, so no starvation probe ever arms and the density arm pins the window
at the 2% floor while a large window-only reuse band goes uncaptured. It is the row the
2026-07-30 equilibrium audit was built to rescue (55.5 -> 64.1, LRU 64.6).

This variant keeps that structure bit-for-bit and only modulates the *hot vs one-shot-noise*
mix on a slow sinusoid. Hot keys are 3000 uniform keys resident in main; one-shot noise always
misses. Trading weight between them moves the sample hit rate without touching the reuse band
that the escape has to find. The modulation is deliberately sub-crash: at the periods used
here the largest sample-to-sample change stays under the climber's 5pp RESTART_THRESHOLD, so
it never announces itself as a workload shift -- it only inflates `rateDeviationEma`, and with
it the 3x margin that both the guard rail and the audit's confirm are priced against.

Usage: whisper_mod.py --amp 0.08 --period 12 --out path.lirs
       (--amp is in units of request share; --period is in climber samples of 4*8192 requests)
"""
import argparse, math, random
from traceio import TraceWriter

N = 4_000_000
HOT_BASE, HOT_N = 1_000_000, 3000
BAND_BASE, BAND_D = 5_000_000, 3000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 9_000_000
W_HOT, W_BAND, W_WHISP = 0.631, 0.1434, 0.0038
SAMPLE = 4 * 8192


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--amp", type=float, default=0.08, help="hot-share modulation amplitude")
    ap.add_argument("--period", type=float, default=12.0, help="modulation period, in samples")
    ap.add_argument("--whisper", type=float, default=1.0, help="scale on the whisper dose")
    ap.add_argument("--shape", default="sine", choices=["sine", "square", "triangle", "walk"])
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    rnd = random.Random(20260726)
    pending = {}
    band_id = whisp_id = noise_id = 0
    out = TraceWriter(a.out)
    period_req = a.period * SAMPLE

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    w_whisp = W_WHISP * a.whisper
    wrnd = random.Random(31337)
    walk = 0.0
    for pos in range(N):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        phase = (pos % period_req) / period_req
        if a.shape == "sine":
            wave = math.sin(2.0 * math.pi * phase)
        elif a.shape == "square":
            wave = 1.0 if phase < 0.5 else -1.0
        elif a.shape == "triangle":
            wave = 4.0 * abs(phase - 0.5) - 1.0
        else:                                   # bounded random walk, one step per sample
            if pos % SAMPLE == 0:
                walk = max(-1.0, min(1.0, walk + wrnd.uniform(-0.7, 0.7)))
            wave = walk
        w_hot = W_HOT + a.amp * wave
        r = rnd.random()
        if r < w_hot:
            out.append(HOT_BASE + rnd.randrange(HOT_N))
        elif r < w_hot + W_BAND:
            k = BAND_BASE + band_id
            band_id += 1
            out.append(k)
            schedule(pos + BAND_D, k)
        elif r < w_hot + W_BAND + w_whisp:
            k = WHISP_BASE + whisp_id
            whisp_id += 1
            out.append(k)
            schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1

    out.close()
    print(f"{a.out}: {len(out)} requests, band={band_id} whisper={whisp_id} noise={noise_id} "
          f"amp={a.amp} period={a.period} samples")


if __name__ == "__main__":
    main()
