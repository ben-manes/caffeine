#!/usr/bin/env python3
"""Instrument traces for the ESC climber study (deterministic, seeded).

All modes share: uniform IRM over a universe whose SIZE is modulated per sample-length block,
so the hit rate moves for reasons the window cannot influence (flat window-curve), with the
noise shape chosen per mode. max=8192 default; block = 4*max requests = one climber sample.

  whiteflat  iid universe modulation u ~ U(-0.10, +0.10): white per-sample HR noise
             sigma ~= 1.5pp, |dHR| typically ~2pp, sub-restart (5pp).
  jam        square-wave universe alternation +-7%: |dHR| ~= 3.5pp every sample, sub-restart;
             deviation EMA ~= half the swing, so amplitude-confidence should pin at 1.
  ramp       smooth monotone drift: HR ~20% -> ~40% linearly over the trace.

Usage: escgen.py <mode> --max 8192 --samples 160 --seed 7 --out trace.lirs
"""
import argparse
import random

def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["whiteflat", "jam", "ramp"])
    p.add_argument("--max", type=int, default=8192)
    p.add_argument("--samples", type=int, default=160)
    p.add_argument("--mult", type=int, default=4, help="block length as multiple of max")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    rng = random.Random(args.seed)
    block = args.mult * args.max
    n0 = 4 * args.max  # base universe: HR ~= max/n0 = 25%

    with open(args.out, "w") as f:
        for i in range(args.samples):
            if args.mode == "whiteflat":
                universe = round(n0 * (1.0 + rng.uniform(-0.10, 0.10)))
            elif args.mode == "jam":
                universe = round(n0 * (0.93 if (i % 2 == 0) else 1.07))
            else:  # ramp: HR 20% -> 40%  =>  universe 5*max -> 2.5*max
                frac = i / max(1, args.samples - 1)
                universe = round(args.max / (0.20 + 0.20 * frac))
            for _ in range(block):
                f.write(f"{rng.randrange(universe)}\n")
    print(f"{args.out}: {args.samples} blocks x {block} = {args.samples * block} requests")

if __name__ == "__main__":
    main()
