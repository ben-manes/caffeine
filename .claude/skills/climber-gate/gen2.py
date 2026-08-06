#!/usr/bin/env python3
"""Second-wave adversarial generators targeting the probe machine's own mechanisms.

The committed climber (>4096) = density signal + starvation-guarded probe machine:
  bar = sample>>10 hits; blind corners W<=C/4, W>=3C/4 (or dead sample); probe walk seeded
  6.25%*C decaying 0.98, crash-abort at base-5pp&&declining; exit when watched region earns
  >=4*bar in ONE sample; density adjudicates (confirm e*dir>0 keeps + ladder=1, slam e*dir<=-1
  undoes); refractory ladder 16->x2->64 samples, reset to 1 on success.

These generators attack, respectively:
  straywall2 : the 4x-bar absolute exit -- hot-churn stray window hits cross the exit bar
               before the walk reaches the reuse band (band placed beyond the stray wall).
  troughwalk : the crash bar's base anchoring -- probes enter on dead (pair-phase) samples with
               base HR ~0, so the walk is uncrashable; a metered in-loop trickle keeps window
               hits inside (bar, 4*bar) so the walk never exits and never triggers new probes;
               the walk then ruins the following LRU-hostile loop phase end to end.
  ratchet    : adjudication spoofing + ladder reset -- bursty short-reuse pairs hand a probed
               window >=4*bar worthless hits with d_w > d_m exactly at exit, so verdicts are
               "success" (ladder=1) on fool's gold; uniform hot set makes displacement damage
               linear; base re-anchors at each already-damaged entry (ratchet).
  rocketphase: the confirm-sample density step -- after a genuine confirm in a dead-denominator
               phase (d_m ~ 0), error=ln(d_w/eps) is astronomic and steps +0.30*C per sample,
               rocketing the window to its ~80% cap; the returning LRU-hostile loop then pays
               3-5 slam samples per period. Short pair phases resonate this per period.
  barflap    : the starvation bar's discretization -- window hits dithered across bar=32
               sample-to-sample so the machine alternates refractory-burn/probe-entry forever;
               measures pure boundary-churn cost (no spoof).
  mixture2   : gen.py mixture with churn knobs (zipf alpha, hot set size/share) to steepen the
               stray-vs-W slope for straywall calibration.

All lirs format, deterministic. Sample S = 4*max requests everywhere; phase lengths are given
in SAMPLES so phase boundaries align with climber samples exactly.

Usage: gen2.py <kind> --max 8192 [knobs] --out path.lirs
"""
import argparse
import heapq
import random


def zipf_sampler(n, alpha, rng):
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


class Emitter:
    """Streams requests; delayed pair-seconds preempt the slot they come due on."""

    def __init__(self, out):
        self.f = open(out, "w")
        self.i = 0
        self.due = []            # heap of (due_index, seq, key)
        self.seq = 0

    def emit(self, key):
        self.f.write(f"{key}\n")
        self.i += 1

    def schedule(self, key, delay):
        self.seq += 1
        heapq.heappush(self.due, (self.i + delay, self.seq, key))

    def step(self, draw):
        """One request: a due second if any, else draw() (which may schedule)."""
        if self.due and self.due[0][0] <= self.i:
            self.emit(heapq.heappop(self.due)[2])
        else:
            draw()

    def close(self):
        self.f.close()


def gen_straywall2(out, max_, dfrac, alpha, hotfrac, hotshare, samples, rng):
    """Mixture: zipf hot set + once-repeated pairs at D=dfrac*max requests. The knobs steepen
    hot churn through the window (strays) so the 4x-bar exit fires before the walk reaches D."""
    S = 4 * max_
    hot_n = int(hotfrac * max_)
    d = int(dfrac * max_)
    zipf = zipf_sampler(hot_n, alpha, rng)
    em = Emitter(out)
    nxt = [100_000_000]

    def draw():
        if rng.random() < hotshare:
            em.emit(zipf())
        else:
            k = nxt[0]
            nxt[0] += 1
            em.emit(k)
            em.schedule(k, d)

    total = samples * S
    while em.i < total:
        em.step(draw)
    em.close()


