#!/usr/bin/env python3
"""Run the climber-gate battery and write a per-cell CSV.

The runnable form of SKILL.md's gate table — update both together. Cells reference the
trace filenames the SKILL.md generation block produces; missing traces are skipped with
a notice, so a partial regeneration still yields a usable sweep. Runs-per-cell follows
the table (N=8 on the bimodal families). The tree defaults to this repository; set
CAF_TREE for an experiment worktree, where `--variants` beyond `hybrid` need the
skill's harness (`harness.py apply <worktree>`).

Usage:
  gate.py <traces-dir> [variants] [out.csv]     # variants default: hybrid
  gate.py <traces-dir> hybrid,reactive out.csv  # refresh the reactive anchor column

`hybrid` alone is right for verifying a change. `hybrid,reactive` refreshes the table's
reactive anchor — the "is the density machine earning its complexity here" column, which
these density-built families otherwise hide behind an LRU comparison. That anchor is a
property of the trace instance, so it is measured on a regeneration or a shared-state
change, not on every run. See SKILL.md's Harness section.
"""
import csv, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402

# (label, filename, size, runs) — bimodal families get more runs
CELLS = [
    ("mixture_d010", "mixture_8192_d010.lirs", 8192, 2),
    ("mixture_d025", "mixture_8192_d025.lirs", 8192, 6),
    ("mixture_d025@32k", "mixture_32768_d025.lirs", 32768, 2),
    ("mixture_d050", "mixture_8192_d050.lirs", 8192, 2),
    ("mixture_d050_long", "mixture_8192_d050_long.lirs", 8192, 2),
    # The --lengthmult 640 steady-state companions (2026-08-05): the short parents price warmup
    ("mixture_d010_long", "mixture_8192_d010_long.lirs", 8192, 2),
    ("mixture_d025_long", "mixture_8192_d025_long.lirs", 8192, 2),
    ("mixture_d025@32k_long", "mixture_32768_d025_long.lirs", 32768, 2),
    ("phases_d050", "phases_8192_d050.lirs", 8192, 8),
    ("phases_d050@32k", "phases_32768_d050.lirs", 32768, 5),
    ("deadphase", "deadphase_8192.lirs", 8192, 2),
    ("widepin", "widepin_8192.lirs", 8192, 8),
    ("straywall2", "straywall2_8192_d050.lirs", 8192, 4),
    ("straywall2@16k", "straywall2_16384_d050.lirs", 16384, 2),
    ("demoflood", "demoflood_8192.lirs", 8192, 2),
    ("trickle_s7", "trickle_8192_s7.lirs", 8192, 2),
    ("trickle_s11", "trickle_8192_s11.lirs", 8192, 2),
    ("lowmix_s7", "lowmix_16384_s7.lirs", 16384, 4),
    ("bandtrap2", "blind_bandtrap2.lirs", 8192, 2),
    ("balloonflip", "blind_balloonflip.lirs", 8192, 8),
    ("whisper", "attack_whisper.lirs", 8192, 2),
    ("whisper_quarter", "attack_whisper_quarter.lirs", 8192, 2),
    ("whisper_mod_p6", "whisper_a0.08_p6.lirs", 8192, 2),
    ("whisper_mod_p12", "whisper_a0.08_p12.lirs", 8192, 2),
    ("whisper_mod_a12", "whisper_a0.12_p12.lirs", 8192, 2),
    ("mixmod_a010", "mixmod_32768_a010.lirs", 32768, 2),
    ("metronome", "metronome_8192_r1.lirs", 8192, 2),
    ("regimeramp", "regimeramp_8192.lirs", 8192, 3),
    ("esc_jam", "esc_jam.lirs", 8192, 2),
    ("tenant_s10", "tenant_s10.lirs", 8192, 2),
    ("zigzag_s7", "zigzag_8192_s7.lirs", 8192, 2),
    ("rungflip_s7", "rungflip_8192_s7.lirs", 8192, 2),
    ("resphase_k1_s7", "resphase_8192_k1_s7.lirs", 8192, 2),
    ("posjam_flat", "posjam_flat_8192.lirs", 8192, 2),
    ("posjam_j50", "jam_j50.lirs", 8192, 2),
    ("posjam_d25", "jam_d25.lirs", 8192, 2),
    ("posjam_d0", "jam_d0.lirs", 8192, 2),
    ("shieldtrap_s7", "shieldtrap_8192.lirs", 8192, 8),
    ("shieldtrap_s11", "shieldtrap_s11.lirs", 8192, 8),
    ("shieldtrap_s13", "shieldtrap_s13.lirs", 8192, 8),
    ("saw_p40", "saw_p40.lirs", 8192, 5),
    ("loopcliff", "loopcliff_8192.lirs", 8192, 2),
    ("climbtrend_up", "climbtrend_8192.lirs", 8192, 3),
    ("climbtrend_dn", "climbtrend_dn_8192.lirs", 8192, 3),
    ("crashnoise_flat", "cn_flat.lirs", 8192, 2),
    ("crashnoise_a12", "cn_sine_a12.lirs", 8192, 2),
    ("mixnoise_flat", "mn8_flat.lirs", 8192, 2),
    ("mixnoise_a10", "mn8_sine_a10.lirs", 8192, 2),
    ("h4c1_attack", "h4c1_p4_attack_long.lirs", 8192, 2),
    ("h4c1_reverse", "h4c1_p4_control_long.lirs", 8192, 2),
    ("moat_h7800", "moat_h7800_long.lirs", 8192, 2),
    ("moat_h3000", "moat_h3000_b4000.lirs", 8192, 2),
    # the shallow doses are where the guard rail is the sole recovery mechanism
    ("moat_h4000", "moat_h4000_b4000.lirs", 8192, 4),
    ("moat_h5000", "moat_h5000_b4000.lirs", 8192, 4),
    # The slowswap pair is bimodal on which of two audit defects binds, so an unseeded mean here
    # is uninterpretable. These rows only sanity-check the ramp control; adjudicate the step arm
    # with `seedrun`-style seeded runs (SKILL.md's row), never from this CSV.
    ("slowswap_ramp", "slowswap_r20.lirs", 8192, 2),
    ("slowswap_step", "slowswap_r1.lirs", 8192, 4),
    # nullchurn is the battery's only sub-5% cell, so it is where the audit bar's proportional
    # cap binds hardest — the regime the 2026-08-04 change alters most.
    ("nullchurn", "blind_nullchurn.lirs", 8192, 2),
    # One terrain, two arms: the rider is window-irrelevant (the anchors match), so the only
    # difference is whether hasBlindCorner() fires. The sighted arm is the control.
    ("blindlock_blind", "blindlock_blind.lirs", 8192, 2),
    ("blindlock_sighted", "blindlock_sighted.lirs", 8192, 2),
    # The D2 tier-cliff straddle: one trace, both sides of the boundary; a fix must move the
    # pair together. The corda+5xloop+corda ladder row stays outside this table (multi-path).
    ("strad_p8@4096", "strad_p8.lirs", 4096, 2),
    ("strad_p8@4097", "strad_p8.lirs", 4097, 2),
    # Frontier sentinels from the 2026-08-13 LIRS study (gen_norank.py): a no-frequency-signal
    # ring whose score is the escape time from a starved corner (wide unseeded spread), and the
    # dense-but-worthless window pairs that price the density rest-point error.
    ("norank_rep_r6", "rep_r6_w4096.lirs", 8192, 8),
    ("norank_flood_j100", "flood_j100.lirs", 8192, 4),
    # /audit-regret round 1 (2026-08-15): the shallow wide moat; unimodal in outcome (spread 1.1)
    ("shallowmoat", "shallowmoat_8192.lirs", 8192, 2),
    # /audit-regret round 1: the reactive law beats the machine here (a burst three samples in ten
    # that the audit's down-walk cannot ride out); a reactive-anchor sentinel whose class was
    # family-ized by round 6's parkveil (this row keeps its own grid caveat)
    ("scarburst", "scarburst_8192.lirs", 8192, 2),
    # /audit-regret round 2 (2026-08-16): a pulsed lure inside a wide valley. `absolve` (period 16,
    # 128 samples) prices the ladder's reach through the lure-paced escalation; `absolve_p8` (period
    # 8, 256 samples) is the absorbing form where every confirm is kept and the rung is pinned at 1
    ("absolve", "absolve_8192.lirs", 8192, 2),
    ("absolve_p8", "absolve_p8_8192.lirs", 8192, 2),
    # /audit-regret round 3 (2026-08-17): the stale-claim family's away-anchor witness. A regime
    # shift lands with the window off the anchor, the stand-down keeps the phase-1 claim, the audit
    # walk that then finds a +30pp position cannot confirm against it, and the guard rail's veto
    # on the same claim drags the window to a position worse in the new regime (128 samples)
    ("ghostclaim", "ghostclaim_8192.lirs", 8192, 2),
    # /audit-regret round 4 (2026-08-17): the audit verdict's position on the walk. A crest one stride
    # from the floor with a cliff on its near side and a slope on its far side: the calibration audit
    # confirms at its fifth stride, three past the crest, the park's down-audit crashes on the cliff at
    # its fifth, and density's rest point above the crest holds the loss between audits (132 samples).
    # `hazefloor` is the same retention cycle at the top corner (round 3's note, rowed at x4 = 320
    # samples: 10.5M requests, the battery's longest cell after moat_h7800), seeded per-seed bars only
    ("crestpast", "crestpast_8192.lirs", 8192, 2),
    ("hazefloor", "hazefloor_8192.lirs", 8192, 2),
    # /audit-regret round 6 (2026-08-21): a phase-alternating mix (zipf <-> band, square period 13)
    # where the fixed mid window wins. Density chases the mix's alternating rest point, the
    # ladder's deep walk parks at the top corner, and the park lives exactly one shield: the first
    # post-shield flip is a crash-scale "shift" that stands the layer down at the anchor and
    # discards it, so the position is re-derived every cycle. The cycle is arm-independent
    # (noaudit runs it through the ladder alone); adjudicate the base on an N=8 seeded mean (a
    # 6-vs-2 basin split by which confirms land on flip samples). The 60-sample witness is the
    # first cycle in isolation and is deterministic; its noaudit arm pins the raw density law at
    # a sighted 15% blend equilibrium.
    ("parkveil", "parkveil_8192.lirs", 8192, 8),
    ("parkveil_min", "parkveil_min_8192.lirs", 8192, 2),
    # /audit-regret round 7 (2026-08-21): a prize arriving after the audit clock has backed off. A
    # flat prelude spends the schedule (the calibration park, two budget failures, auditWait
    # 32 -> 128), then a band caught only from 50% arrives; the stand-down that detects it
    # reschedules nothing (or, inside the undo's retreat, is covered), so nothing but the clock
    # moves the held floor: the base finds the band at s237 and the 187-sample witness never does
    # (its hybrid equals noaudit). Deterministic across admission seeds; the phase-1 length is the
    # family's alignment axis (the gate table has the ladder).
    ("latebloom", "latebloom_8192.lirs", 8192, 2),
    ("latebloom_min", "latebloom_min_8192.lirs", 8192, 2),
    # /audit-regret round 7: the main-side masked signal (main dense, sighted, worthless at the
    # margin; the window sighted by a trickle), the first interior-peak construction in seven
    # rounds. noaudit pins the floor; the audit layer recovers it through the calibration park and
    # the walk's confirm on the peak (56% on six seeds, 69% on two). Adjudicate on the N=8 mean.
    ("mainsat", "mainsat_8192.lirs", 8192, 8),
    # /audit-regret round 8: the calibration audit's opening side from an interior rest point
    # (AuditClock.down is true before any confirm and the rest has a stride of room below), the
    # stationary witness for section 8 item 4's latency residual; deterministic at 8 seeds.
    ("sidecliff", "sidecliff_8192.lirs", 8192, 2),
    # /audit-regret round 8: a first-round starvation confirm lands on the near edge of a band's
    # catch region by construction, plants no park, and density's rest point below the edge slides
    # it off; a working-set jump every 13 samples kicks and crashes the cycle. Adjudicate on the
    # N=8 mean (seeds 4 and 8 hold a sawtooth, the rest collapse).
    ("jumpslide", "jumpslide_8192.lirs", 8192, 8),
    # /audit-regret round 8: the moat family at a fraction-bar dose (the audit's crash bar 0.15 of
    # a 9% rate cuts a ten-stride approach one stride short of a +23pp step); bimodal on the tenth
    # stride's sample against the bar, adjudicate on the N=8 mean.
    ("lowbar", "lowbar_8192.lirs", 8192, 8),
]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    traces = sys.argv[1]
    variants = sys.argv[2].split(",") if len(sys.argv) > 2 else ["hybrid"]
    out = sys.argv[3] if len(sys.argv) > 3 else "gate.csv"
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "size", "runs"] + list(variants))
        for label, name, size, runs in CELLS:
            path = os.path.join(traces, name)
            if not os.path.exists(path):
                print(f"SKIP {label}: missing {path}", flush=True)
                continue
            # Rotate the arms INSIDE each run rather than running them in blocks. The processes
            # stay temporally adjacent, which limits environmental drift, but they do not share
            # JVM state or request-indexed admission draws.
            got = {v: [] for v in variants}
            for _ in range(runs):
                for v in variants:
                    hr = R.variant(path, size, v)[0]
                    if hr is not None:
                        got[v].append(hr)
            achieved = min((len(got[v]) for v in variants), default=0)
            if achieved != runs:
                print(f"DROPOUT {label}: " + " ".join(
                    f"{v}={len(got[v])}/{runs}" for v in variants), flush=True)
            row = [label, size, achieved]
            for v in variants:
                row.append(round(statistics.mean(got[v]), 2) if got[v] else "")
            w.writerow(row)
            fh.flush()
            print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
