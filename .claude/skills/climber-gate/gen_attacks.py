#!/usr/bin/env python3
"""Cycle-1 attack-trace generators (ledger A1/A2/A6/A8/A9). Deterministic per seed.

Formats:
  .lirs      one decimal key per line (object traps)
  .lcs.csv   libcachesim csv: header + rows with weight at col[3], key at col[4] (weighted)

Usage: gen_attacks.py <outdir> [--seeds 7,11,13]
Generates the standard battery; each generator documents its mechanism + intent.
"""
import argparse, math, os, random

# ---------- helpers ----------

def zipf_sampler(n, s, rng):
    """Bounded Zipf via inverse CDF table (n items, exponent s)."""
    weights = [1.0 / (i + 1) ** s for i in range(n)]
    total = sum(weights)
    cdf, acc = [], 0.0
    for w in weights:
        acc += w / total
        cdf.append(acc)
    import bisect
    def sample():
        return bisect.bisect_left(cdf, rng.random())
    return sample


def write_lirs(path, events):
    with open(path, "w") as f:
        f.write("\n".join(str(k) for k in events))
    print(f"{path}: {len(events)} events, {len(set(events))} distinct")


def write_lcs(path, events_weights):
    with open(path, "w") as f:
        f.write("clock_time,ignore1,ignore2,obj_size,obj_id\n")
        for i, (k, w) in enumerate(events_weights):
            f.write(f"{i},0,0,{w},{k}\n")
    print(f"{path}: {len(events_weights)} events, {len(set(k for k, _ in events_weights))} distinct")


# ---------- A8: cadence resonance + zigzag ----------

