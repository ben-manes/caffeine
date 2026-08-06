#!/usr/bin/env python3
"""Scale-parameterized adversarial trace generators targeting the climber designs' mechanisms.

All traces are lirs format (one integer key per line). Deterministic (seeded) so runs are
reproducible. Sizes are expressed relative to the intended cache size `max` so the same structure
can be synthesized at any scale — the point is that the density trap is scale-RELATIVE (floor = 2%
of max), so a workload family, not a fixed trace, is the adversarial object.

Generators:
  mixture   : steady interleave of a Zipf hot-set (defends main with high frequencies, provides
              continuous main hits) with a stream of pair-accessed recency items at reuse distance
              D. A window smaller than D catches none of the recency re-accesses, so the density
              signal reads windowDensity~0 vs mainDensity>0 and pins at the floor; the recency
              share of traffic is forfeited. Discriminator when floor < D and a climber could
              plausibly reach D.
  phases    : alternating frequency phases (loop over a main-sized working set) and recency phases
              (pair-accessed items at distance D) — the corda+loop structure synthesized at scale.
              Frequency phases drive the window to the floor; recency phases then present ~zero
              signal to a floor-pinned density climber (both densities ~0 => error=0 => hold),
              while the reactive climber restarts on the HR crash and re-explores.
  deadphase : a hot-set steady state interrupted by pure one-shot scans (no reuse at all). Nothing
              should move: measures an escape mechanism's false-fire cost (growing the window
              during a scan evicts useful main entries).
  widepin   : recency-heavy opening that drives the window very wide, then a frequency loop whose
              working set needs the main region. Probes the symmetric trap (window pinned wide,
              main starved and unmeasurable).

Usage: gen.py <kind> --max 8192 [--dfrac 0.25] [--phases 12] [--phaselen 16] [--out path.lirs]
"""
import argparse, random


def zipf_sampler(n, alpha, rng):
    # cumulative distribution over ranks 1..n
    weights = [1.0 / (i ** alpha) for i in range(1, n + 1)]
    total = sum(weights)
    cum = []
    acc = 0.0
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


def gen_mixture(out, max_, dfrac, length_mult, hot_share, rng):
    """hot_share of accesses go to a Zipf hot-set (~0.6*max keys); the rest are recency pairs:
    key emitted, re-emitted D accesses later via a delay ring, never seen again."""
    hot_n = int(0.6 * max_)
    d = int(dfrac * max_)
    total = length_mult * max_
    zipf = zipf_sampler(hot_n, 0.9, rng)
    ring = [None] * d
    nxt = hot_n  # recency keys start above the hot-set id space
    with open(out, "w") as f:
        for i in range(total):
            slot = i % d
            if ring[slot] is not None:
                f.write(f"{ring[slot]}\n")   # the delayed second access
                ring[slot] = None
                continue
            if rng.random() < hot_share:
                f.write(f"{zipf()}\n")
            else:
                f.write(f"{nxt}\n")
                ring[slot] = nxt             # schedule its re-access D later
                nxt += 1


def gen_phases(out, max_, dfrac, phases, phaselen, rng):
    """Alternating: frequency phase = loop over 0.9*max keys; recency phase = pair-accessed items
    at distance D (batch of D new keys, then the same batch again, repeated)."""
    loop_n = int(0.9 * max_)
    d = int(dfrac * max_)
    plen = phaselen * max_
    nxt = 10_000_000  # recency ids disjoint from loop ids
    with open(out, "w") as f:
        for p in range(phases):
            if p % 2 == 0:
                for i in range(plen):
                    f.write(f"{i % loop_n}\n")
            else:
                emitted = 0
                while emitted < plen:
                    batch = [nxt + j for j in range(d)]
                    nxt += d
                    for k in batch:
                        f.write(f"{k}\n")
                    for k in batch:
                        f.write(f"{k}\n")
                    emitted += 2 * d


def gen_deadphase(out, max_, phases, phaselen, rng):
    """Zipf steady state with pure one-shot scan bursts (each scan key seen exactly once ever)."""
    hot_n = int(0.6 * max_)
    plen = phaselen * max_
    zipf = zipf_sampler(hot_n, 0.9, rng)
    nxt = 10_000_000
    with open(out, "w") as f:
        for p in range(phases):
            if p % 2 == 0:
                for _ in range(plen):
                    f.write(f"{zipf()}\n")
            else:
                for _ in range(plen):
                    f.write(f"{nxt}\n")
                    nxt += 1


def gen_widepin(out, max_, phases, phaselen, rng):
    """Recency-heavy phase (pairs at distance 0.6*max — needs a wide window) alternating with a
    frequency loop over 0.85*max keys (needs the main region + admission)."""
    d = int(0.6 * max_)
    loop_n = int(0.85 * max_)
    plen = phaselen * max_
    nxt = 10_000_000
    with open(out, "w") as f:
        for p in range(phases):
            if p % 2 == 0:
                emitted = 0
                while emitted < plen:
                    batch = [nxt + j for j in range(d)]
                    nxt += d
                    for k in batch:
                        f.write(f"{k}\n")
                    for k in batch:
                        f.write(f"{k}\n")
                    emitted += 2 * d
            else:
                for i in range(plen):
                    f.write(f"{i % loop_n}\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["mixture", "phases", "deadphase", "widepin"])
    ap.add_argument("--max", type=int, required=True)
    ap.add_argument("--dfrac", type=float, default=0.25)
    ap.add_argument("--phases", type=int, default=12)
    ap.add_argument("--phaselen", type=int, default=16)   # in units of max
    ap.add_argument("--lengthmult", type=int, default=192)  # mixture total, in units of max
    ap.add_argument("--hotshare", type=float, default=0.5)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    if args.kind == "mixture":
        gen_mixture(args.out, args.max, args.dfrac, args.lengthmult, args.hotshare, rng)
    elif args.kind == "phases":
        gen_phases(args.out, args.max, args.dfrac, args.phases, args.phaselen, rng)
    elif args.kind == "deadphase":
        gen_deadphase(args.out, args.max, args.phases, args.phaselen, rng)
    else:
        gen_widepin(args.out, args.max, args.phases, args.phaselen, rng)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
