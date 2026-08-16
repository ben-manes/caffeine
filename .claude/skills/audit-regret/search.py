#!/usr/bin/env python3
"""Search over workload specs: batch evaluation, neighborhoods, shrinking, and dose bisection.

Every subcommand evaluates specs through regret.py (anchors + product, one row per arm) and
appends to a resumable CSV, so an interrupted sweep costs nothing and a spec is never measured
twice under one label. Runs are sequential on purpose (one simulator process at a time).

  search.py eval <spec.json ... | dir> --csv results.csv [--size N] [--seeds 1] [--variants hybrid]
      Screen a batch; prints the ranking by gap. A directory means every *.json in it. --size is
      the maximum the cells RUN at (default: each spec's own); to change a spec's geometry,
      regenerate it with `mutate --set max=...`.

  search.py mutate base.json --set <path>=<v1>,<v2>,... [--set ...] --out DIR [--dry-run]
      Write the cartesian product of the settings as spec files (the neighborhood or the dose
      ladder). Paths are dotted into the JSON, list indices numeric:
        members.band.d=0.10,0.25,0.50   segments.0.samples=20,40   max=8192,16384
        segments.1.shares.band.mod.amp=0,0.5,0.8
      Then `eval` the directory.

  search.py shrink base.json --csv shrink.csv --out min.json [--keep 0.6] [--size N] [--seeds 1]
      Greedy delta-debugging toward the minimal witness: drop each member, each segment, halve
      repeat and segment lengths, zero each modulation; a reduction is kept while the gap stays
      at least --keep of the base gap (and >= 1pp). Passes repeat until nothing more can go.

  search.py pair a.json b.json --csv pair.csv [--seeds 1]
      An aliasing pair (one terrain, one bit flipped): run both and print the first sample where
      the trajectories diverge with the machine's fields at the decision side by side, so a
      claim that "the observables were identical when the actions had to differ" is checked
      rather than asserted.

  search.py bisect base.json --knob <path> --lo A --hi B --bar 2.0 [--steps 6] --csv bisect.csv
      Locate the phase transition of one continuous knob: the value where the gap crosses --bar,
      assuming the gap is monotone in the knob between --lo and --hi (check the printed ladder;
      if it is not monotone, mutate a ladder instead). Reports cliff vs gradient.

CAF_TREE selects the tree; a harness worktree gives trajectories, seeds and ablation arms.
"""
import argparse, copy, csv, glob, hashlib, itertools, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import regret as G   # noqa: E402
import workload as W  # noqa: E402


# ----------------------------------------------------------------------------- evaluation

def done_labels(path):
    """Rows already measured, keyed by (label, variant, size) so one spec run at two maxima is
    two rows rather than a skip."""
    if not path or not os.path.exists(path):
        return set()
    with open(path) as f:
        return {(r["label"], r["variant"], r["size"]) for r in csv.DictReader(f)}


def find_row(path, label, variant, size):
    if not path or not os.path.exists(path):
        return None
    with open(path) as f:
        for r in csv.DictReader(f):
            if (r["label"], r["variant"], r["size"]) == (label, variant, str(size)):
                return r
    return None


def evaluate(spec_path, args, label=None):
    """Runs one spec through regret.py's pipeline; returns {variant: row} and appends the CSV."""
    label = label or os.path.splitext(os.path.basename(spec_path))[0]
    # --size is the cache maximum the cell RUNS at, never a regeneration override: a spec's own
    # max sets its geometry (every size in it is relative), so changing scale means regenerating
    # with `mutate --set max=...`, and --size alone runs one geometry at another maximum, which
    # is what a tier-straddle pair (4096 / 4097) wants
    trace, spec = G.resolve_trace(spec_path, args.traces_dir, None, None)
    size = args.size or int(spec["max"])
    seeds = [int(s) for s in args.seeds.split(",")] if args.seeds else None
    variants = [v for v in args.variants.split(",") if v]
    rows_list = G.evaluate_cell(trace, spec, size, "lirs", variants, seeds, args.runs, args.start,
                                args.belady, args.dump_dir, label)
    rows = {r["variant"]: r for r in rows_list}
    if args.csv and rows:
        new = not os.path.exists(args.csv)
        with open(args.csv, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=G.FIELDS)
            if new:
                w.writeheader()
            w.writerows(rows.values())
    return rows


def gap_of(rows, variant):
    r = rows.get(variant)
    return float(r["gap"]) if r else float("nan")


