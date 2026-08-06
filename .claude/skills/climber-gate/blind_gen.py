#!/usr/bin/env python3
"""Blind-review attack traces (independent adversarial pass). Deterministic, seed 41.

blind_bandtrap   crash-guarded absorbing band: uniform flat hot (avg-density winner)
                 + a pair band whose onset sits past the decayed rung-16/32 reach zone's
                 verdict point and whose capture is hit-rate-positive but density-negative;
                 hot sized so rung-64 committed walks crash (ungated crash precedes the
                 deep-confirm) before hot can leak into the window and confirm.
blind_balloonflip  pure-hot phases drive probe-confirm + epsilon-balloon to ~80% window;
                 loop-over-max phases punish the balloon at recovery cadence.
blind_scrambler  per-sample +/-18pp exogenous HR square wave neuters the walk's internal
                 bold-driver (reversal-through-base exit is commitment-ungated), over a
                 density-inverted band; adjudication-FAIL is the desync backstop.
blind_nullchurn  uniform random, HR ~2.5% < 4.88% equilibrium-starvation bound: floor
                 window is starved at perfect density equilibrium -> perpetual probing.

Format: one decimal key per line (.lirs). Usage: blind_gen.py <outdir>
"""
import random
import sys

C = 8192
SAMPLE = 4 * C  # 32768


def bandtrap(path, seed=41):
    """hot 4200 uniform @ .55 draw; pairs .10 draw spaced 6500 exact; cold rest.
    Realized: sec .0909, hot .50, first .0909, cold .318. Floor HR .50, band HR .59.
    Onset ~ 6500*.50 = 3250; hot ceiling W ~ 3910; LRU catches both (~59)."""
    rng = random.Random(seed)
    total = 62 * SAMPLE
    pending = {}
    nxt_pair = 100_000_000
    nxt_cold = 500_000_000
    with open(path, "w") as f:
        for i in range(total):
            k = pending.pop(i, None)
            if k is None:
                u = rng.random()
                if u < 0.55:
                    k = rng.randrange(4200)
                elif u < 0.65:
                    k = nxt_pair
                    nxt_pair += 1
                    pending[i + 6500] = k
                else:
                    k = nxt_cold
                    nxt_cold += 1
            f.write(f"{k}\n")
    print(f"{path}: {total} events")


def bandtrap2(path, seed=43):
    """v2: cold flood cut so LRU holds hot+pairs. hot 3600 @ .76 draw, pairs .12
    (spacing 6500), cold .12. Realized: hot .679, first/sec .107, cold .107.
    Floor HR .679 (hot only); LRU ~ .74 (catches pairs, hot tail loss ~6%).
    Band latch ~[2087, 4510]; adjudication density-inverted (wd ~1.35 vs md ~4.0);
    rung-64 stride enters the band profitably at s1 but commit-10 forces the walk
    into the hot-displacement crash zone. 130 samples to exercise rung-64 rounds."""
    rng = random.Random(seed)
    total = 130 * SAMPLE
    pending = {}
    nxt_pair = 100_000_000
    nxt_cold = 500_000_000
    with open(path, "w") as f:
        for i in range(total):
            k = pending.pop(i, None)
            if k is None:
                u = rng.random()
                if u < 0.76:
                    k = rng.randrange(3600)
                elif u < 0.88:
                    k = nxt_pair
                    nxt_pair += 1
                    pending[i + 6500] = k
                else:
                    k = nxt_cold
                    nxt_cold += 1
            f.write(f"{k}\n")
    print(f"{path}: {total} events")


def balloonflip(path, seed=41):
    """A(9 samples): uniform hot 600 only. B(11 samples): sequential loop 9830 keys.
    A B A B A B A = 69 samples. Loop cursor persists across B phases."""
    rng = random.Random(seed)
    phases = [("A", 9), ("B", 11), ("A", 9), ("B", 11), ("A", 9), ("B", 11), ("A", 9)]
    cursor = 0
    n = 0
    with open(path, "w") as f:
        for kind, samples in phases:
            for _ in range(samples * SAMPLE):
                if kind == "A":
                    k = 1_000_000 + rng.randrange(600)
                else:
                    k = cursor
                    cursor = (cursor + 1) % 9830
                f.write(f"{k}\n")
                n += 1
    print(f"{path}: {n} events")


def scrambler(path, seed=41):
    """Sample-aligned X/Y block toggle: X hot .68 / first .08 / cold .24,
    Y hot .48 / first .08 / cold .44; hot 3800 uniform; pairs spaced 6500.
    Floor HR alternates ~63/45 -> |dHR| ~ 18pp per sample."""
    rng = random.Random(seed)
    total = 62 * SAMPLE
    pending = {}
    nxt_pair = 100_000_000
    nxt_cold = 500_000_000
    with open(path, "w") as f:
        for i in range(total):
            k = pending.pop(i, None)
            if k is None:
                x_block = (i // SAMPLE) % 2 == 0
                hot_p = 0.68 if x_block else 0.48
                u = rng.random()
                if u < hot_p:
                    k = rng.randrange(3800)
                elif u < hot_p + 0.08:
                    k = nxt_pair
                    nxt_pair += 1
                    pending[i + 6500] = k
                else:
                    k = nxt_cold
                    nxt_cold += 1
            f.write(f"{k}\n")
    print(f"{path}: {total} events")


def nullchurn(path, seed=41):
    """Uniform over 40*C keys: HR ~ 2.5% everywhere, density-equilibrium at any split.
    Floor window is starved at equilibrium (HR < 4.88% bound) -> perpetual probes."""
    rng = random.Random(seed)
    total = 50 * SAMPLE
    with open(path, "w") as f:
        for _ in range(total):
            f.write(f"{rng.randrange(40 * C)}\n")
    print(f"{path}: {total} events")


if __name__ == "__main__":
    out = sys.argv[1]
    if len(sys.argv) > 2 and sys.argv[2] == "v2":
        bandtrap2(f"{out}/blind_bandtrap2.lirs")
    else:
        bandtrap(f"{out}/blind_bandtrap.lirs")
        balloonflip(f"{out}/blind_balloonflip.lirs")
        scrambler(f"{out}/blind_scrambler.lirs")
        nullchurn(f"{out}/blind_nullchurn.lirs")
