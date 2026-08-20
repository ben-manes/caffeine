#!/usr/bin/env python3
"""Per-tenant regret: key-range hit/miss accounting over the anchor sweep and the product arms.

Every gate bar and `regret.py` column is aggregate, so "a minority tenant stranded while the
aggregate reads clean" is invisible to them by construction. This instrument attributes each
request's hit or miss to a key range (a tenant), the same way `PolicyActor` already attributes
penalties: bracket `policy.record(event)` with the prior hit/miss counts. That works for every
synchronous policy and for the materialized Clairvoyant, and it is inert unless
`-Dcaffeine.simulator.tenantSplits` is set, so the gate and regret tools are unaffected.

    tenants.py patch|verify|strip <worktree>       # wire the accounting into PolicyActor (idempotent)
    tenants.py cell <trace|spec.json> --size N [--splits auto|k1,k2] [--fmt lirs]
               [--windows 0.01,...] [--variants hybrid] [--seeds 1,2] [--csv out.csv]
    tenants.py split <trace> --splits k1[,k2..] --outdir DIR   # per-tenant sub-traces
    tenants.py dedicated <trace> --size N --splits k1 [--fracs 0.1,..] [--csv out.csv]

Splits are boundaries: ranges (-inf,k1), [k1,k2), [k2,inf). `--splits auto` derives them from a
workload.py spec's member order ((i+1) * BLOCK key bases). For `gen.py mixture` traces the split
is `int(0.6 * max)` (zipf hot set below, recency pairs above); for `a1_tenant.py` it is 20000000.

Per-tenant readouts per arm: request share, hit rate, and against the anchors the three gaps that
separate the mechanisms: `g_agg` = tenant's rate at the aggregate-best static window minus the
product's (what a perfect aggregate optimizer would already cost or give this tenant is excluded);
`g_own` = tenant's rate at its own best static window minus the product's (the cost of sharing one
window, climber included); `dedicated` (from the dedicated sweep) = the tenant's rate in its own
product cache at a capacity fraction (the consolidation counterfactual). Belady is out of scope
here; `attributed` is checked against the CSV row and a mismatch discards the run.

NEVER commit the worktree's patch: it prints to stderr, like the climber harness.
"""
import argparse, csv, json, os, statistics, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.abspath(f"{HERE}/../climber-gate")
sys.path.insert(0, GATE)
import run as R  # noqa: E402

BLOCK = 1 << 40  # workload.py's key-space stride per member
ACTOR = ("simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator"
         "/policy/PolicyActor.java")

# ---------------------------------------------------------------- the PolicyActor patch

FIELDS_ANCHOR = "  private CompletableFuture<@Nullable Void> future;"
FIELDS = FIELDS_ANCHOR + '''

  // TENANT ACCOUNTING (worktree-only, wired by audit-regret/tenants.py, never commit): per
  // key-range hit/miss counts, attributed by bracketing record() as the penalty attribution
  // below does. -Dcaffeine.simulator.tenantSplits=k1,k2 defines the ranges (-inf,k1), [k1,k2),
  // [k2,inf); unset leaves all of it inert.
  private static final long[] TENANT_SPLITS = parseTenantSplits();
  private final long[] tenantHits = new long[TENANT_SPLITS.length + 1];
  private final long[] tenantMisses = new long[TENANT_SPLITS.length + 1];

  private static long[] parseTenantSplits() {
    String property = System.getProperty("caffeine.simulator.tenantSplits", "");
    if (property.isEmpty()) {
      return new long[0];
    }
    long[] splits = java.util.Arrays.stream(property.split(","))
        .mapToLong(Long::parseLong).toArray();
    java.util.Arrays.sort(splits);
    return splits;
  }

  private int tenantOf(long key) {
    int index = java.util.Arrays.binarySearch(TENANT_SPLITS, key);
    return (index >= 0) ? (index + 1) : (-index - 1);
  }'''