def write_spec(spec, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w") as f:
        json.dump(spec, f, indent=1)
        f.write("\n")
    return path


# ----------------------------------------------------------------------------- paths

def parse_value(v):
    for cast in (int, float):
        try:
            return cast(v)
        except ValueError:
            pass
    return v


def set_path(spec, path, value):
    """Sets a dotted path; a share given as a number replaces a {share, mod} entry's share."""
    node = spec
    parts = path.split(".")
    for p in parts[:-1]:
        node = node[int(p)] if isinstance(node, list) else node[p]
    last = parts[-1]
    if isinstance(node, list):
        node[int(last)] = value
    elif isinstance(node.get(last), dict) and "share" in node[last] and not isinstance(value, dict):
        node[last]["share"] = value
    else:
        node[last] = value


def get_path(spec, path):
    node = spec
    for p in path.split("."):
        node = node[int(p)] if isinstance(node, list) else node[p]
    return node


# ----------------------------------------------------------------------------- subcommands

def cmd_eval(args):
    specs = []
    for s in args.specs:
        specs.extend(sorted(glob.glob(os.path.join(s, "*.json"))) if os.path.isdir(s) else [s])
    done = done_labels(args.csv)
    variants = [v for v in args.variants.split(",") if v]
    ranking = []
    for sp in specs:
        label = os.path.splitext(os.path.basename(sp))[0]
        size = args.size or W.load(sp)["max"]
        if all((label, v, str(size)) in done for v in variants):
            print(f"{label} @{size}: done, skipping")
            continue
        rows = evaluate(sp, args, label)
        for v, r in rows.items():
            ranking.append((float(r["gap"]), label, v, r))
    if args.csv and os.path.exists(args.csv):
        with open(args.csv) as f:
            ranking = [(float(r["gap"]), r["label"], r["variant"], r) for r in csv.DictReader(f)]
    ranking.sort(key=lambda t: -t[0])
    print("\nranking by gap (pp below the static ceiling):")
    print(f"{'label':36s} {'size':>7s} {'arm':8s} {'gap':>7s} {'closed':>7s} {'headroom':>8s} "
          f"{'n':>4s}  hints")
    for gap, label, v, r in ranking:
        print(f"{label:36s} {r.get('size', ''):>7s} {v:8s} {gap:7.2f} {r.get('closed', ''):>7s} "
              f"{r.get('headroom', ''):>8s} {r.get('n', ''):>4s}  {r.get('hints', '')}")


def cmd_mutate(args):
    base = W.load(args.base)
    axes = []
    for s in args.set:
        path, vals = s.split("=", 1)
        axes.append((path, [parse_value(v) for v in vals.split(",")]))
    stem = os.path.splitext(os.path.basename(args.base))[0]
    made = []
    for combo in itertools.product(*[vals for _, vals in axes]):
        spec = copy.deepcopy(base)
        tags = []
        for (path, _), val in zip(axes, combo):
            set_path(spec, path, val)
            tags.append(f"{path.split('.')[-2] + '.' if path.count('.') else ''}{path.split('.')[-1]}={val}")
        out = os.path.join(args.out, stem + "__" + "__".join(tags) + ".json")
        made.append(out)
        if not args.dry_run:
            write_spec(spec, out)
    for m in made:
        print(m)
    print(f"{len(made)} specs{' (dry run)' if args.dry_run else ''}")


def reductions(spec, min_samples=G.SHORT_SAMPLES):
    """Candidate one-step reductions of a spec, each as (name, new_spec). Length reductions
    never take the trace under min_samples decisions: a short trace prices convergence, and a
    "witness" made by shortening measures how long the climber takes rather than what it does."""
    out = []
    members = list(spec["members"].keys())
    for m in members:
        if len(members) == 1:
            break
        s = copy.deepcopy(spec)
        ok = True
        for seg in s["segments"]:
            seg["shares"].pop(m, None)
            if not seg["shares"]:
                ok = False
        if ok:
            s["members"].pop(m)
            out.append((f"drop member {m}", s))
    if len(spec["segments"]) > 1:
        for i in range(len(spec["segments"])):
            s = copy.deepcopy(spec)
            s["segments"].pop(i)
            out.append((f"drop segment {i}", s))
    if int(spec.get("repeat", 1)) > 1:
        s = copy.deepcopy(spec)
        s["repeat"] = max(1, int(spec["repeat"]) // 2)
        if W.samples(s) >= min_samples:
            out.append((f"repeat {spec['repeat']} -> {s['repeat']}", s))
    for i, seg in enumerate(spec["segments"]):
        s = copy.deepcopy(spec)
        s["segments"][i]["samples"] = int(seg["samples"]) // 2
        if int(seg["samples"]) >= 8 and W.samples(s) >= min_samples:
            out.append((f"segment {i} samples {seg['samples']} -> {s['segments'][i]['samples']}", s))
        for name, entry in seg["shares"].items():
            if isinstance(entry, dict) and entry.get("mod"):
                s = copy.deepcopy(spec)
                s["segments"][i]["shares"][name] = entry["share"]
                out.append((f"segment {i} unmodulate {name}", s))
    return out


def primary(row):
    """The first hint that names a class (flags like SHORT/peak-at-edge/left-optimum skipped)."""
    flags = ("SHORT", "peak-at-edge", "left-optimum", "clean", "beats-static-ceiling")
    for h in (row.get("hints") or "").split("|"):
        h = h.split(" x")[0].strip()
        if h and h not in flags:
            return h
    return None


def cmd_shrink(args):
    base = W.load(args.base)
    work = os.path.join(os.path.dirname(os.path.abspath(args.out)), "shrink-work")
    stem = os.path.splitext(os.path.basename(args.base))[0]

    def run(spec, tag):
        h = hashlib.md5(json.dumps(spec, sort_keys=True).encode()).hexdigest()[:8]
        path = write_spec(spec, os.path.join(work, f"{stem}__{tag}__{h}.json"))
        label = f"{stem}__{tag}__{h}"
        size = args.size or spec["max"]
        row = find_row(args.csv, label, args.variant, size) or \
            evaluate(path, args, label).get(args.variant)
        return float(row["gap"]), primary(row)

    if W.samples(base) < args.min_samples:
        sys.exit(f"base holds {W.samples(base)} samples, under --min-samples {args.min_samples}: "
                 f"lengthen it first, a short trace prices convergence")
    g0, class0 = run(base, "base")
    lo, hi = max(1.0, args.keep * g0), g0 / args.keep
    print(f"base gap {g0:.2f} class {class0}; keeping reductions with gap in [{lo:.2f}, {hi:.2f}]"
          + (f" and class {class0}" if class0 else " (no trajectory: gap band only)"))
    if g0 < 1.0:
        sys.exit("base gap is below 1pp: nothing to shrink")
    cur, log, step = base, [], 0
    while True:
        accepted = False
        for name, cand in reductions(cur, args.min_samples):
            step += 1
            g, cls = run(cand, f"r{step}")
            same = (class0 is None) or (cls == class0)
            keep = (lo <= g <= hi) and same
            why = "" if keep else (" (class changed: %s)" % cls if not same else
                                   " (gap left the band: amplified or lost)")
            log.append((name, g, cls, keep))
            print(f"  [{step}] {name}: gap {g:.2f} {cls or ''} -> {'KEEP' if keep else 'reject'}{why}",
                  flush=True)
            if keep:
                cur = cand
                accepted = True
                break
        if not accepted:
            break
    write_spec(cur, args.out)
    print(f"\nminimal witness -> {args.out}")
    W.describe(cur)
    with open(args.out + ".log", "w") as f:
        f.write(f"base gap {g0:.2f} class {class0}\n")
        for name, g, cls, keep in log:
            f.write(f"{'KEEP  ' if keep else 'reject'} {g:6.2f} {cls or '-':30s} {name}\n")


def cmd_bisect(args):
    base = W.load(args.base)
    work = os.path.join(os.path.dirname(os.path.abspath(args.csv)), "bisect-work")
    stem = os.path.splitext(os.path.basename(args.base))[0]
    knob = args.knob.split(".")[-1]
    ladder = []

    def run(val):
        spec = copy.deepcopy(base)
        set_path(spec, args.knob, val)
        label = f"{stem}__{knob}={val:g}"
        path = write_spec(spec, os.path.join(work, label + ".json"))
        prior = find_row(args.csv, label, args.variant, args.size or spec["max"])
        g = float(prior["gap"]) if prior else gap_of(evaluate(path, args, label), args.variant)
        ladder.append((val, g))
        return g

    lo, hi = float(args.lo), float(args.hi)
    glo, ghi = run(lo), run(hi)
    if (glo >= args.bar) == (ghi >= args.bar):
        print(f"no crossing of bar {args.bar} between {lo:g} ({glo:.2f}) and {hi:g} ({ghi:.2f}); "
              f"widen the range or lower the bar")
    else:
        for _ in range(args.steps):
            mid = (lo + hi) / 2
            gm = run(mid)
            if (gm >= args.bar) == (glo >= args.bar):
                lo, glo = mid, gm
            else:
                hi, ghi = mid, gm
        print(f"\ntransition of {args.knob} at ~{(lo + hi) / 2:g} (bracket {lo:g}..{hi:g}: "
              f"gap {glo:.2f} -> {ghi:.2f})")
    ladder.sort()
    print("ladder:")
    for v, g in ladder:
        print(f"  {knob}={v:<10g} gap={g:6.2f}")
    if len(ladder) >= 3:
        span = abs(ladder[-1][1] - ladder[0][1])
        rng = ladder[-1][0] - ladder[0][0]
        jumps = [(abs(b[1] - a[1]), b[0] - a[0]) for a, b in zip(ladder, ladder[1:])]
        big = max(jumps, key=lambda j: j[0])
        if span > 0 and big[0] >= 0.5 * span and big[1] <= 0.15 * rng:
            print("shape: CLIFF (half the range's change inside 15% of the knob range)")
        else:
            print("shape: gradient")


def cmd_pair(args):
    """Two specs meant to be observably alike at the decision (an aliasing pair): run both, then
    walk the trajectories together and print the first sample where they diverge, with the
    machine's fields at that sample side by side."""
    rows = {}
    for sp in (args.a, args.b):
        label = os.path.splitext(os.path.basename(sp))[0]
        rows[sp] = evaluate(sp, args, label)
    dumps = []
    for sp in (args.a, args.b):
        label = os.path.splitext(os.path.basename(sp))[0]
        tag = f"s{args.seeds.split(',')[0]}" if args.seeds else "r0"
        path = os.path.join(args.dump_dir, f"{label}.{args.variant}.{tag}.traj")
        if not os.path.exists(path):
            sys.exit(f"no trajectory at {path} (harness tree needed)")
        with open(path) as f:
            dumps.append(G.parse([ln for ln in f if ln.startswith("climb ")]))
    a, b = dumps
    n = min(len(a), len(b))
    keys = ["win", "hr", "mode", "wh", "mh", "ph", "adj", "rung", "arung", "auditWait", "left",
            "anchorW", "anchorR", "ema", "dev", "hold", "wbase", "wbar"]
    print(f"\n{'s':>3s} {'winA':>6s} {'winB':>6s} {'modeA':>12s} {'modeB':>12s} {'hrA':>7s} {'hrB':>7s}")
    first = None
    for i in range(n):
        wa, wb = int(a[i]["win"]), int(b[i]["win"])
        if first is None and abs(wa - wb) > 0.02 * int(a[i]["max"]):
            first = i
        if i < 6 or (first is not None and abs(i - first) <= 3):
            print(f"{i:3d} {wa:6d} {wb:6d} {a[i]['mode']:>12s} {b[i]['mode']:>12s} "
                  f"{a[i]['hr']:>7s} {b[i]['hr']:>7s}")
    if first is None:
        print(f"the windows never diverge by more than 2% over {n} samples")
        return
    j = max(0, first - 1)
    print(f"\nfirst divergence at sample {first}; the decision sample {j} side by side:")
    for k in keys:
        va, vb = a[j].get(k, ""), b[j].get(k, "")
        mark = "  <-" if va != vb else ""
        print(f"  {k:10s} {va:>12s} {vb:>12s}{mark}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--csv", default=None)
        p.add_argument("--size", type=int, default=None)
        p.add_argument("--seeds", default="1", help="admission seeds (harness); '' for unseeded")
        p.add_argument("--runs", type=int, default=1)
        p.add_argument("--variants", default="hybrid")
        p.add_argument("--variant", default="hybrid", help="the arm shrink/bisect judge by")
        p.add_argument("--start", type=float, default=0.01)
        p.add_argument("--belady", action="store_true")
        p.add_argument("--traces-dir", default=os.path.join(os.getcwd(), "traces"))
        p.add_argument("--dump-dir", default=os.path.join(os.getcwd(), "dumps"))

    p = sub.add_parser("eval")
    p.add_argument("specs", nargs="+")
    common(p)
    p = sub.add_parser("mutate")
    p.add_argument("base")
    p.add_argument("--set", action="append", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("shrink")
    p.add_argument("base")
    p.add_argument("--out", required=True)
    p.add_argument("--keep", type=float, default=0.6)
    p.add_argument("--min-samples", type=int, default=G.SHORT_SAMPLES)
    common(p)
    p = sub.add_parser("pair")
    p.add_argument("a")
    p.add_argument("b")
    common(p)
    p = sub.add_parser("bisect")
    p.add_argument("base")
    p.add_argument("--knob", required=True)
    p.add_argument("--lo", type=float, required=True)
    p.add_argument("--hi", type=float, required=True)
    p.add_argument("--bar", type=float, default=2.0)
    p.add_argument("--steps", type=int, default=6)
    common(p)
    args = ap.parse_args()
    if hasattr(args, "seeds") and args.seeds == "":
        args.seeds = None
    {"eval": cmd_eval, "mutate": cmd_mutate, "shrink": cmd_shrink, "bisect": cmd_bisect,
     "pair": cmd_pair}[args.cmd](args)


if __name__ == "__main__":
    main()
