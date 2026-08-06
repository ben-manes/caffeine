#!/usr/bin/env python3
"""ATTACK 1: "whisper" -- starvation-bar evasion (armProbe precondition).

Mechanism: densityClimb only probes at a *blind* corner (windowStarved =
windowHits < requestCount>>10, i.e. 0.1% of requests).  A workload that keeps
the window earning just ABOVE that bar -- but far below main's average density
-- is "sighted", so no probe ever arms, and the proportional density step
clamps the window at the 2% floor forever.  A large window-only reuse band is
then never discovered.

Trace shape (max=8192, sample=4*8192=32768 requests, bar=32 hits/sample):
  hot   55%  uniform over 3000 keys      -> lives in main (protected), LRU-friendly
  band  25%  pairs at distance 3000 req  -> needs window ~1000 entries; window-only
  whisp  .7% pairs at distance 100 req   -> ~109 window hits/sample: 3x the bar,
                                            but only 0.66 hits/entry vs main's 2.2
  noise rest unique keys                 -> churn

Control (--mode ctl) replaces the whisper pairs with unique noise keys: the
window then earns 0, the corner is blind, a probe arms and should find the band.
"""
import os
import random
import sys

N = 4_000_000
HOT_BASE, HOT_N = 1_000_000, 3000
BAND_BASE, BAND_D = 5_000_000, 3000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 9_000_000

# primary-draw weights (second accesses are scheduled, ~13% of positions)
W_HOT, W_BAND, W_WHISP = 0.631, 0.1434, 0.0038


def main(mode, path):
    rnd = random.Random(20260726)
    pending = {}
    band_id = 0
    whisp_id = 0
    noise_id = 0
    out = []

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    scale = {'half': 0.5, 'quarter': 0.25}.get(mode, 1.0)
    w_whisp = W_WHISP * scale
    for pos in range(N):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        r = rnd.random()
        if r < W_HOT:
            out.append(HOT_BASE + rnd.randrange(HOT_N))
        elif r < W_HOT + W_BAND:
            k = BAND_BASE + band_id
            band_id += 1
            out.append(k)
            schedule(pos + BAND_D, k)
        elif r < W_HOT + W_BAND + w_whisp:
            if mode == 'ctl':
                out.append(NOISE_BASE + noise_id)
                noise_id += 1
            else:
                k = WHISP_BASE + whisp_id
                whisp_id += 1
                out.append(k)
                schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1

    with open(path, 'w') as f:
        f.write('\n'.join(map(str, out)))
        f.write('\n')
    print(f'{path}: {len(out)} requests, band={band_id} whisper={whisp_id} noise={noise_id}')


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'attack'
    here = os.getcwd() + '/'  # run from the intended output directory

    name = {'attack': 'attack_whisper.lirs', 'ctl': 'attack_whisper_ctl.lirs',
            'half': 'attack_whisper_half.lirs', 'quarter': 'attack_whisper_quarter.lirs'}[mode]
    main(mode, here + name)