ATTRIBUTE_ANCHOR = """        if (policy.stats().hitCount() > priorHits) {
          policy.stats().recordHitPenalty(event.hitPenalty());
        } else if (policy.stats().missCount() > priorMisses) {
          policy.stats().recordMissPenalty(event.missPenalty());
        }"""
ATTRIBUTE = """        if (policy.stats().hitCount() > priorHits) {
          policy.stats().recordHitPenalty(event.hitPenalty());
          if (TENANT_SPLITS.length > 0) {
            tenantHits[tenantOf(event.key())]++;
          }
        } else if (policy.stats().missCount() > priorMisses) {
          policy.stats().recordMissPenalty(event.missPenalty());
          if (TENANT_SPLITS.length > 0) {
            tenantMisses[tenantOf(event.key())]++;
          }
        }"""

DUMP_ANCHOR = """      policy.finished();
      completed.complete(null);"""
DUMP = """      policy.finished();
      if (TENANT_SPLITS.length > 0) {
        var line = new StringBuilder("TENANT ").append(policy.stats().name().replace(' ', '_'));
        long attributed = 0;
        for (int i = 0; i < tenantHits.length; i++) {
          line.append(" t").append(i).append('=')
              .append(tenantHits[i]).append(':').append(tenantMisses[i]);
          attributed += tenantHits[i] + tenantMisses[i];
        }
        line.append(" attributed=").append(attributed);
        System.err.println(line);
      }
      completed.complete(null);"""

EDITS = [(FIELDS_ANCHOR, FIELDS), (ATTRIBUTE_ANCHOR, ATTRIBUTE), (DUMP_ANCHOR, DUMP)]


def run_patch(mode, tree):
    path = os.path.join(tree, ACTOR)
    with open(path) as f:
        text = f.read()
    changed = 0
    for anchor, repl in EDITS:
        present = repl in text
        if mode == "verify":
            if not present:
                sys.exit(f"tenant patch: MISSING edit anchored at: {anchor.splitlines()[0]!r}")
        elif mode == "strip":
            if present:
                text = text.replace(repl, anchor)
                changed += 1
        elif not present:
            if anchor not in text:
                sys.exit(f"tenant patch: anchor not found: {anchor.splitlines()[0]!r}")
            text = text.replace(anchor, repl)
            changed += 1
    if mode in ("patch", "strip"):
        with open(path, "w") as f:
            f.write(text)
    print(f"tenant patch {mode}: ok ({changed if mode != 'verify' else len(EDITS)}/{len(EDITS)})")


# ---------------------------------------------------------------- invocation and parsing

def prop(splits):
    return f"-Dcaffeine.simulator.tenantSplits={','.join(str(s) for s in splits)}"


def parse_tenants(stderr):
    """{policy display name: [(hits, misses), ...]} from the patch's stderr dump."""
    out = {}
    for ln in stderr.splitlines():
        if not ln.startswith("TENANT "):
            continue
        parts = ln.split()
        name = parts[1].replace("_", " ")
        counts = [tuple(int(x) for x in kv.split("=", 1)[1].split(":"))
                  for kv in parts[2:] if kv.startswith("t")]
        out[name] = counts
    return out


def rate(hm):
    h, m = hm
    return 100.0 * h / (h + m) if (h + m) else 0.0


def anchor_sweep(trace, size, splits, fmt="lirs", windows=None):
    """One invocation: Lru + the WindowTinyLfu window sweep, aggregate and per-tenant."""
    windows = windows or R.WINDOWS
    args = ["-Dcaffeine.simulator.policies.0=linked.Lru",
            "-Dcaffeine.simulator.policies.1=sketch.WindowTinyLfu", prop(splits)]
    for i, w in enumerate(windows):
        args.append(f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.2f}")
    r = R.gradle(size, trace, args, fmt)
    agg, per = {}, parse_tenants(r.stderr)
    import re
    for ln in r.stdout.splitlines():
        if ln.startswith("linked.Lru,"):
            agg["lru"] = float(ln.split(",")[1])
        m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\),([\d.]+),", ln)
        if m:
            agg[f"w{int(m.group(1))}"] = float(m.group(2))
    if "lru" not in agg:
        sys.stderr.write(r.stdout[-3000:] + r.stderr[-3000:])
        sys.exit("anchor sweep failed")
    tenants = {}
    for name, counts in per.items():
        key = "lru" if name.startswith("linked.Lru") else None
        if key is None:
            m = re.match(r"sketch\.WindowTinyLfu \((\d+)%\)", name)
            key = f"w{int(m.group(1))}" if m else None
        if key:
            tenants[key] = counts
    return agg, tenants


