#!/usr/bin/env python3
"""The tier-straddle generator: a mixture whose recency share alternates, period as the dose.

The tier gate switches the steering law and the sample period together at maximum 4096 (reactive
samples every 10x maximum, density every 4x). A workload whose optimal window moves on a timescale
*between* those two cadences is trackable by one tier and not the other, so the same trace lands the
window in two different places on either side of one entry. The period is therefore the dose axis
for a tier cliff, and the family carries its own controls at both ends: a slow period both tiers
track, a fast period neither does.

The terrain is one mixture throughout — a Zipf hot set that always fits main, plus fresh keys
re-accessed at distance D — and the phases differ only in how much of the traffic is recency:

  F  recency share `--lo` (default 0.10): the optimal window is small
  R  recency share `--hi` (default 0.55): the optimal window is roughly D

Alternating the *share* rather than the regime is load-bearing. An earlier version alternated a
frequency loop with a pure pair-reuse phase; each phase's optimum is the other's disaster there, so
a fixed mid window beats any tracker and BOTH tiers land ~20pp below LRU. A cliff measured between
two failures says nothing, and `hill-climber.md` §3 already books that shape as the widepin limits
family. Here the hot set stays resident in both phases, so tracking the share pays and the two tiers
are separated by how fast they can follow it.

Dose matching: for fixed `--max`, `--lengthmult`, `--dfrac`, `--lo`, `--hi` every period emits the
same total request count, the same F/R slot split and the same hot key set; the recency key count is
driven by a per-slot accumulator with the same rule at every period, so it matches to within the
handful of keys a phase boundary can shift. The generator PRINTS the realized counts — check them
rather than assuming.

Deterministic: the hot-set sampler is seeded and the recency ids are a counter, so an instance
regenerates bit-identically.

Usage: gen_straddle.py --max 4096 --period 6 [--dfrac 0.35] [--lo 0.10] [--hi 0.55] --out t.lirs
"""
import argparse, random


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


def build(out, max_, period, dfrac, lo, hi, hotfrac, length_mult, seed):
    hot_n = max(1, int(hotfrac * max_))
    d = max(1, int(dfrac * max_))
    total = length_mult * max_

    # Snap the period so the phases tile the trace exactly and the F/R slot split is 50/50 at every
    # dose; without it the slow end emits a short trace and the control differs in length as well
    # as in period, which is the confound the controls exist to remove.
    valid = [k for k in range(1, total + 1) if (total % k == 0) and ((total // k) % 2 == 0)]
    want = max(1.0, period) * max_
    chunk = min(valid, key=lambda k: (abs(k - want), k))
    phases = total // chunk

    rng = random.Random(seed)
    zipf = zipf_sampler(hot_n, 0.9, rng)
    ring = [None] * d
    nxt = 10_000_000                       # recency ids disjoint from the hot set's
    acc = 0.0
    counts = {"hot": 0, "new": 0, "reuse": 0}

    with open(out, "w") as f:
        buf = []
        for i in range(total):
            slot = i % d
            if ring[slot] is not None:
                buf.append(ring[slot])
                ring[slot] = None
                counts["reuse"] += 1
            else:
                share = hi if ((i // chunk) % 2 == 1) else lo
                acc += share / 2.0         # each new key contributes two requests
                if acc >= 1.0:
                    acc -= 1.0
                    buf.append(nxt)
                    ring[slot] = nxt       # its re-access lands D slots later
                    nxt += 1
                    counts["new"] += 1
                else:
                    buf.append(zipf())
                    counts["hot"] += 1
            if len(buf) >= 1 << 16:
                f.write("\n".join(map(str, buf)) + "\n")
                buf = []
        if buf:
            f.write("\n".join(map(str, buf)) + "\n")
    return dict(requests=total, phases=phases, chunk=chunk, hot_n=hot_n, d=d, **counts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, required=True)
    ap.add_argument("--period", type=float, required=True, help="phase length in units of max")
    ap.add_argument("--dfrac", type=float, default=0.35, help="recency reuse distance / max")
    ap.add_argument("--lo", type=float, default=0.10, help="recency share in the F phase")
    ap.add_argument("--hi", type=float, default=0.55, help="recency share in the R phase")
    ap.add_argument("--hotfrac", type=float, default=0.6, help="hot set size / max")
    ap.add_argument("--lengthmult", type=int, default=192, help="total requests in units of max")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    i = build(args.out, args.max, args.period, args.dfrac, args.lo, args.hi,
              args.hotfrac, args.lengthmult, args.seed)
    eff = i["chunk"] / args.max
    snap = "" if abs(eff - args.period) < 1e-9 else f" (snapped from {args.period:g}x)"
    print(f"wrote {args.out}: {i['requests']} requests, {i['phases']} phases of {i['chunk']} "
          f"= {eff:g}x max{snap}, hot_n={i['hot_n']} D={i['d']} "
          f"hot={i['hot']} new={i['new']} reuse={i['reuse']}")


if __name__ == "__main__":
    main()
