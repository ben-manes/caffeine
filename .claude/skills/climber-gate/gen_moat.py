#!/usr/bin/env python3
"""ATTACK (adv4): "moat" -- a hit-rate VALLEY between the resting window and the prize.

Mechanism under test: every walk the machine owns (starvation probe AND audit) aborts on
`hitRate <= probeBaseHitRate - walkInteriorBar()`, an ABSOLUTE comparison against the
sample the walk armed on. The bar is 5pp for audits (fixed) and min(max(5pp,3*dev),15pp)
for starvation probes. A walk therefore crosses only terrain that never dips more than the
bar below its start. Reach per attempt is one stride, rung-scaled: 6.25% -> 12.5% -> 25%
of the maximum. So a valley that is (a) deeper than the bar and (b) wider than ~25% of the
maximum is absorbing for BOTH walk kinds: every attempt crash-aborts, the ladder saturates,
and `auditWait` doubles to 512.

Trace shape (max M, sample = 4*M requests):
  hot    uniform over --hotn keys       -> lives in main; its hit rate is capacity-limited
                                           when hotn is near M, which is what digs the moat
  band   pairs at request-distance --bandd -> window-only reuse; the prize, reachable only
                                           once the window holds ~bandd*missRate entries
  whisp  pairs at distance 100          -> keeps the window's sample hits just ABOVE the
                                           starvation bar so the corner stays SIGHTED and
                                           only the AUDIT layer may act (--whisper 0 makes
                                           it blind, handing the corner to starvation probes)
  noise  unique keys                    -> churn

The DOSE is --hotn: the hot set's capacity sensitivity, i.e. the moat's depth. --hotn small
(e.g. 3000 at M=8192) is the dose-matched control: identical traffic volume, identical band
and whisper structure, identical reuse distances -- only the approach to the prize is
monotone instead of a valley.
"""
import argparse
import random
from traceio import TraceWriter


def build(path, max_size, n, hotn, bandd, w_hot, w_band, w_whisp, seed, blindat=0):
    rnd = random.Random(seed)
    HOT_BASE, BAND_BASE, WHISP_BASE, NOISE_BASE = 1_000_000, 5_000_000, 3_000_000, 9_000_000
    WHISP_D = 100
    pending, out = {}, TraceWriter(path)
    band_id = whisp_id = noise_id = 0

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    blind_pos = blindat * 4 * max_size if blindat else n + 1
    for pos in range(n):
        if pos == blind_pos:
            w_whisp = 0.0   # phase B: the window stops earning -> the corner turns BLIND
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        r = rnd.random()
        if r < w_hot:
            out.append(HOT_BASE + rnd.randrange(hotn))
        elif r < w_hot + w_band:
            k = BAND_BASE + band_id
            band_id += 1
            out.append(k)
            schedule(pos + bandd, k)
        elif r < w_hot + w_band + w_whisp:
            k = WHISP_BASE + whisp_id
            whisp_id += 1
            out.append(k)
            schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1
    out.close()
    return out, band_id, whisp_id, noise_id


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--n", type=int, default=4_000_000)
    ap.add_argument("--hotn", type=int, default=7000, help="moat DOSE: hot-set key count")
    ap.add_argument("--bandd", type=int, default=13000, help="band reuse distance (requests)")
    ap.add_argument("--whot", type=float, default=0.55)
    ap.add_argument("--wband", type=float, default=0.20)
    ap.add_argument("--whisper", type=float, default=0.0038,
                    help="whisper weight; 0 makes the floor corner blind")
    ap.add_argument("--blindat", type=int, default=0,
                    help="sample index after which the whisper stops (window turns blind)")
    ap.add_argument("--seed", type=int, default=20260801)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    out, band, whisp, noise = build(a.out, a.max, a.n, a.hotn, a.bandd,
                                    a.whot, a.wband, a.whisper, a.seed, a.blindat)
    print(f"{a.out}: {len(out)} requests, max={a.max} hotn={a.hotn} bandd={a.bandd} "
          f"band={band} whisper={whisp} noise={noise} samples={len(out)//(4*a.max)}")


if __name__ == "__main__":
    main()