def product_arm(trace, size, splits, fmt="lirs", variant="hybrid", seed=None, dump=None):
    extra = [prop(splits)]
    args = ["-Dcaffeine.simulator.policies.0=product.Caffeine",
            f"-Dcaffeine.climber.variant={variant}", "-Dcaffeine.climber.debug=true", *extra]
    if seed is not None:
        args.append(f"-Dcaffeine.climber.seed={seed}")
    r = R.gradle(size, trace, args, fmt)
    hr = None
    for ln in r.stdout.splitlines():
        if ln.startswith("product.Caffeine,"):
            hr = float(ln.split(",")[1])
    if hr is None:
        sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
        sys.exit("product arm failed")
    if dump:
        lines = [ln for ln in r.stderr.splitlines() if ln.startswith("climb ")]
        if lines:
            with open(dump, "w") as f:
                f.write("\n".join(lines) + "\n")
    per = parse_tenants(r.stderr)
    return hr, per.get("product.Caffeine", [])


def synthesize(spec_path, out_dir, max_=None, seed=None):
    """workload.py spec -> trace; returns (trace path, splits from member order)."""
    with open(spec_path) as f:
        spec = json.load(f)
    members = len(spec["members"])
    splits = [(i + 2) * BLOCK for i in range(members - 1)]
    label = os.path.splitext(os.path.basename(spec_path))[0]
    trace = os.path.join(out_dir, f"{label}.lirs")
    if not os.path.exists(trace):
        cmd = [sys.executable, os.path.join(HERE, "workload.py"), spec_path, "--out", trace]
        if max_:
            cmd += ["--max", str(max_)]
        if seed is not None:
            cmd += ["--seed", str(seed)]
        subprocess.run(cmd, check=True)
    names = list(spec["members"])
    return trace, splits, names


# ---------------------------------------------------------------- cell

