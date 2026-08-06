#!/usr/bin/env python3
"""A1: WINDOW COMMANDEERING -- a two-tenant trace for tenant-isolation attacks.

VICTIM   (keys 1_000_000 + [0, V))  Zipf(alpha) over V = vfrac * max distinct keys.
                                    Frequency-shaped: wants a small window / big main.
ATTACKER (keys 50_000_000 + ...)    window-shaped traffic whose hits land in the window.
                                    mode=pair : each key touched exactly twice, distance D
                                                requests apart (so attacker hits <= N_att/2).
                                    mode=loop : cycles a fixed set of L keys (hits unbounded).

Tenant split for the harness accounting is 20_000_000: victim keys are below it, attacker
keys above it, so `-Dcaffeine.simulator.tenantSplit=20000000` yields exact per-tenant
hit/miss counts for every policy.

The attack thesis: `WindowClimber.densityClimb` steers on ln(windowDensity/mainDensity),
which is *per-slot earnings*, not total hit rate.  A tenant that concentrates its hits into
the small window out-earns a victim whose hits are diluted across the whole main region, so
capacity flows to the attacker.  Worse, once the window passes the victim's working-set
cliff the victim's own main hits collapse, which RAISES the error further -- a positive
feedback the attacker only has to trigger once.

Usage:
  a1_tenant.py --max 8192 --share 0.05 --dist 400 --out t.lirs
  a1_tenant.py --max 8192 --share 0.05 --mode loop --loop 2000 --out t.lirs
"""
import argparse
import random

VICTIM_BASE = 1_000_000
ATTACK_BASE = 50_000_000
TENANT_SPLIT = 20_000_000


def zipf_cum(n, alpha):
    w = [1.0 / ((i + 1) ** alpha) for i in range(n)]
    tot = sum(w)
    cum, acc = [], 0.0
    for x in w:
        acc += x / tot
        cum.append(acc)
    return cum


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, required=True)
    ap.add_argument("--share", type=float, required=True, help="attacker share of requests")
    ap.add_argument("--dist", type=int, default=400, help="attacker reuse distance (requests)")
    ap.add_argument("--mode", default="pair", choices=("pair", "loop", "spread"))
    ap.add_argument("--dmin", type=int, default=64, help="spread mode: min reuse distance")
    ap.add_argument("--dmax", type=int, default=0, help="spread mode: max reuse distance "
                                                        "(default 3*max)")
    ap.add_argument("--loop", type=int, default=0, help="loop mode working-set size")
    ap.add_argument("--vfrac", type=float, default=1.0, help="victim keys / max")
    ap.add_argument("--alpha", type=float, default=0.85)
    ap.add_argument("--victim", default="zipf", choices=("zipf", "loop", "loopmix"))
    ap.add_argument("--loopfrac", type=float, default=0.9,
                    help="loop victim: working set / max (a cliff at window = max - loopset)")
    ap.add_argument("--hotfrac", type=float, default=0.10,
                    help="loopmix: share of victim requests going to a small Zipf hot core")
    ap.add_argument("--len", type=int, default=200, help="trace length as a multiple of max")
    ap.add_argument("--burst", default=None,
                    help="A:B -- emit attacker traffic only in samples [A,B) (sample = 4*max "
                         "requests).  Tests whether a TRANSIENT impulse captures the window "
                         "permanently: err(w) is bistable, so a nudge past the unstable root "
                         "is carried the rest of the way by the density arm alone.")
    ap.add_argument("--seed", type=int, default=20260726)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    rnd = random.Random(a.seed)
    n = a.len * a.max
    vkeys = max(1, int(a.vfrac * a.max))
    cum = zipf_cum(vkeys, a.alpha)
    pop = list(range(vkeys))

    # attacker primary-draw probability: in pair mode half of its requests are scheduled
    # replays, so the primary draw rate is share/2; in loop mode every request is a draw.
    p_att = a.share if a.mode == "loop" else a.share / 2.0
    dmax = a.dmax or (3 * a.max)
    import math
    lo, hi = math.log(a.dmin), math.log(dmax)
    loop_n = a.loop or max(1, int(0.25 * a.max))

    sample = 4 * a.max
    burst = tuple(int(x) for x in a.burst.split(":")) if a.burst else None
    pending = {}
    out = []
    aid = 0
    lpos = 0
    vpos = 0
    loopset = max(1, int(a.loopfrac * a.max))
    # victim draws are batched for speed
    vbuf, vi = [], 0
    for pos in range(n):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        if (burst is None or burst[0] <= (pos // sample) < burst[1]) and rnd.random() < p_att:
            if a.mode in ("pair", "spread"):
                k = ATTACK_BASE + aid
                aid += 1
                out.append(k)
                # spread: log-uniform reuse distance.  Its hit count then grows ~linearly with
                # the window's residence time, i.e. ~linearly with the window size, so the
                # attacker's *window density* (hits/slot) is ~scale-free.  That removes the
                # 1/w self-limiting term that would otherwise stop the density arm, handing
                # the sign of ln(wd/md) entirely to the victim's collapsing main density.
                d = a.dist if a.mode == "pair" else int(math.exp(rnd.uniform(lo, hi)))
                q = pos + d
                while q in pending:
                    q += 1
                pending[q] = k
            else:
                out.append(ATTACK_BASE + (lpos % loop_n))
                lpos += 1
            continue
        if a.victim != "zipf":
            # A cyclic loop over loopfrac*max keys: its hit rate falls off a cliff the moment
            # main can no longer hold the whole set (window > max - loopset).  Its main density
            # is only moderate (one hit per key per cycle) so the density arm sees a cheap
            # region -- yet the marginal value of that capacity is enormous.
            if a.victim == "loopmix" and rnd.random() < a.hotfrac:
                if vi >= len(vbuf):
                    vbuf = random.Random(rnd.random()).choices(pop, cum_weights=cum, k=200_000)
                    vi = 0
                out.append(VICTIM_BASE + 900_000 + vbuf[vi])
                vi += 1
            else:
                out.append(VICTIM_BASE + (vpos % loopset))
                vpos += 1
            continue
        if vi >= len(vbuf):
            vbuf = random.Random(rnd.random()).choices(pop, cum_weights=cum, k=200_000)
            vi = 0
        out.append(VICTIM_BASE + vbuf[vi])
        vi += 1

    with open(a.out, "w") as f:
        f.write("\n".join(map(str, out)))
        f.write("\n")
    nat = sum(1 for k in out if k >= TENANT_SPLIT)
    print(f"{a.out}: n={len(out)} attacker={nat} ({100.0*nat/len(out):.3f}%) "
          f"victimKeys={vkeys} dist={a.dist} mode={a.mode}")


if __name__ == "__main__":
    main()
