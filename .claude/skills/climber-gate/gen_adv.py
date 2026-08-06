#!/usr/bin/env python3
"""Adversarial generators for the 2026-07-31 climber review (density tier, >4096).

The prior families all attack the DENSITY signal or the PROBE machine. These attack the
2026-07-30 goal-metric layer instead: the anchor, its guard-rail veto, the `guardHold` park,
and the audit clock's stillness counter.

  regimeramp : the exogenous-vs-endogenous asymmetry. A durable, gradual workload regime
               change moves the achievable hit rate down by MORE than any window can earn
               back. The anchor's rate claim was earned in the old regime, so as soon as the
               density arm correctly steers away from it the smoothed rate is "short" -> the
               guard rail vetoes, drags the window back to the OLD regime's operating point
               and parks it (guardHold). Steering is then suppressed, and the audit's confirm
               bar (3 x the rate deviation) is calibrated on the exogenous swing while the
               only thing an audit can win is the endogenous window gain. Park is permanent.

  metronome  : the positional jam of the audit clock. `stableSamples` counts samples whose
               window moved less than 2% of the maximum. A workload that alternates its
               density preference on the sample cadence keeps the density arm taking large
               steps forever, so the clock never reaches auditWait and the F4 equilibrium
               audit -- the machine's only escape from a sighted-but-wrong operating point --
               can never arm. The reactive climber's step DECAYS under alternation and it
               settles; the density step is proportional to a signed error that never shrinks.

Format: lirs (one decimal key per line). Deterministic per seed.
Usage: gen_adv.py <kind> --max 8192 --out path.lirs [...]
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


# --------------------------------------------------------------------------- regimeramp

def gen_regimeramp(out, max_, seed, samples_a, samples_ramp, samples_b,
                   dfrac, keepfrac, keepshare,
                   a_hot, a_cold, b_hot, b_pair, b_cold):
    """Phase A is frequency-shaped and high-rate; phase B is recency-shaped and LOWER-rate
    even at its best window. The ramp between them never moves the sample hit rate by the
    5pp crash threshold in one sample, so the anchor is never discarded by the crash rule.

    Components
      hot  : Zipf over 0.5*max keys        -- main-resident, feeds mainDensity
      pair : two accesses at distance D    -- needs a window that spans D; the endogenous prize
      keep : two accesses at distance k<<D -- ~1.5% of traffic, keeps the window SIGHTED so no
             starvation probe ever arms (the whisper technique, used here to protect a park)
      cold : one-shot keys                 -- misses; sets the achievable ceiling
    """
    rng = random.Random(seed)
    period = 4 * max_                      # one climber sample
    hot_n = int(0.5 * max_)
    d = max(2, int(dfrac * max_))
    k = max(2, int(keepfrac * max_))
    zipf = zipf_sampler(hot_n, 1.0, rng)
    total_samples = samples_a + samples_ramp + samples_b

    ring_d = [None] * d
    ring_k = [None] * k
    nxt = hot_n
    i = 0
    with open(out, "w") as f:
        for s in range(total_samples):
            if s < samples_a:
                t = 0.0
            elif s < samples_a + samples_ramp:
                t = (s - samples_a + 1) / float(samples_ramp)
            else:
                t = 1.0
            hot = a_hot + t * (b_hot - a_hot)
            pair = t * b_pair
            cold = a_cold + t * (b_cold - a_cold)
            keep = keepshare
            # normalize
            tot = hot + pair + cold + keep
            hot, pair, cold, keep = hot / tot, pair / tot, cold / tot, keep / tot
            for _ in range(period):
                slot_d, slot_k = i % d, i % k
                i += 1
                if ring_k[slot_k] is not None:
                    f.write(f"{ring_k[slot_k]}\n")
                    ring_k[slot_k] = None
                    continue
                if ring_d[slot_d] is not None:
                    f.write(f"{ring_d[slot_d]}\n")
                    ring_d[slot_d] = None
                    continue
                r = rng.random()
                if r < hot:
                    f.write(f"{zipf()}\n")
                elif r < hot + pair:
                    f.write(f"{nxt}\n")
                    ring_d[slot_d] = nxt
                    nxt += 1
                elif r < hot + pair + keep:
                    f.write(f"{nxt}\n")
                    ring_k[slot_k] = nxt
                    nxt += 1
                else:
                    f.write(f"{nxt}\n")
                    nxt += 1
    print(f"wrote {out}: {total_samples * period} requests, {total_samples} samples "
          f"(A={samples_a} ramp={samples_ramp} B={samples_b}) D={d} k={k}")


# --------------------------------------------------------------------------- metronome

def gen_metronome(out, max_, seed, samples, ratio, dfrac, loopfrac, mix):
    """Alternate, on the sample cadence, between a recency-shaped block (pairs at distance D:
    window-dense) and a frequency-shaped block (a loop over loopfrac*max: main-dense). `ratio`
    is the block length in climber samples. Each block flips the sign of the density error, so
    the window takes a large step every sample and `stableSamples` can never accumulate.

    `mix` keeps a share of the OTHER shape present in every block so neither region is ever
    starved (no probe arms) -- the equilibrium is sighted, wrong, and unauditable.
    """
    rng = random.Random(seed)
    period = 4 * max_
    loop_n = int(loopfrac * max_)
    d = max(2, int(dfrac * max_))
    ring = [None] * d
    nxt = loop_n
    loop_i = 0
    i = 0
    with open(out, "w") as f:
        for s in range(samples):
            recency = ((s // ratio) % 2) == 0
            pair_share = (1.0 - mix) if recency else mix
            for _ in range(period):
                slot = i % d
                i += 1
                if ring[slot] is not None:
                    f.write(f"{ring[slot]}\n")
                    ring[slot] = None
                    continue
                if rng.random() < pair_share:
                    f.write(f"{nxt}\n")
                    ring[slot] = nxt
                    nxt += 1
                else:
                    f.write(f"{loop_i % loop_n}\n")
                    loop_i += 1
    print(f"wrote {out}: {samples * period} requests, {samples} samples, ratio={ratio} "
          f"D={d} loop_n={loop_n}")


# --------------------------------------------------------------------------- mixmod

def shape_value(kind, phase):
    """phase in [0,1) -> a zero-mean unit-amplitude wave."""
    import math
    if kind == "sine":
        return math.sin(2.0 * math.pi * phase)
    if kind == "square":
        return 1.0 if phase < 0.5 else -1.0
    if kind == "triangle":
        return 4.0 * abs(phase - 0.5) - 1.0
    raise ValueError(kind)


def gen_mixmod(out, max_, seed, dfrac, length_mult, hot_share, pair_share, amp, period, shape):
    """The committed `mixture` trap (gen.py) with a window-irrelevant hit-rate modulation.

    Trap: a Zipf hot-set defends main while twice-accessed items at reuse distance D go
    uncaptured by a floor window -- the density arm reads main as denser and holds the floor.
    Modulation: the hot share trades against one-shot cold keys on a slow wave, moving the
    sample hit rate without changing which window captures the reuse band.
    """
    rng = random.Random(seed)
    sample = 4 * max_
    hot_n = int(0.6 * max_)
    d = max(2, int(dfrac * max_))
    total = length_mult * max_
    zipf = zipf_sampler(hot_n, 0.9, rng)
    ring = [None] * d
    nxt = hot_n
    period_req = period * sample
    with open(out, "w") as f:
        for i in range(total):
            slot = i % d
            if ring[slot] is not None:
                f.write(f"{ring[slot]}\n")
                ring[slot] = None
                continue
            h = hot_share + amp * shape_value(shape, (i % period_req) / period_req)
            r = rng.random()
            if r < h:
                f.write(f"{zipf()}\n")
            elif r < h + pair_share:
                f.write(f"{nxt}\n")
                ring[slot] = nxt
                nxt += 1
            else:
                f.write(f"{nxt}\n")
                nxt += 1
    print(f"wrote {out}: {total} requests ({total // sample} samples) D={d} hot={hot_share} "
          f"pair={pair_share} amp={amp} period={period} shape={shape}")


# --------------------------------------------------------------------------- climbtrend

def gen_climbtrend(out, max_, seed, samples, keepfrac, keepshare, hot_lo, hot_hi, direction,
                   hotfrac=0.5, alpha=1.0, modamp=0.0, modperiod=12.0):
    """The streak confirm's admitted exposure, dosed: a monotone hit-rate trend.

    `AUDIT_CONFIRM_STREAK` confirms on four CONSECUTIVE raw samples above a reference frozen at
    arm. On a workload whose hit rate is trending, every sample beats a reference frozen a few
    samples earlier — so the streak completes on the trend alone, no matter where the walk has
    wandered, and the audit confirms a position it never tested. The confirm then plants the
    anchor, parks (guardHold), and shields the park from crash release for 32 samples.

    Structure: a Zipf hot set (frequency-shaped, wants the floor window) whose share rises
    monotonically against one-shot noise, plus a whisper-style short-reuse keepalive so the
    window stays SIGHTED and the audit -- not a starvation probe -- is the active mechanism.
    There is no long-reuse band anywhere, so the optimal window is the floor for the whole run:
    every confirm above it is a false one, by construction.

    The per-sample rise is deliberately far below the 5pp restart threshold, so the trend never
    announces itself as a workload shift.
    """
    rng = random.Random(seed)
    period = 4 * max_
    hot_n = int(hotfrac * max_)
    k = max(2, int(keepfrac * max_))
    zipf = zipf_sampler(hot_n, alpha, rng)
    ring = [None] * k
    nxt = hot_n
    i = 0
    with open(out, "w") as f:
        for s in range(samples):
            t = s / max(1, samples - 1)
            if direction == "down":
                t = 1.0 - t
            hot = hot_lo + t * (hot_hi - hot_lo)
            if modamp:
                import math
                hot += modamp * math.sin(2.0 * math.pi * s / modperiod)
            for _ in range(period):
                slot = i % k
                i += 1
                if ring[slot] is not None:
                    f.write(f"{ring[slot]}\n")
                    ring[slot] = None
                    continue
                r = rng.random()
                if r < hot:
                    f.write(f"{zipf()}\n")
                elif r < hot + keepshare:
                    f.write(f"{nxt}\n")
                    ring[slot] = nxt
                    nxt += 1
                else:
                    f.write(f"{nxt}\n")
                    nxt += 1
    rise = (hot_hi - hot_lo) / max(1, samples - 1)
    print(f"wrote {out}: {samples * period} requests, {samples} samples, hot {hot_lo}->{hot_hi} "
          f"({direction}), ~{100 * rise:.2f}pp/sample composition drift, keep={keepshare}")



# --------------------------------------------------------------------------- loopcliff

def gen_loopcliff(out, max_, seed, samples, loopfrac, keepfrac, keepshare):
    """A cold-start misconfirm aimed at a cliff.

    The calibration audit's confirm lands at a structurally determined position: the walk
    strides 6.25% of the maximum per sample and the confirm cannot fire before its committed
    depth of five, so the first audit parks at roughly a third of the cache. This workload is a
    cyclic loop over `loopfrac` of the maximum -- it fits in main while the window is small and
    thrashes once the window steals enough capacity -- so that position is a cliff rather than a
    gentle slope. The keepalive keeps the window sighted so the audit, not a starvation probe,
    is the mechanism under test.
    """
    rng = random.Random(seed)
    period = 4 * max_
    loop_n = int(loopfrac * max_)
    k = max(2, int(keepfrac * max_))
    ring = [None] * k
    nxt = loop_n
    loop_i = 0
    i = 0
    with open(out, "w") as f:
        for _ in range(samples):
            for _ in range(period):
                slot = i % k
                i += 1
                if ring[slot] is not None:
                    f.write(f"{ring[slot]}\n")
                    ring[slot] = None
                    continue
                if rng.random() < keepshare:
                    f.write(f"{nxt}\n")
                    ring[slot] = nxt
                    nxt += 1
                else:
                    f.write(f"{loop_i % loop_n}\n")
                    loop_i += 1
    print(f"wrote {out}: {samples * period} requests, {samples} samples, loop_n={loop_n} "
          f"({loopfrac} of max), keep={keepshare}")



# --------------------------------------------------------------------------- shieldtrap

def gen_shieldtrap(out, max_, seed, cycles, len_a, len_b, dfrac, hotfrac, keepshare):
    """Aimed at the fresh-park shield (`parkFreshLeft`).

    An audit's confirm parks the window and then deliberately ignores crash-scale rate swings
    for one initial audit wait (32 samples), so that a just-validated position is not released
    by the workload's own weather. The crash stand-down is, however, the machine's only fast
    detector of a genuine regime change -- so a workload that changes regime right after a
    confirm is frozen at the old regime's window for the length of the shield.

    Phase A is recency-shaped (pairs at reuse distance D) so an audit confirms a LARGE window
    and parks there. Phase B is frequency-shaped (a Zipf hot set that needs nearly all of main)
    where that same large window is expensive. The A->B transition is crash-scale by
    construction, which is exactly the signal the shield suppresses.
    """
    rng = random.Random(seed)
    period = 4 * max_
    d = max(2, int(dfrac * max_))
    hot_n = int(hotfrac * max_)
    zipf = zipf_sampler(hot_n, 0.7, rng)
    ring = [None] * d
    nxt = hot_n
    i = 0
    with open(out, "w") as f:
        for _ in range(cycles):
            for phase, length in (("a", len_a), ("b", len_b)):
                for _ in range(length):
                    for _ in range(period):
                        slot = i % d
                        i += 1
                        if ring[slot] is not None:
                            f.write(f"{ring[slot]}\n")
                            ring[slot] = None
                            continue
                        r = rng.random()
                        if phase == "a":
                            if r < 0.80:
                                f.write(f"{nxt}\n")
                                ring[slot] = nxt
                                nxt += 1
                            else:
                                f.write(f"{zipf()}\n")
                        else:
                            if r < keepshare:
                                f.write(f"{nxt}\n")
                                ring[slot] = nxt
                                nxt += 1
                            else:
                                f.write(f"{zipf()}\n")
    print(f"wrote {out}: {cycles * (len_a + len_b) * period} requests, "
          f"{cycles} cycles of A={len_a}/B={len_b} samples, D={d} hot_n={hot_n}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["regimeramp", "metronome", "mixmod", "climbtrend", "loopcliff", "shieldtrap"])
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", required=True)
    # regimeramp
    ap.add_argument("--samples-a", type=int, default=40)
    ap.add_argument("--samples-ramp", type=int, default=24)
    ap.add_argument("--samples-b", type=int, default=200)
    ap.add_argument("--dfrac", type=float, default=0.35)
    ap.add_argument("--keepfrac", type=float, default=0.005)
    ap.add_argument("--keepshare", type=float, default=0.015)
    ap.add_argument("--a-hot", type=float, default=0.86)
    ap.add_argument("--a-cold", type=float, default=0.125)
    ap.add_argument("--b-hot", type=float, default=0.42)
    ap.add_argument("--b-pair", type=float, default=0.12)
    ap.add_argument("--b-cold", type=float, default=0.445)
    # metronome
    ap.add_argument("--samples", type=int, default=240)
    ap.add_argument("--ratio", type=int, default=1)
    ap.add_argument("--loopfrac", type=float, default=0.9)
    ap.add_argument("--mix", type=float, default=0.12)
    # mixmod
    ap.add_argument("--lengthmult", type=int, default=576)
    ap.add_argument("--hot-share", type=float, default=0.55)
    ap.add_argument("--pair-share", type=float, default=0.22)
    ap.add_argument("--amp", type=float, default=0.0)
    ap.add_argument("--period", type=float, default=6.0)
    ap.add_argument("--shape", default="sine", choices=["sine", "square", "triangle"])
    # climbtrend
    ap.add_argument("--hot-lo", type=float, default=0.25)
    ap.add_argument("--hot-hi", type=float, default=0.85)
    ap.add_argument("--direction", default="up", choices=["up", "down"])
    ap.add_argument("--hotfrac", type=float, default=0.5)
    ap.add_argument("--alpha", type=float, default=1.0)
    ap.add_argument("--modamp", type=float, default=0.0)
    ap.add_argument("--modperiod", type=float, default=12.0)
    ap.add_argument("--cycles", type=int, default=6)
    ap.add_argument("--len-a", type=int, default=14)
    ap.add_argument("--len-b", type=int, default=26)
    a = ap.parse_args()

    if a.kind == "shieldtrap":
        gen_shieldtrap(a.out, a.max, a.seed, a.cycles, a.len_a, a.len_b,
                       a.dfrac, a.hotfrac, a.keepshare)
    elif a.kind == "loopcliff":
        gen_loopcliff(a.out, a.max, a.seed, a.samples, a.loopfrac, a.keepfrac, a.keepshare)
    elif a.kind == "climbtrend":
        gen_climbtrend(a.out, a.max, a.seed, a.samples, a.keepfrac, a.keepshare,
                       a.hot_lo, a.hot_hi, a.direction, a.hotfrac, a.alpha,
                       a.modamp, a.modperiod)
    elif a.kind == "mixmod":
        gen_mixmod(a.out, a.max, a.seed, a.dfrac, a.lengthmult, a.hot_share, a.pair_share,
                   a.amp, a.period, a.shape)
    elif a.kind == "regimeramp":
        gen_regimeramp(a.out, a.max, a.seed, a.samples_a, a.samples_ramp, a.samples_b,
                       a.dfrac, a.keepfrac, a.keepshare,
                       a.a_hot, a.a_cold, a.b_hot, a.b_pair, a.b_cold)
    else:
        gen_metronome(a.out, a.max, a.seed, a.samples, a.ratio, a.dfrac, a.loopfrac, a.mix)


if __name__ == "__main__":
    main()
