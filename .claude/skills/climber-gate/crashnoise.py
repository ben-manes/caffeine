#!/usr/bin/env python3
"""whisper + a window-irrelevant hit-rate modulation with the AMPLITUDE as the dose.

Sibling of the committed `whisper_mod.py` — same base trace, same hot-vs-one-shot-noise
trade — with the per-sample wave pre-generated, mean-centred, and RMS-normalised, so every
arm shares a mean hit rate and a variance and differs only in the amplitude under test. The
modulation is window-irrelevant by construction (hot keys are resident in main and always
hit; one-shot noise always misses; the reuse band the escape must find is untouched), so a
static-window sweep must be flat across arms — assert that before reading any climber number.

The gate rows are the adv3 F1 instrument, now watching its open AUDIT half: the walk-interior
exits (crash abort, bold-driver reversal) test per-sample hit-rate moves against the
walk-interior bar, which is deviation-priced for starvation probes (the shipped adv3 fix) but
absolute 5pp for audit walks. No starvation probe arms on this whisper base, so the ladder
isolates the audit side and is a threshold, not a gradient — nothing moves while the scatter
sits under the audit's bar (a06), the cell falls below LRU where it crosses (a12: audits arm
and die to `auditCrash` before adjudication), and a deeper dose adds nothing (the audits are
already dead).

  --shape sine     : the gate arms (deterministic, spreads <= 0.03)
  --shape flat     : amp ignored; the dose-matched zero control
  --shape shuffle  : sine's marginal law drawn i.i.d. per sample — kept for completeness,
                     NOT evidence: the arm is bistable at N=8 and its sign flips across
                     noise seeds and scales (the mixture family's documented basin lottery);
                     adv3's autocorrelation sub-claim built on it was retracted. Amplitude
                     against the absolute bar is the dose; shape is not.

Usage: crashnoise.py --shape sine --amp 0.12 --period 12 --out path.lirs
"""
import argparse, math, random, sys

N = 4_000_000
HOT_BASE, HOT_N = 1_000_000, 3000
BAND_BASE, BAND_D = 5_000_000, 3000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 9_000_000
W_HOT, W_BAND, W_WHISP = 0.631, 0.1434, 0.0038
SAMPLE = 4 * 8192


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--amp", type=float, default=0.08)
    ap.add_argument("--period", type=float, default=12.0, help="sine period, in samples")
    ap.add_argument("--shape", default="sine", choices=["sine", "shuffle", "flat"])
    ap.add_argument("--seed", type=int, default=None,
                    help="varies the shuffle shape only; sine and flat are deterministic")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if (a.seed is not None) and (a.shape != "shuffle"):
        # sine and flat never read the seed, so a seeded sweep over them would compare eight
        # byte-identical traces and mistake the basin draw for evidence
        sys.exit(f"--seed only varies --shape shuffle; {a.shape} is deterministic")

    rnd = random.Random(20260726)          # identical stream to whisper_mod.py
    nrnd = random.Random(90210 + (7 if a.seed is None else a.seed))
    pending = {}
    band_id = whisp_id = noise_id = 0
    out = []
    period_req = a.period * SAMPLE

    def schedule(pos, key):
        while pos in pending:
            pos += 1
        pending[pos] = key

    # Pre-generate the per-sample wave, then mean-centre and variance-match it, so the arms
    # differ ONLY in amplitude: same mean hot share (same base hit rate), same variance
    # (same rateDeviationEma), same marginal family.
    nsamples = N // SAMPLE + 2
    if a.shape == "sine":
        seq = [math.sin(2.0 * math.pi * ((s * SAMPLE % period_req) / period_req))
               for s in range(nsamples)]
    elif a.shape == "shuffle":
        seq = [math.sin(2.0 * math.pi * nrnd.random()) for _ in range(nsamples)]
    else:
        seq = [0.0] * nsamples
    if a.shape != "flat":
        mean = sum(seq) / len(seq)
        seq = [v - mean for v in seq]
        rms = math.sqrt(sum(v * v for v in seq) / len(seq))
        seq = [v / rms * math.sqrt(0.5) for v in seq]   # sine's RMS

    cap = 1.0 - W_BAND - W_WHISP
    for pos in range(N):
        k = pending.pop(pos, None)
        if k is not None:
            out.append(k)
            continue
        wave = seq[pos // SAMPLE]
        w_hot = max(0.0, min(cap, W_HOT + a.amp * wave))
        r = rnd.random()
        if r < w_hot:
            out.append(HOT_BASE + rnd.randrange(HOT_N))
        elif r < w_hot + W_BAND:
            k = BAND_BASE + band_id
            band_id += 1
            out.append(k)
            schedule(pos + BAND_D, k)
        elif r < w_hot + W_BAND + W_WHISP:
            k = WHISP_BASE + whisp_id
            whisp_id += 1
            out.append(k)
            schedule(pos + WHISP_D, k)
        else:
            out.append(NOISE_BASE + noise_id)
            noise_id += 1

    with open(a.out, "w") as f:
        f.write("\n".join(map(str, out)))
        f.write("\n")
    print(f"{a.out}: {len(out)} requests, band={band_id} whisper={whisp_id} "
          f"noise={noise_id} shape={a.shape} amp={a.amp}")


if __name__ == "__main__":
    main()