def gen_troughwalk(out, max_, loopfrac, dpairfrac, len_loop, len_pair, trickle, dshort,
                   periods, warm, rng, pairfirst=False):
    """Loop phase (LRU-hostile loop, loopfrac*max keys) with a metered short-reuse trickle that
    keeps window hits in (bar, 4*bar); pair phase = pure pairs at D=dpairfrac*max requests,
    unreachable within len_pair samples from the floor. Probes enter on the dead pair-phase
    samples (base HR ~0) and walk uncrashed through the whole loop phase."""
    S = 4 * max_
    loop_n = int(loopfrac * max_)
    d_pair = int(dpairfrac * max_)
    em = Emitter(out)
    loop_i = [0]
    nxt_t = [50_000_000]
    nxt_p = [100_000_000]

    def draw_loop():
        if rng.random() < trickle:
            k = nxt_t[0]
            nxt_t[0] += 1
            em.emit(k)
            em.schedule(k, dshort)
        else:
            em.emit(loop_i[0] % loop_n)
            loop_i[0] += 1

    def draw_pair():
        k = nxt_p[0]
        nxt_p[0] += 1
        em.emit(k)
        em.schedule(k, d_pair)

    period = len_loop + len_pair
    total_samples = warm + periods * period
    for s in range(total_samples):
        if pairfirst:
            in_pair = (s >= warm) and (((s - warm) % period) < len_pair)
        else:
            in_pair = (s >= warm) and (((s - warm) % period) >= len_loop)
        draw = draw_pair if in_pair else draw_loop
        end = (s + 1) * S
        while em.i < end:
            em.step(draw)
    em.close()


def gen_ratchet(out, max_, hotfrac, ddeepfrac, dshort, burst_period, hot_on, trickle_on,
                hot_off, samples, rng):
    """Uniform hot set (linear displacement damage) + uncatchable deep pairs + short-reuse
    trickle present only on burst samples (1 in burst_period). The trickle hands a probed
    window >=4*bar hits with d_w>d_m at exit => confirm on fool's gold, ladder=1, re-probe
    from the damaged base."""
    S = 4 * max_
    hot_n = int(hotfrac * max_)
    d_deep = int(ddeepfrac * max_)
    em = Emitter(out)
    nxt_t = [50_000_000]
    nxt_d = [100_000_000]

    def hot():
        em.emit(rng.randrange(hot_n))

    def deep():
        k = nxt_d[0]
        nxt_d[0] += 1
        em.emit(k)
        em.schedule(k, d_deep)

    def tric():
        k = nxt_t[0]
        nxt_t[0] += 1
        em.emit(k)
        em.schedule(k, dshort)

    def draw_on():
        r = rng.random()
        if r < hot_on:
            hot()
        elif r < hot_on + trickle_on:
            tric()
        else:
            deep()

    def draw_off():
        if rng.random() < hot_off:
            hot()
        else:
            deep()

    for s in range(samples):
        on = (s % burst_period) == (burst_period - 1)
        draw = draw_on if on else draw_off
        end = (s + 1) * S
        while em.i < end:
            em.step(draw)
    em.close()


def gen_rocketphase(out, max_, loopfrac, dpairfrac, len_loop, len_pair, periods, rng):
    """LRU-hostile loop phase alternating with a pure pair phase at small D. The probe finds
    the band instantly; the confirm sample's density step (d_m ~ 0 => |error| huge) rockets the
    window to its cap; the returning loop phase pays the slam."""
    S = 4 * max_
    loop_n = int(loopfrac * max_)
    d_pair = max(1, int(dpairfrac * max_))
    em = Emitter(out)
    loop_i = [0]
    nxt_p = [100_000_000]

    def draw_loop():
        em.emit(loop_i[0] % loop_n)
        loop_i[0] += 1

    def draw_pair():
        k = nxt_p[0]
        nxt_p[0] += 1
        em.emit(k)
        em.schedule(k, d_pair)

    period = len_loop + len_pair
    for s in range(periods * period):
        in_pair = (s % period) >= len_loop
        draw = draw_pair if in_pair else draw_loop
        end = (s + 1) * S
        while em.i < end:
            em.step(draw)
    em.close()


