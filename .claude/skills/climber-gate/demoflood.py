#!/usr/bin/env python3
"""Demoflood: the constructive proof that the probe verdict's probation baseline must be
frozen at arm time (fifth-form study, 2026-07-26).

Geometry: a hot set sized to SATURATE the protected region, so any up-walk's protected
shrink demotes still-hot entries into probation where they keep earning — live probation
density spikes to ~11.8/slot mid-walk, and a verdict judged against the LIVE rate is an
absorbing false-veto (pins at the floor with zero confirms), while the frozen baseline
escapes. Plus a reuse band and one-shot noise keeping the floor window starved.

Note: --band_d is EVENT distance; the window-stack reuse distance is ~(band+noise
fraction)·band_d because hot traffic hits in main without entering the window, so the
escape settles near ~22% of C rather than at band_d. The enrichment geometry — the row's
purpose — is unaffected.

Deterministic; LIRS format (one key per line).
"""
import argparse, random

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=8192)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--lengthmult", type=int, default=80, help="samples of 4*max events")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rng = random.Random(a.seed)

    m = a.max
    protected_cap = int(0.8 * 0.99 * m)          # ~6488 @8192
    hot_n = int(0.95 * protected_cap)            # saturates protected (~6160)
    band_d = int(0.35 * m)                       # re-touch depth: multi-stride walk (~2867)
    total = a.lengthmult * 4 * m                 # events (~2.6M @8192 default)

    hot = list(range(1_000_000, 1_000_000 + hot_n))
    band_next = 5_000_000                        # band key counter
    noise_next = 9_000_000
    pending = []                                 # (emit_at_index, key) re-touches
    out = open(a.out, "w")
    hi = 0
    emitted = 0
    while emitted < total:
        r = rng.random()
        # due re-touch first (approximates reuse distance in event terms)
        if pending and pending[0][0] <= emitted:
            _, k = pending.pop(0)
            out.write(f"{k}\n")
        elif r < 0.70:                           # hot core: sequential scan keeps all sketch-hot
            out.write(f"{hot[hi % hot_n]}\n")
            hi += 1
        elif r < 0.90:                           # band: first touch now, re-touch band_d later
            band_next += 1
            out.write(f"{band_next}\n")
            pending.append((emitted + band_d, band_next))
        else:                                    # one-shot noise
            noise_next += 1
            out.write(f"{noise_next}\n")
        emitted += 1
    out.close()
    print(f"wrote {a.out}: {emitted} events, hot={hot_n}, band_d={band_d}")

if __name__ == "__main__":
    main()
