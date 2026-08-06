#!/usr/bin/env python3
"""ATTACK (terra r3): the blind-corner lockout of the audit layer.

Mechanism under test: `densityClimb` is a priority router in which `reading.hasBlindCorner()`
sat above `auditClock.isDue()`, so on a blind sample the audit could never arm however long the
position had been still (fixed 2026-08-04; this pair is its regression row). On a trap the
starvation probe cannot adjudicate its way out of, the machine then spends the rest of the
run alternating refractory holds and refused probes with the audit clock permanently due.

The instrument is a DOSE-MATCHED PAIR over one terrain. Both arms are the same mixture trap
(Zipf hot set defending main + pair-accessed recency items at reuse distance D, the prize a
grown window would catch). They differ only in a `whisper` rider of pairs at distance 100:

  --whisper 0        BLIND arm   : the window earns ~nothing at the floor, so every sample
                                   is a blind corner and the audit is locked out
  --whisper 0.004    SIGHTED arm : the rider lifts the window's sample hits just above the
                                   starvation bar, no blind corner is declared, and the same
                                   audit the blind arm never gets to arm escapes the trap

The rider is window-IRRELEVANT by construction (pairs at distance 100 hit in any window at
or above the 2% floor, so no static window prefers one arm), which the anchor sweep must
confirm: LRU and the static-window ceiling have to match across the two arms. Any hit-rate
gap between them is therefore the router's ordering, not the terrain.

Usage:
  gen_blindlock.py --max 8192 --whisper 0     --out blindlock_blind.lirs
  gen_blindlock.py --max 8192 --whisper 0.004 --out blindlock_sighted.lirs
"""
import argparse
import random

WHISPER_D = 100
WHISPER_BASE = 60_000_000


def zipf_sampler(n, alpha, rng):
    weights = [1.0 / (i ** alpha) for i in range(1, n + 1)]
    total = sum(weights)
    cum, acc = [], 0.0
    for w in weights:
        acc += w / total
        cum.append(acc)

    def sample():
        r = rng.random()
        lo, hi = 0, n - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if cum[mid] < r:
                lo = mid + 1
            else:
                hi = mid
        return lo
    return sample


def build(max_, dfrac, length_mult, hot_share, whisper, seed):
    """The gate's `gen.py mixture` trap, plus the blindness rider."""
    rng = random.Random(seed)
    hot_n = int(0.6 * max_)
    d = int(dfrac * max_)
    total = length_mult * max_
    zipf = zipf_sampler(hot_n, 0.9, rng)
    ring = [None] * d
    pending = {}
    out = []
    nxt = hot_n
    whisper_id = 0

    for i in range(total):
        k = pending.pop(i, None)
        if k is not None:
            out.append(k)
            continue
        slot = i % d
        if ring[slot] is not None:
            out.append(ring[slot])
            ring[slot] = None
            continue
        # draw unconditionally: short-circuiting on `whisper` desynchronises the two arms'
        # streams, which is exactly the shared terrain this pair exists to hold fixed
        if rng.random() < whisper:
            k = WHISPER_BASE + whisper_id
            whisper_id += 1
            out.append(k)
            pos = i + WHISPER_D
            while pos in pending:
                pos += 1
            pending[pos] = k
            continue
        if rng.random() < hot_share:
            out.append(zipf())
        else:
            out.append(nxt)
            ring[slot] = nxt
            nxt += 1
    return out, whisper_id


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--dfrac", type=float, default=0.50, help="reuse distance, as a fraction of max")
    ap.add_argument("--lengthmult", type=int, default=192, help="total requests, in units of max")
    ap.add_argument("--hotshare", type=float, default=0.5)
    ap.add_argument("--whisper", type=float, default=0.0,
                    help="rider weight; 0 keeps the corner blind, ~0.004 makes it sighted")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    out, whisper = build(a.max, a.dfrac, a.lengthmult, a.hotshare, a.whisper, a.seed)
    with open(a.out, "w") as f:
        f.write("\n".join(map(str, out)))
        f.write("\n")
    print(f"{a.out}: {len(out)} requests, max={a.max} dfrac={a.dfrac} whisper={whisper} "
          f"samples={len(out) // (4 * a.max)}")


if __name__ == "__main__":
    main()
