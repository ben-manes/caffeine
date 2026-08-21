#!/usr/bin/env python3
"""Run one (trace, size) cell through the simulator and report HR + trajectory.

The tree defaults to this repository; point CAF_TREE at an experiment worktree to
measure a candidate. `--variants`/`--dump` are meaningful only when that tree carries
the skill's harness (`harness.py apply <worktree>`; a stock build ignores the properties
and emits no trajectory); against a stock tree just omit them.

Pass an EMPTY --variants to run the anchors alone. That mode is load-bearing: hill-climber.md
§6 selects a holdout by LRU-only characterization, and a cell is disqualified once any
product.Caffeine process has touched it. Before 2026-08-02 the variant list was never allowed to
be empty, so --anchors always fired one product run and silently burned the cell it was
characterizing.

Usage:
  run.py <trace> --size N [--variants hybrid,noaudit,prefix] [--runs 1 | --seeds 1,2,3]
         [--fmt lirs] [--anchors] [--dump PREFIX]
  run.py <trace> --size N --anchors --variants ''        # characterize without burning the cell
"""
import argparse, os, re, statistics, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WT = os.environ.get("CAF_TREE", os.path.abspath(f"{HERE}/../../.."))
WINDOWS = [0.01, 0.02, 0.05, 0.10, 0.20, 0.30, 0.40, 0.50, 0.70, 0.80]
JAVA_LONG_MIN = -(1 << 63)
JAVA_LONG_MAX = (1 << 63) - 1


def gradle(size, trace, args, fmt="lirs"):
    base = [f"{WT}/gradlew", "simulator:run", "-q",
            f"-Dcaffeine.simulator.maximum-size={size}",
            f"-Dcaffeine.simulator.files.paths.0={fmt}:{os.path.abspath(trace)}",
            "-Dcaffeine.simulator.admission.0=Always",
            "-Dcaffeine.simulator.report.format=csv"]
    # CAF_EXTRA appends harness properties (e.g. -Dcaffeine.climber.startwin=0.4) to every run,
    # so a study can hold one knob across the anchors and every arm without forking the tools.
    base += os.environ.get("CAF_EXTRA", "").split()
    return subprocess.run(base + args, capture_output=True, text=True, cwd=WT)


def curve(trace, size, fmt="lirs", windows=WINDOWS):
    """Returns (LRU, {percent-main label: hit rate}) over the static-window anchor sweep."""
    args = ["-Dcaffeine.simulator.policies.0=linked.Lru",
            "-Dcaffeine.simulator.policies.1=sketch.WindowTinyLfu"]
    for i, w in enumerate(windows):
        args.append(f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.2f}")
    r = gradle(size, trace, args, fmt)
    lru, static = None, {}
    for ln in r.stdout.splitlines():
        if ln.startswith("linked.Lru,"):
            lru = float(ln.split(",")[1])
        m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),", ln)
        if m:
            static[int(m.group(1))] = float(m.group(2))
    if lru is None:
        sys.stderr.write(r.stdout[-3000:] + r.stderr[-3000:])
    return lru, static


def anchors(trace, size, fmt="lirs"):
    lru, static = curve(trace, size, fmt)
    best = (-1.0, None)
    for win, hr in static.items():
        if hr > best[0]:
            best = (hr, win)
    return lru, best


def variant(trace, size, var, fmt="lirs", debug=False, dump=None, seed=None, extra=()):
    args = ["-Dcaffeine.simulator.policies.0=product.Caffeine",
            f"-Dcaffeine.climber.variant={var}", *extra]
    if debug:
        args.append("-Dcaffeine.climber.debug=true")
    if seed is not None:
        args.append(f"-Dcaffeine.climber.seed={seed}")
    r = gradle(size, trace, args, fmt)
    hr = None
    for ln in r.stdout.splitlines():
        if ln.startswith("product.Caffeine,"):
            hr = float(ln.split(",")[1])
    lines = [ln for ln in r.stderr.splitlines() if ln.startswith("climb ")]
    if dump and lines:
        with open(dump, "w") as f:
            f.write("\n".join(lines) + "\n")
    if hr is None:
        sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
    return hr, lines