def gen_barflap(out, max_, hotfrac, hotshare, ddeepfrac, lo_hits, hi_hits, dshort,
                samples, rng):
    """Steady zipf hot + uncatchable deep pairs; a floor-caught trickle dithered per sample so
    window hits land ~lo_hits (below bar) on even samples and ~hi_hits (above bar) on odd ones.
    Measures pure starvation-bar boundary churn."""
    S = 4 * max_
    hot_n = int(hotfrac * max_)
    d_deep = int(ddeepfrac * max_)
    zipf = zipf_sampler(hot_n, 0.9, rng)
    em = Emitter(out)
    nxt_t = [50_000_000]
    nxt_d = [100_000_000]

    def make_draw(p_tric):
        def draw():
            r = rng.random()
            if r < p_tric:
                k = nxt_t[0]
                nxt_t[0] += 1
                em.emit(k)
                em.schedule(k, dshort)
            elif r < p_tric + hotshare:
                em.emit(zipf())
            else:
                k = nxt_d[0]
                nxt_d[0] += 1
                em.emit(k)
                em.schedule(k, d_deep)
        return draw

    for s in range(samples):
        target = lo_hits if (s % 2 == 0) else hi_hits
        draw = make_draw(target / S)
        end = (s + 1) * S
        while em.i < end:
            em.step(draw)
    em.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["straywall2", "troughwalk", "ratchet", "rocketphase",
                                     "barflap"])
    ap.add_argument("--max", type=int, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=7)
    # straywall2 / mixture knobs
    ap.add_argument("--dfrac", type=float, default=0.40)
    ap.add_argument("--alpha", type=float, default=0.75)
    ap.add_argument("--hotfrac", type=float, default=0.75)
    ap.add_argument("--hotshare", type=float, default=0.55)
    ap.add_argument("--samples", type=int, default=64)
    # troughwalk / rocketphase knobs
    ap.add_argument("--loopfrac", type=float, default=1.4)
    ap.add_argument("--dpairfrac", type=float, default=0.35)
    ap.add_argument("--lenloop", type=int, default=10)
    ap.add_argument("--lenpair", type=int, default=3)
    ap.add_argument("--trickle", type=float, default=0.005)
    ap.add_argument("--dshort", type=int, default=150)
    ap.add_argument("--periods", type=int, default=8)
    ap.add_argument("--warm", type=int, default=2)
    # ratchet knobs
    ap.add_argument("--ddeepfrac", type=float, default=2.5)
    ap.add_argument("--burstperiod", type=int, default=2)
    ap.add_argument("--hoton", type=float, default=0.40)
    ap.add_argument("--trickleon", type=float, default=0.35)
    ap.add_argument("--hotoff", type=float, default=0.70)
    # barflap knobs
    ap.add_argument("--lohits", type=int, default=23)
    ap.add_argument("--hihits", type=int, default=42)
    ap.add_argument("--pairfirst", action="store_true",
                    help="troughwalk: start each period with the pair phase (bootstraps the "
                         "dead-base probe before any failure can inflate the ladder)")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    if args.kind == "straywall2":
        gen_straywall2(args.out, args.max, args.dfrac, args.alpha, args.hotfrac,
                       args.hotshare, args.samples, rng)
    elif args.kind == "troughwalk":
        gen_troughwalk(args.out, args.max, args.loopfrac, args.dpairfrac, args.lenloop,
                       args.lenpair, args.trickle, args.dshort, args.periods, args.warm, rng,
                       pairfirst=args.pairfirst)
    elif args.kind == "ratchet":
        gen_ratchet(args.out, args.max, args.hotfrac, args.ddeepfrac, args.dshort,
                    args.burstperiod, args.hoton, args.trickleon, args.hotoff,
                    args.samples, rng)
    elif args.kind == "rocketphase":
        gen_rocketphase(args.out, args.max, args.loopfrac, args.dpairfrac, args.lenloop,
                        args.lenpair, args.periods, rng)
    else:
        gen_barflap(args.out, args.max, args.hotfrac, args.hotshare, args.ddeepfrac,
                    args.lohits, args.hihits, args.dshort, args.samples, rng)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
