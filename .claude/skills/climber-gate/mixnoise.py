#!/usr/bin/env python3
"""The `mixture` trap + a window-irrelevant hit-rate modulation, amplitude-dosed.

`gen_adv.py mixmod`'s construction (a Zipf hot set defends main; twice-accessed items at
reuse distance D go uncaptured by a floor window) with the per-sample wave pre-generated,
mean-centred, and RMS-normalised — every arm shares a mean hit rate, a variance, and an
LRU/static-window curve, differing only in the amplitude under test.

The `mn8_sine_a10` gate row is the probe-side walk-interior pricing's prize (the shipped adv3
fix): under the absolute 5pp bar the dose's wave crash-aborted every starvation up-probe one
stride off the floor (arm → crash → 16/32-sample refractory hold → repeat), severing the
mixture trap's only escape and pinning the cell 3.4pp below LRU; the priced-capped bar walks
through the weather and the escape confirms by sample ~5 (51.6 → 59.8, +4.8 over LRU). The
row's fall-back toward 51.6 is the tell that the probe bar's pricing or its cap broke.

  --shape sine / flat / shuffle: as in `crashnoise.py` — sine and flat are the gate arms;
  shuffle (the i.i.d. marginal control) is kept for completeness and is NOT evidence
  (bistable at N=8, sign flips across seeds and scales; the retracted autocorrelation
  sub-claim).

Usage: mixnoise.py --max 8192 --shape sine --amp 0.10 --out path.lirs
"""
import argparse, math, random


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=32768)
    ap.add_argument("--dfrac", type=float, default=0.25)
    ap.add_argument("--lengthmult", type=int, default=400)
    ap.add_argument("--hot-share", type=float, default=0.55)
    ap.add_argument("--pair-share", type=float, default=0.22)
    ap.add_argument("--amp", type=float, default=0.10)
    ap.add_argument("--period", type=float, default=6.0)
    ap.add_argument("--shape", default="sine", choices=["sine", "shuffle", "flat"])
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--noise-seed", type=int, default=0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    rng = random.Random(a.seed)                       # identical stream to gen_adv.py mixmod
    nrnd = random.Random(555_000 + a.noise_seed)
    sample = 4 * a.max
    hot_n = int(0.6 * a.max)
    d = max(2, int(a.dfrac * a.max))
    total = a.lengthmult * a.max
    zipf = zipf_sampler(hot_n, 0.9, rng)
    ring = [None] * d
    nxt = hot_n
    period_req = a.period * sample

    nsamples = total // sample + 2
    if a.shape == "sine":
        seq = [math.sin(2.0 * math.pi * ((s * sample % period_req) / period_req))
               for s in range(nsamples)]
    elif a.shape == "shuffle":
        seq = [math.sin(2.0 * math.pi * nrnd.random()) for _ in range(nsamples)]
    else:
        seq = [0.0] * nsamples
    if a.shape != "flat":
        mean = sum(seq) / len(seq)
        seq = [v - mean for v in seq]
        rms = math.sqrt(sum(v * v for v in seq) / len(seq))
        seq = [v / rms * math.sqrt(0.5) for v in seq]

    with open(a.out, "w") as f:
        for i in range(total):
            slot = i % d
            if ring[slot] is not None:
                f.write(f"{ring[slot]}\n")
                ring[slot] = None
                continue
            h = a.hot_share + a.amp * seq[i // sample]
            r = rng.random()
            if r < h:
                f.write(f"{zipf()}\n")
            elif r < h + a.pair_share:
                f.write(f"{nxt}\n")
                ring[slot] = nxt
                nxt += 1
            else:
                f.write(f"{nxt}\n")
                nxt += 1
    print(f"wrote {a.out}: {total} requests ({total // sample} samples) D={d} "
          f"shape={a.shape} amp={a.amp} period={a.period}")


if __name__ == "__main__":
    main()