def summarize(lines, size):
    if not lines:
        return "no trajectory (stock build or debug off)"
    wins, modes = [], {}
    for ln in lines:
        d = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
        wins.append(int(d["win"]) / size)
        modes[d["mode"]] = modes.get(d["mode"], 0) + 1
    audits = sum(v for k, v in modes.items() if k.startswith("AUDIT"))
    conf = modes.get("AUDITCONFIRM", 0)
    arms = sum(v for k, v in modes.items() if k.startswith("ARM"))
    return (f"n={len(wins)} start={wins[0]:.3f} min={min(wins):.3f} max={max(wins):.3f} "
            f"end={wins[-1]:.3f} mean={statistics.mean(wins):.3f} "
            f"@floor={sum(1 for w in wins if w <= 0.024) / len(wins):.0%} "
            f"audits={audits - conf} confirms={conf} probes={arms}")


def parse_seeds(value):
    """Parse a non-empty, duplicate-free list of signed Java long admission seeds."""
    if value is None:
        return None
    try:
        seeds = [int(seed) for seed in value.split(",")]
    except ValueError as error:
        raise ValueError(f"invalid --seeds value: {value!r}") from error
    if not seeds or any(not token for token in value.split(",")):
        raise ValueError("--seeds must contain comma-separated integers")
    if any(seed < JAVA_LONG_MIN or seed > JAVA_LONG_MAX for seed in seeds):
        raise ValueError("--seeds must contain signed 64-bit integers")
    if len(seeds) != len(set(seeds)):
        raise ValueError("--seeds must not contain duplicates")
    return seeds


def parse_variants(value):
    """Parse the variant list, preserving the supported empty-list anchor mode."""
    variants = [variant for variant in value.split(",") if variant]
    if len(variants) != len(set(variants)):
        raise ValueError("--variants must not contain duplicates")
    return variants


def execution_plan(variants, runs, seeds):
    """Return (variant, seed, ordinal) runs, interleaving variants within each seed."""
    if seeds is None:
        return [(variant, None, ordinal) for variant in variants for ordinal in range(runs)]
    return [
        (variant, seed, ordinal)
        for ordinal, seed in enumerate(seeds)
        for variant in variants
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--size", type=int, required=True)
    ap.add_argument("--variants", default="hybrid")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--fmt", default="lirs")
    ap.add_argument("--anchors", action="store_true")
    ap.add_argument("--dump", default=None)
    ap.add_argument("--seeds", default=None,
                    help="comma-separated admission seeds; runs one pass per seed instead of "
                         "--runs, so each arm is reproducible and arms compare seed by seed; "
                         "not request-indexed common-random-number pairing (hill-climber.md §6)")
    args = ap.parse_args()
    try:
        seeds = parse_seeds(args.seeds)
        variants = parse_variants(args.variants)
    except ValueError as error:
        ap.error(str(error))

    name = os.path.basename(args.trace)
    if args.anchors:
        lru, (best, bw) = anchors(args.trace, args.size, args.fmt)
        if (lru is None) or (bw is None):
            # a weighted trace drops linked.Lru by design, so an absent anchor is not always a
            # failure; either way it is reported rather than formatted as a float
            print(f"{name} @ {args.size}:  ANCHORS INCOMPLETE  LRU={lru}  ceiling={bw}")
        else:
            print(f"{name} @ {args.size}:  LRU={lru:.2f}  ceiling={best:.2f} @win {bw}%")
    else:
        print(f"{name} @ {args.size}:")
    results = {variant_name: [] for variant_name in variants}
    trajectories = {variant_name: [] for variant_name in variants}
    for variant_name, seed, ordinal in execution_plan(variants, args.runs, seeds):
        hr, lines = variant(
            args.trace, args.size, variant_name, args.fmt, debug=True,
            dump=(f"{args.dump}.{variant_name}.traj" if args.dump and ordinal == 0 else None),
            seed=seed)
        if hr is None:
            raise SystemExit(
                f"missing hit rate for variant={variant_name} "
                f"seed={seed if seed is not None else ordinal}")
        results[variant_name].append((seed, hr))
        if ordinal == 0:
            trajectories[variant_name] = lines

    for variant_name in variants:
        seeded_rates = results[variant_name]
        hrs = [hit_rate for _, hit_rate in seeded_rates]
        m = statistics.mean(hrs) if hrs else float("nan")
        sp = (max(hrs) - min(hrs)) if len(hrs) > 1 else 0.0
        seed_vector = ""
        if seeds is not None:
            seed_vector = "   seeds=" + ",".join(
                f"s{seed}:{hit_rate:.2f}" for seed, hit_rate in seeded_rates)
        print(f"  {variant_name:9s} hr={m:6.2f}±{sp:.2f}   "
              f"{summarize(trajectories[variant_name], args.size)}{seed_vector}")


if __name__ == "__main__":
    main()