def gen_resphase(c, k_periods, length_mult, seed):
    """Two working sets alternate with phase length = k * 4C requests (sample-aligned).
    Attack: every sample sees a coherent phase, so density looks stable within-sample
    while cross-phase the optimal window flips — cadence-locked widepin generalization."""
    rng = random.Random(seed)
    phase_len = k_periods * 4 * c
    total = length_mult * 8 * c * k_periods
    set_a = list(range(0, int(0.9 * c)))
    set_b = list(range(10 * c, 10 * c + int(0.9 * c)))
    events = []
    for i in range(total):
        active = set_a if (i // phase_len) % 2 == 0 else set_b
        events.append(active[rng.randrange(len(active))])
    return events


def gen_rungflip(c, seed):
    """Phase flips at refractory-rung cadence: 32 samples, then 64 samples, repeating.
    Attack: align workload shifts with the ladder's re-arm boundaries so probes launch
    into a just-flipped regime and always fail — tries to lock the ladder at 64."""
    rng = random.Random(seed)
    sample = 4 * c
    seq = []
    set_a = list(range(0, int(0.9 * c)))
    set_b = list(range(10 * c, 10 * c + int(0.9 * c)))
    active, toggle = set_a, [32, 64]
    ti = 0
    while len(seq) < 60 * 64 * sample // 32:
        for _ in range(toggle[ti % 2] * sample):
            seq.append(active[rng.randrange(len(active))])
        active = set_b if active is set_a else set_a
        ti += 1
        if len(seq) > 6_000_000:
            break
    return seq


def gen_zigzag(c, seed):
    """Working set drifts as a sawtooth across the key space (Ben's quirky case):
    window-worthy recency at the moving edge, frequency value decaying behind it."""
    rng = random.Random(seed)
    total = 40 * 4 * c
    span = 3 * c
    events = []
    for i in range(total):
        t = i / total * 8 * math.pi
        center = int((span / 2) * (1 + math.sin(t)))
        events.append(center + rng.randrange(int(0.4 * c)))
    return events


# ---------- A6: low-HR trap ----------

def gen_lowmix(c, seed):
    """LRU base ~3%: a huge cold universe + a thin pair-band at spacing 0.25C.
    The crash veto (base-5pp) is structurally dead below 5% HR; only budget/reversal
    bound walk damage. Attack: measure walk-round dips against the LRU anchor."""
    rng = random.Random(seed)
    total = 50 * 4 * c
    cold_universe = 40 * c
    spacing = c // 4
    events = []
    pending = {}
    next_pair_key = 100_000_000
    for i in range(total):
        if i in pending:
            events.append(pending.pop(i))
        elif rng.random() < 0.06:
            k = next_pair_key
            next_pair_key += 1
            events.append(k)
            pending[i + spacing + rng.randrange(spacing // 4)] = k
        else:
            events.append(rng.randrange(cold_universe))
    return events


# ---------- A9: density-equilibrium spoof ----------

def gen_trickle(c, seed):
    """Hot set 0.3C earns everything; a rotating twice-touched trickle at spacing ~0.45C
    is tuned so window density matches main density near W≈C/2 — the untested
    density-bias corollary: can worthless strays hold the window at 50%?"""
    rng = random.Random(seed)
    total = 50 * 4 * c
    hot = int(0.3 * c)
    spacing = int(0.45 * c)
    events = []
    pending = {}
    nxt = 200_000_000
    hot_sample = zipf_sampler(hot, 0.8, rng)
    for i in range(total):
        if i in pending:
            events.append(pending.pop(i))
        elif rng.random() < 0.30:
            k = nxt
            nxt += 1
            events.append(k)
            pending[i + spacing + rng.randrange(max(1, spacing // 8))] = k
        else:
            events.append(hot_sample())
    return events


# ---------- A1/A2: weighted ----------

def gen_wmixture(c_entries, d_frac, seed):
    """gen.py mixture with lognormal weights (mean ~4KB): Zipf hot 0.6C + pairs at
    spacing D. Capacity is weight-denominated; same pattern as the object trap.
    Returns (events_weights, byte_capacity)."""
    rng = random.Random(seed)
    weights = {}
    def w(k):
        if k not in weights:
            weights[k] = max(64, int(rng.lognormvariate(math.log(4096), 0.7)))
        return weights[k]
    hot = int(0.6 * c_entries)
    spacing = max(1, int(d_frac * c_entries))
    total = 48 * 4 * c_entries
    hot_sample = zipf_sampler(hot, 0.9, rng)
    events = []
    pending = {}
    nxt = 300_000_000
    for i in range(total):
        if i in pending:
            k = pending.pop(i)
        elif rng.random() < 0.25:
            k = nxt
            nxt += 1
            pending[i + spacing] = k
        else:
            k = hot_sample()
        events.append((k, w(k)))
    mean_w = sum(weights.values()) / max(1, len(weights))
    return events, int(c_entries * mean_w)


def gen_wfewentries(seed):
    """~200 resident entries of 25-100MB in a 10GB cache. The window floor (2% = 200MB)
    can be smaller than one entry; bar floor 4 vs tiny per-region hit counts; corner
    geometry in weight units. Never tested before (methodology F16 #2)."""
    rng = random.Random(seed)
    universe = 2000
    weights = {k: rng.randrange(25_000_000, 100_000_000) for k in range(universe)}
    total = 400_000
    events = []
    hot_sample = zipf_sampler(universe, 1.05, rng)
    for i in range(total):
        phase = (i // 50_000) % 2
        k = hot_sample() if phase == 0 else (universe - 1 - hot_sample())
        events.append((k, weights[k]))
    return events, 10_000_000_000


# ---------- main ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir")
    ap.add_argument("--seeds", default="7,11,13")
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)
    seeds = [int(s) for s in args.seeds.split(",")]
    o = args.outdir
    for seed in seeds:
        sfx = f"_s{seed}"
        write_lirs(f"{o}/resphase_8192_k1{sfx}.lirs", gen_resphase(8192, 1, 4, seed))
        write_lirs(f"{o}/resphase_8192_k2{sfx}.lirs", gen_resphase(8192, 2, 3, seed))
        write_lirs(f"{o}/rungflip_8192{sfx}.lirs", gen_rungflip(8192, seed))
        write_lirs(f"{o}/zigzag_8192{sfx}.lirs", gen_zigzag(8192, seed))
        write_lirs(f"{o}/lowmix_16384{sfx}.lirs", gen_lowmix(16384, seed))
        write_lirs(f"{o}/trickle_8192{sfx}.lirs", gen_trickle(8192, seed))
        ew, cap = gen_wmixture(8192, 0.10, seed)
        write_lcs(f"{o}/wmixture_d010{sfx}.lcs.csv", ew)
        print(f"  wmixture_d010{sfx} byte capacity: {cap}")
        ew, cap = gen_wfewentries(seed)
        write_lcs(f"{o}/wfew{sfx}.lcs.csv", ew)
        print(f"  wfew{sfx} byte capacity: {cap}")


if __name__ == "__main__":
    main()


def gen_shifter(c, seed):
    """Teaching showcase: three regimes with DIFFERENT optimal windows and real
    hit-rate consequences at large C. Regime A: Zipf hot set ~0.9C (frequency;
    optimal window ~0) — regime B: hot 0.3C + pair band at 0.45C (recency band;
    optimal window large) — back to regime A. Tests adapt, re-adapt, and both
    directions of a bad start."""
    rng = random.Random(seed)
    third = 55 * 4 * c
    hot = zipf_sampler(int(0.9 * c), 1.0, rng)
    hot_small = zipf_sampler(int(0.3 * c), 0.8, rng)
    events = []
    for i in range(third):
        events.append(hot())
    pending = {}
    nxt = 500_000_000
    spacing = int(0.45 * c)
    for i in range(third, 2 * third):
        if i in pending:
            events.append(pending.pop(i))
        elif rng.random() < 0.30:
            k = nxt
            nxt += 1
            events.append(k)
            pending[i + spacing + rng.randrange(max(1, spacing // 8))] = k
        else:
            events.append(400_000_000 + hot_small())
    for i in range(third):
        events.append(hot())
    return events