def cmd_cell(a):
    splits, names = None, None
    if a.trace.endswith(".json"):
        os.makedirs(a.traces_dir, exist_ok=True)
        trace, auto, names = synthesize(a.trace, a.traces_dir, a.size, a.trace_seed)
        splits = auto if a.splits == "auto" else [int(x) for x in a.splits.split(",")]
    else:
        trace = a.trace
        if a.splits == "auto":
            sys.exit("--splits auto needs a spec.json input")
        splits = [int(x) for x in a.splits.split(",")]
    k = len(splits) + 1
    names = names or [f"t{i}" for i in range(k)]
    windows = [float(x) for x in a.windows.split(",")] if a.windows else R.WINDOWS

    side = f"{trace}.tenants.{a.size}.json"
    if os.path.exists(side) and not a.refresh:
        with open(side) as f:
            cached = json.load(f)
        agg, tenants = cached["agg"], {k2: [tuple(x) for x in v]
                                       for k2, v in cached["tenants"].items()}
    else:
        agg, tenants = anchor_sweep(trace, a.size, splits, a.fmt, windows)
        with open(side, "w") as f:
            json.dump({"agg": agg, "tenants": tenants}, f)

    warms = [k2 for k2 in agg if k2.startswith("w")]
    ceiling_w = max(warms, key=lambda k2: agg[k2])
    start = agg.get("w1", agg[min(warms, key=lambda k2: int(k2[1:]))])
    headroom = agg[ceiling_w] - start

    seeds = [int(x) for x in a.seeds.split(",")] if a.seeds else [None]
    rows = []
    for variant in a.variants.split(","):
        per_seed, per_seed_t = [], []
        for seed in seeds:
            dump = None
            if a.dump_dir:
                os.makedirs(a.dump_dir, exist_ok=True)
                tag = f"{os.path.basename(trace)}.{a.size}.{variant}.s{seed}"
                dump = os.path.join(a.dump_dir, f"{tag}.climb")
            hr, per = product_arm(trace, a.size, splits, a.fmt, variant, seed, dump)
            per_seed.append(hr)
            per_seed_t.append(per)
        mean = statistics.mean(per_seed)
        sd = statistics.stdev(per_seed) if len(per_seed) > 1 else 0.0
        gap = agg[ceiling_w] - mean
        print(f"\n== {os.path.basename(trace)} @{a.size} {variant} "
              f"seeds={a.seeds or 'unseeded'}")
        print(f"aggregate: LRU {agg['lru']:.2f}  start {start:.2f}  "
              f"ceiling {agg[ceiling_w]:.2f} @{ceiling_w}  product {mean:.2f} ±{sd:.2f}  "
              f"gap {gap:+.2f}  headroom {headroom:+.2f}")
        total = [tuple(map(sum, zip(*[s[i] for s in per_seed_t])))
                 for i in range(k)] if per_seed_t[0] else []
        print(f"{'tenant':>10} {'share%':>7} {'lru':>6} {'@agg' + ceiling_w:>8} "
              f"{'own-best':>12} {'product':>8} {'g_agg':>7} {'g_own':>7}")
        n_reqs = sum(h + m for h, m in total) or 1
        for i in range(k):
            t_curve = {k2: rate(v[i]) for k2, v in tenants.items() if v}
            own_w = max((k2 for k2 in t_curve if k2.startswith("w")),
                        key=lambda k2: t_curve[k2])
            t_prod = rate(total[i]) if total else float("nan")
            share = 100.0 * sum(total[i]) / n_reqs if total else 0.0
            g_agg = t_curve[ceiling_w] - t_prod
            g_own = t_curve[own_w] - t_prod
            print(f"{names[i]:>10} {share:7.2f} {t_curve['lru']:6.2f} "
                  f"{t_curve[ceiling_w]:8.2f} {t_curve[own_w]:6.2f}@{own_w:<5} "
                  f"{t_prod:8.2f} {g_agg:+7.2f} {g_own:+7.2f}")
            rows.append({"label": os.path.basename(trace), "size": a.size, "variant": variant,
                         "tenant": names[i], "share": round(share, 3),
                         "lru_t": round(t_curve["lru"], 3),
                         "at_agg_ceiling": round(t_curve[ceiling_w], 3),
                         "own_best_w": own_w, "own_best": round(t_curve[own_w], 3),
                         "product": round(t_prod, 3), "g_agg": round(g_agg, 3),
                         "g_own": round(g_own, 3), "agg_gap": round(gap, 3),
                         "agg_ceiling_w": ceiling_w, "agg_lru": agg["lru"],
                         "seeds": a.seeds or ""})
    if a.csv:
        exists = os.path.exists(a.csv)
        with open(a.csv, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            if not exists:
                w.writeheader()
            w.writerows(rows)


# ---------------------------------------------------------------- split + dedicated

def cmd_split(a):
    splits = [int(x) for x in a.splits.split(",")]
    os.makedirs(a.outdir, exist_ok=True)
    base = os.path.splitext(os.path.basename(a.trace))[0]
    outs = [open(os.path.join(a.outdir, f"{base}.t{i}.lirs"), "w")
            for i in range(len(splits) + 1)]
    counts = [0] * len(outs)
    with open(a.trace) as f:
        for ln in f:
            key = int(ln)
            i = sum(1 for s in splits if key >= s)
            outs[i].write(ln)
            counts[i] += 1
    for o in outs:
        o.close()
    total = sum(counts)
    for i, c in enumerate(counts):
        print(f"t{i}: {c} requests ({100.0 * c / total:.2f}%)")


def cmd_dedicated(a):
    """Per-tenant standalone product runs at capacity fractions; best split vs shared."""
    splits = [int(x) for x in a.splits.split(",")]
    outdir = os.path.join(os.path.dirname(a.trace) or ".", "dedicated")
    base = os.path.splitext(os.path.basename(a.trace))[0]
    subs = [os.path.join(outdir, f"{base}.t{i}.lirs") for i in range(len(splits) + 1)]
    if not all(os.path.exists(s) for s in subs):
        a2 = argparse.Namespace(trace=a.trace, splits=a.splits, outdir=outdir)
        cmd_split(a2)
    fracs = [float(x) for x in a.fracs.split(",")]
    table = {}  # (tenant, frac) -> (hits, misses, rate)
    for i, sub in enumerate(subs):
        reqs = sum(1 for _ in open(sub))
        for frac in fracs:
            size = max(1, int(frac * a.size))
            hr, _ = product_arm(sub, size, [0], variant=a.variant)
            hits = round(reqs * hr / 100.0)
            table[(i, frac)] = (hits, reqs - hits, hr)
            print(f"t{i} frac={frac} size={size}: {hr:.2f} ({hits} hits / {reqs} reqs)")
    if len(subs) == 2:
        print("\nsplit search (f, 1-f of the shared capacity):")
        best = None
        for f1 in fracs:
            f2 = round(1.0 - f1, 4)
            if (1, f2) not in table:
                continue
            hits = table[(0, f1)][0] + table[(1, f2)][0]
            reqs = sum(table[(0, f1)][:2]) + sum(table[(1, f2)][:2])
            hr = 100.0 * hits / reqs
            print(f"  t0@{f1} + t1@{f2}: {hr:.2f} "
                  f"(t0 {table[(0, f1)][2]:.2f}, t1 {table[(1, f2)][2]:.2f})")
            if best is None or hr > best[0]:
                best = (hr, f1, f2)
        if best:
            print(f"best split: {best[0]:.2f} at t0@{best[1]}/t1@{best[2]}")
    if a.csv:
        with open(a.csv, "a", newline="") as f:
            w = csv.writer(f)
            for (i, frac), (h, m, hr) in sorted(table.items()):
                w.writerow([base, a.size, f"t{i}", frac, h, m, round(hr, 3)])


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for mode in ("patch", "verify", "strip"):
        p = sub.add_parser(mode)
        p.add_argument("tree")
    p = sub.add_parser("cell")
    p.add_argument("trace")
    p.add_argument("--size", type=int, required=True)
    p.add_argument("--fmt", default="lirs")
    p.add_argument("--splits", default="auto")
    p.add_argument("--windows")
    p.add_argument("--variants", default="hybrid")
    p.add_argument("--seeds")
    p.add_argument("--trace-seed", type=int)
    p.add_argument("--traces-dir", default="./traces")
    p.add_argument("--dump-dir", default="./dumps")
    p.add_argument("--refresh", action="store_true")
    p.add_argument("--csv")
    p = sub.add_parser("split")
    p.add_argument("trace")
    p.add_argument("--splits", required=True)
    p.add_argument("--outdir", required=True)
    p = sub.add_parser("dedicated")
    p.add_argument("trace")
    p.add_argument("--size", type=int, required=True)
    p.add_argument("--splits", required=True)
    p.add_argument("--fracs", default="0.05,0.1,0.2,0.3,0.5,0.7,0.8,0.9,0.95")
    p.add_argument("--variant", default="hybrid")
    p.add_argument("--csv")
    a = ap.parse_args()
    if a.cmd in ("patch", "verify", "strip"):
        run_patch(a.cmd, a.tree)
    elif a.cmd == "cell":
        cmd_cell(a)
    elif a.cmd == "split":
        cmd_split(a)
    elif a.cmd == "dedicated":
        cmd_dedicated(a)


if __name__ == "__main__":
    main()
