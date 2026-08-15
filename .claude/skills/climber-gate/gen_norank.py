#!/usr/bin/env python3
"""Two cells from the 2026-08-13 LIRS family study, rowed as frontier sentinels (2026-08-15).

Neither is a trap the density machine is expected to solve; both are recorded so that a change
which moves them is measured. Their generators are copied verbatim from that study's
`gen_lirs2.py` (`repeat`) and `gen_stack.py` (`flood`), and regenerate the study's traces
bit-identically (md5 checked at the copy).

  repeat    A ring of `W` slots; each slot holds a key for exactly `R` references spaced exactly
            `W` apart, then retires it for a fresh one. Every key has the same lifetime frequency,
            so the sketch cannot rank a candidate against a victim and main fills with retired
            keys whose counts shield them. Window hits appear only once the window's residency
            covers the reuse distance: window / miss rate >= W, about 8% of an 8,192 cache at the
            1/R miss rate of the good regime but about 45% while main is that graveyard, so the
            starved corner is a self-consistent trap that only a large walk escapes. LRU is the
            ceiling ((R-1)/R, 83.29 at R=6), and the shipped machine's score is its escape time.

  flood     A hot set cycled in rotation order, plus fresh throwaway pairs at short reuse. The
            pairs hit in any window at all and are dense per slot, so the average density steers
            the window up while its marginal value is nothing past the pair spacing; the ceiling
            is a 1% window (65.39 at jrate 1.0), the density rest point 13-15pp below it. The
            audit layer walks down, confirms, parks, and density walks it back up when the park
            expires. `--prologue` plants the hot set before the stream.

Deterministic. Writes one key per line (the `lirs` trace format).

  gen_norank.py repeat --refs 6 --width 4096 --out $S/rep_r6_w4096.lirs
  gen_norank.py flood --jrate 1.0 --prologue --out $S/flood_j100.lirs
"""
import argparse
import os
import random


def write(out, keys):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as f:
        f.write("\n".join(str(k) for k in keys) + "\n")
    print(f"wrote {out}  ({len(keys):,} requests, {len(set(keys)):,} distinct)")


def repeat(a):
    """A ring of W slots, each cycling a key through exactly R references spaced W apart."""
    slots = [[0, 0] for _ in range(a.width)]
    keys, nxt = [], 0
    for i in range(a.n):
        slot = slots[i % a.width]
        if slot[1] == 0:
            slot[0] = nxt
            slot[1] = a.refs
            nxt += 1
        slot[1] -= 1
        keys.append(slot[0])
    write(a.out, keys)


def flood(a):
    """Hot rotation + throwaway pairs at short reuse."""
    out, pending = [], {}
    hot = list(range(a.hot))
    if a.shuffle_hot:
        random.Random(a.seed).shuffle(hot)
    if a.prologue:
        out.extend(hot)
    nxt = a.hot
    hot_i = 0
    credit = 0.0
    while len(out) < a.n:
        pos = len(out)
        if pos in pending:
            out.append(pending.pop(pos))
            continue
        if credit >= 1.0:
            credit -= 1.0
            out.append(nxt)
            q = pos + a.spread
            while q in pending:
                q += 1
            pending[q] = nxt
            nxt += 1
        else:
            out.append(hot[hot_i % a.hot])
            hot_i += 1
            credit += a.jrate
    write(a.out, out[:a.n])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("mode", choices=["repeat", "flood"])
    p.add_argument("--out", required=True)
    p.add_argument("--n", type=int, default=3_000_000)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--refs", type=int, default=6, help="repeat: references per key")
    p.add_argument("--width", type=int, default=2048, help="repeat: reuse distance and live keys")
    p.add_argument("--hot", type=int, default=8000, help="flood: hot set size")
    p.add_argument("--jrate", type=float, default=1.5,
                   help="flood: throwaway pairs per hot request")
    p.add_argument("--spread", type=int, default=4, help="flood: requests inside a throwaway pair")
    p.add_argument("--shuffle-hot", action="store_true", help="flood: break the rotation order")
    p.add_argument("--prologue", action="store_true", help="flood: plant the hot set first")
    a = p.parse_args()
    {"repeat": repeat, "flood": flood}[a.mode](a)


if __name__ == "__main__":
    main()
