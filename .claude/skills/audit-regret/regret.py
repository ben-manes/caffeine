#!/usr/bin/env python3
"""Regret of one (workload, size) cell: anchors, decomposition, and the machine's signature.

Regret is measured against what the climber could have earned, not against LRU or an absolute
hit rate. Anchors, all from one static sweep of `sketch.WindowTinyLfu` plus `linked.Lru`:
  start    the static window the product starts at (1%, or --start for a planted window)
  ceiling  the best static window in the reachable range (1..80%; the window cannot exceed 80%)
  LRU      the floor ("better than doing nothing"), and Belady with --belady (structural limit)
Per arm:
  gap      ceiling - cache (pp; the number gate rows are barred on)
  headroom ceiling - start (pp; the prize the climber exists to win; ~0 means a HOLD cell)
  closed   (cache - start) / headroom: the fraction of the prize captured (negative: moving hurt)
  missx    (miss_cache - miss_ceiling) / miss_ceiling: relative extra misses a user pays
With a harness tree (`climber-gate/harness.py apply`) the run also yields the per-sample
trajectory, from which the gap is decomposed and the machine's signature is read:
  position regret  mean over samples of ceiling - static(window_s), split at the settle point into
                   transient (before) and steady (after); the whole-trace static curve is the
                   reference, so on phase-structured workloads it is a blend, not a per-phase truth
  residual         gap - position regret: what the timing of the moves cost (churn, phase
                   interaction) or won (tracked a phase the blend cannot see)
  signature        mode occupancy, probes/audits armed/confirmed/crashed/failed, ladder and clock
                   extremes, floor/top occupancy, movement and sign flips, post-undo deficit
  hints            candidate failure classes from the signature (SKILL.md's table); the agent
                   adjudicates, the script only points

Usage:
  regret.py <trace.lirs | spec.json> --size N [--seeds 1,2,..,8 | --runs N] [--variants hybrid]
            [--start 0.01] [--belady] [--fmt lirs] [--traces-dir DIR] [--dump-dir DIR]
            [--csv out.csv] [--label NAME] [--max-override N] [--traj FILE]
  regret.py --traj FILE --size N --static '1:60.1,2:61.0,...' --lru 55.0   # analyze a dump alone
CAF_TREE selects the tree (a harness worktree for trajectories and ablation arms); CAF_EXTRA
appends properties to every run, as climber-gate's run.py does.
"""
import argparse, csv, hashlib, json, math, os, statistics, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.abspath(os.path.join(HERE, "..", "climber-gate"))
sys.path.insert(0, GATE)
sys.path.insert(0, HERE)
import run as R          # noqa: E402  (climber-gate's runner: gradle, curve, variant)
import workload as W     # noqa: E402

FLOOR, TOP = 0.024, 0.75          # at-floor and at-the-upper-corner window fractions
PLATEAU_PP = 0.5                  # within this of the ceiling counts as arrived
SHORT_SAMPLES = 40                # fewer decisions than this prices convergence, not the machine
FIELDS = ["label", "trace", "size", "variant", "seeds", "n", "lru", "belady", "start_hr",
          "ceiling", "ceiling_w", "peak_edge", "cache", "cache_min", "cache_max", "gap", "headroom",
          "closed", "missx", "structural", "pos_regret", "pos_transient", "pos_steady", "settle",
          "arrival", "residual", "floor_frac", "top_frac", "move", "flips", "steer", "park", "hold",
          "walk", "undo", "veto", "probes", "probe_confirm", "probe_crash", "probe_fail", "audits",
          "audit_confirm", "audit_crash", "audit_fail", "audit_refused", "max_rung", "max_arung",
          "max_wait", "held_frac", "early_confirm", "undo_deficit", "rest_err", "law", "needed",
          "pos_l3", "win_l3", "wstar", "progress", "quiet_l3", "bad_veto", "revisits", "repeat_arms",
          "settled", "resid_sd", "block_flips", "wander",
          "seed_hints", "hints"]


# ----------------------------------------------------------------------------- traces & anchors

def resolve_trace(path, traces_dir, max_override, seed_override):
    """A .json spec is generated (cached by content) into traces_dir; a trace path is used as is."""
    if not path.endswith(".json"):
        return path, None
    spec = W.load(path, max_override, seed_override)
    h = hashlib.md5(json.dumps(spec, sort_keys=True).encode()).hexdigest()[:8]
    name = os.path.splitext(os.path.basename(path))[0]
    out = os.path.join(traces_dir, f"{name}_m{spec['max']}_s{spec['seed']}_{h}.lirs")
    os.makedirs(traces_dir, exist_ok=True)
    if not os.path.exists(out):
        W.generate(spec, out)
    return out, spec


def anchors(trace, size, fmt, belady):
    """LRU, the static curve {pct: hr}, and Belady (or None), cached beside the trace."""
    side = f"{trace}.anchors.{size}.json"
    data = {}
    if os.path.exists(side):
        with open(side) as f:
            data = json.load(f)
    if "lru" not in data:
        lru, static = R.curve(trace, size, fmt)
        if lru is None or not static:
            sys.exit("anchor run failed (see simulator output above)")
        data.update({"lru": lru, "static": {str(k): v for k, v in static.items()}})
    if belady and "belady" not in data:
        r = R.gradle(size, trace, ["-Dcaffeine.simulator.policies.0=opt.Clairvoyant"], fmt)
        for ln in r.stdout.splitlines():
            if ln.startswith("opt.Clairvoyant,"):
                data["belady"] = float(ln.split(",")[1])
        if "belady" not in data:
            sys.stderr.write("!! Belady run produced no result\n" + r.stdout[-1500:] + r.stderr[-1500:])
    with open(side, "w") as f:
        json.dump(data, f)
    static = {int(k): v for k, v in data["static"].items()}
    return data["lru"], static, data.get("belady")


class Curve:
    """Piecewise-linear static hit rate by window percent, clamped to the swept range."""

    def __init__(self, static):
        self.pts = sorted(static.items())
        self.ceiling, self.ceiling_w = max((hr, w) for w, hr in self.pts)
        self.lo_w, self.hi_w = self.pts[0][0], self.pts[-1][0]

    def at(self, pct):
        pts = self.pts
        if pct <= pts[0][0]:
            return pts[0][1]
        if pct >= pts[-1][0]:
            return pts[-1][1]
        for (w0, h0), (w1, h1) in zip(pts, pts[1:]):
            if w0 <= pct <= w1:
                return h0 + (h1 - h0) * (pct - w0) / (w1 - w0)
        return pts[-1][1]


# ----------------------------------------------------------------------------- trajectory

def parse(lines):
    rows = []
    for ln in lines:
        d = {}
        for kv in ln.split()[1:]:
            if "=" in kv:
                k, v = kv.split("=", 1)
                d[k] = v
        rows.append(d)
    return rows


def group(mode):
    """The canonical group of a harness mode label."""
    if mode == "steer":
        return "steer"
    if mode == "park":
        return "park"
    if mode == "hold":
        return "hold"
    if mode == "undo":
        return "undo"
    if mode in ("VETO", "vetoRet"):
        return "veto"
    if mode.startswith("auditWalk"):
        return "auditWalk"
    if mode.startswith("walk"):
        return "walk"
    if mode == "AUDITCONFIRM":
        return "audit_confirm"
    if mode == "CONFIRM+steer":
        return "probe_confirm"
    if mode == "auditCrash":
        return "audit_crash"
    if mode == "auditFail":
        return "audit_fail"
    if mode == "Crash":
        return "probe_crash"
    if mode == "Fail":
        return "probe_fail"
    if mode == "AUDIT":
        return "audit_refused"
    if mode.startswith("AUDIT"):
        return "audit_arm"
    if mode == "ARM":
        return "probe_refused"
    if mode.startswith("ARM"):
        return "probe_arm"
    return "other"


def longest_run(flags):
    best = cur = 0
    for f in flags:
        cur = cur + 1 if f else 0
        best = max(best, cur)
    return best


def analyze(rows, size, curve, gap, lru, headroom=None):
    """Decompose one arm's gap with its trajectory and read the machine's signature."""
    n = len(rows)
    win = [int(r["win"]) / size for r in rows]
    hr = [float(r["hr"]) for r in rows]
    modes = [group(r.get("mode", "?")) for r in rows]
    static_at = [curve.at(100.0 * w) for w in win]
    pos = [curve.ceiling - s for s in static_at]
    wstar = curve.ceiling_w / 100.0

    # settle: the first index from which the whole remaining trajectory stays inside a 10% band.
    # A trajectory that only quiets over its last few samples has not settled, so a tail shorter
    # than max(8, n/4) does not count and `settled` stays false.
    tail_min = max(8, n // 4)
    settle = n
    for i in range(max(1, n - tail_min + 1)):
        seg = win[i:]
        if max(seg) - min(seg) <= 0.10:
            settle = i
            break
    settled = settle <= n - tail_min
    on_plateau = [s >= curve.ceiling - PLATEAU_PP for s in static_at]
    departure = next((i for i, p in enumerate(on_plateau) if not p), None)
    arrival = None
    if departure is not None:
        arrival = next((i for i in range(departure, n) if on_plateau[i]), None)
    pos_regret = statistics.mean(pos) if n else float("nan")
    pos_transient = sum(pos[:settle]) / n if n else float("nan")
    pos_steady = sum(pos[settle:]) / n if n else float("nan")
    third = max(1, n // 3)
    pos_l3 = statistics.mean(pos[-third:])
    win_l3 = statistics.mean(win[-third:])
    blocks = [statistics.mean(win[i * n // 6:(i + 1) * n // 6] or [win[-1]]) for i in range(6)]
    dist_first = abs(statistics.mean(blocks[:2]) - wstar)
    dist_last = abs(statistics.mean(blocks[-2:]) - wstar)
    progress = dist_first - dist_last

    deltas = [b - a for a, b in zip(win, win[1:])]
    move = statistics.mean(abs(d) for d in deltas) if deltas else 0.0
    signs = [d for d in deltas if abs(d) > 0.005]
    flips = sum(1 for a, b in zip(signs, signs[1:]) if (a > 0) != (b > 0))
    counts = {}
    for m in modes:
        counts[m] = counts.get(m, 0) + 1
    c = counts.get
    held = [r.get("hold") == "1" for r in rows]
    early_confirm = next((i for i, m in enumerate(modes) if m == "audit_confirm" and i < 24
                          and static_at[i] < curve.ceiling - 1.0), None)
    quiet_l3 = sum(1 for m in modes[-third:] if m in ("park", "hold", "veto")) / third
    steer_l3 = sum(1 for m in modes[-third:] if m == "steer") / third

    # a veto that drags the window to an anchor whose static value is below the position it
    # leaves, on a claim whose rate is far above the current smoothed rate: a stale claim
    bad_veto = 0
    for i, m in enumerate(modes):
        if m == "veto":
            aw = int(rows[i].get("anchorW", -1))
            if aw >= 0 and curve.at(100.0 * aw / size) < static_at[i] - 1.0 \
                    and float(rows[i].get("anchorR", 0)) - float(rows[i].get("ema", 0)) >= 0.03:
                bad_veto += 1

    # post-undo deficit: hr - static(win) over the 3 samples after each undo/crash, minus the
    # same quantity elsewhere; a positive value means recovering the position did not recover
    # the hit rate (displaced residents)
    resid = [h * 100 - s for h, s in zip(hr, static_at)]
    resid_sd = statistics.pstdev(resid) if n > 1 else 0.0
    after, elsewhere = [], []
    marks = set()
    for i, m in enumerate(modes):
        if m in ("undo", "probe_crash", "audit_crash", "probe_fail", "audit_fail"):
            marks.update(range(i + 1, min(n, i + 4)))
    for i, v in enumerate(resid):
        (after if i in marks else elsewhere).append(v)
    undo_deficit = (statistics.mean(elsewhere) - statistics.mean(after)) if after and elsewhere else 0.0

    # density error at rest (last third): ln(windowDensity / mainDensity); its sign is what the
    # steering law wants, to compare against the direction the ceiling window lies in
    def err(r):
        w = max(1, int(r["win"]))
        wh, mh = float(r.get("wh", 0)), float(r.get("mh", 0))
        return math.log((wh / w + 1e-9) / (mh / max(1, size - w) + 1e-9))
    rest_err = statistics.mean(err(r) for r in rows[-third:])
    law = 0 if abs(rest_err) < 0.15 else (1 if rest_err > 0 else -1)
    needed = 0 if abs(wstar - win_l3) < 0.05 else (1 if wstar > win_l3 else -1)

    # repeat probes: an arm (probe or audit) launched in a direction and from a window band where
    # an earlier arm already ended in a crash or a failure, so the machine re-runs an experiment
    # whose outcome it has seen; the ladders are the memory, and this counts what leaks past them
    failed_from, repeat_arms, open_arm = set(), 0, None
    for i, m in enumerate(modes):
        if m in ("probe_arm", "audit_arm"):
            mode = rows[i].get("mode", "")
            key = ("dn" if mode.endswith("dn") else "up", int(win[i] / 0.05))
            if key in failed_from:
                repeat_arms += 1
            open_arm = key
        elif m in ("probe_crash", "probe_fail", "audit_crash", "audit_fail") and open_arm:
            failed_from.add(open_arm)
            open_arm = None
        elif m in ("probe_confirm", "audit_confirm"):
            open_arm = None

    # revisits: distinct excursions that return into a 5%-wide band the trajectory already left
    bands, revisits, cur = set(), 0, None
    for w in win:
        b = int(w / 0.05)
        if b != cur:
            if b in bands:
                revisits += 1
            bands.add(b)
            cur = b

    bdeltas = [b - a for a, b in zip(blocks, blocks[1:]) if abs(b - a) > 0.02]
    block_flips = sum(1 for a, b in zip(bdeltas, bdeltas[1:]) if (a > 0) != (b > 0))
    # wander: path length over the span it covers. A direct approach reads ~1; a control loop
    # following the workload retraces the same ground and reads several times that.
    wander = sum(abs(d) for d in deltas) / max(0.05, max(win) - min(win))

    def imax(key):
        return max((int(r.get(key, 0)) for r in rows), default=0)

    sig = {
        "n": n, "settle": settle, "settled": settled, "departure": departure, "arrival": arrival,
        "pos_regret": pos_regret, "pos_transient": pos_transient, "pos_steady": pos_steady,
        "pos_l3": pos_l3, "win_l3": win_l3, "wstar": wstar, "progress": progress,
        "dist_first": dist_first, "residual": gap - pos_regret,
        "floor_frac": sum(1 for w in win if w <= FLOOR) / n, "top_frac": sum(1 for w in win if w >= TOP) / n,
        "move": move, "flips": flips, "steer": c("steer", 0), "park": c("park", 0), "hold": c("hold", 0),
        "walk": c("walk", 0) + c("auditWalk", 0), "undo": c("undo", 0), "veto": c("veto", 0),
        "bad_veto": bad_veto, "quiet_l3": quiet_l3, "steer_l3": steer_l3,
        "probes": c("probe_arm", 0), "probe_confirm": c("probe_confirm", 0),
        "probe_crash": c("probe_crash", 0), "probe_fail": c("probe_fail", 0),
        "audits": c("audit_arm", 0), "audit_confirm": c("audit_confirm", 0),
        "audit_crash": c("audit_crash", 0), "audit_fail": c("audit_fail", 0),
        "audit_refused": c("audit_refused", 0), "max_rung": imax("rung"), "max_arung": imax("arung"),
        "max_wait": imax("auditWait"), "held_frac": sum(held) / n, "early_confirm": early_confirm,
        "undo_deficit": undo_deficit, "resid_sd": resid_sd, "block_flips": block_flips,
        "wander": wander,
        "rest_err": rest_err, "law": law, "needed": needed,
        "revisits": revisits, "repeat_arms": repeat_arms,
        "longest_park": longest_run([m == "park" for m in modes]),
        "longest_hold": longest_run([m == "hold" for m in modes]),
        "blocks": blocks,
    }
    sig["hints"] = hints(sig, gap, curve, headroom)
    return sig


def hints(s, gap, curve, headroom=None):
    """Candidate failure classes from the signature; SKILL.md's table is the definition. The
    procedure: is the window still in the wrong place at the end? If so, does the steering law
    want the move the ceiling calls for (then something above it is holding the window: the
    recovery layer) or not (then the law's own rest point is wrong: the controller)?"""
    out = []
    n = s["n"]
    if n < SHORT_SAMPLES:
        out.append("SHORT")
    if gap < 1.0:
        # a negative gap is not an error: the anchor is the best STATIC window, which an adaptive
        # window can beat where the workload's phases want different splits
        return out + (["clean"] if gap > -1.0 else ["beats-static-ceiling"])
    if curve.ceiling_w >= curve.hi_w:
        out.append("peak-at-edge")
    hold_cell = headroom is not None and headroom < 1.0
    if hold_cell:
        out.append("left-optimum")
    wrong_at_end = s["pos_l3"] >= max(1.0, 0.5 * gap)
    converging = s["dist_first"] > 0.05 and s["progress"] >= 0.3 * s["dist_first"]
    if not s["settled"] and s["wander"] >= 3.0 and (s["flips"] >= n // 4 or s["block_flips"] >= 2):
        out.append("oscillation")
    walks_ended = s["probe_crash"] + s["probe_fail"] + s["audit_crash"] + s["audit_fail"]
    confirms = s["probe_confirm"] + s["audit_confirm"]
    if wrong_at_end:
        if s["law"] != 0 and s["law"] == s["needed"]:
            if s["quiet_l3"] >= 0.5:
                out.append("held-against-the-law")
                if s["bad_veto"] >= 1:
                    out.append("memory-failure/stale-claim")
                if s["early_confirm"] is not None:
                    out.append("premature-commitment")
                if s["audits"] <= max(1, n // 64) or s["max_wait"] >= 128 or s["max_rung"] >= 64:
                    out.append("insufficient-exploration")
            elif s["floor_frac"] >= 0.5 or s["top_frac"] >= 0.5:
                out.append("insufficient-exploration/reach")
            else:
                out.append("steer-blocked")
        else:
            sighted = (s["probes"] == 0) and (s["steer_l3"] >= 0.5)
            out.append("wrong-equilibrium" + ("/masked-signal" if sighted else ""))
            if s["floor_frac"] >= 0.5 and walks_ended >= 3 and confirms == 0:
                out.append("insufficient-exploration/reach")
    elif converging or (s["arrival"] is not None and s["arrival"] >= n // 3) \
            or s["pos_transient"] >= 0.6 * s["pos_regret"]:
        out.append("slow-convergence" + ("/walk-paced" if s["walk"] >= n // 4 else ""))
    elif s["pos_l3"] >= 1.0:
        out.append("wrong-equilibrium/mild")
    if walks_ended >= 3 and confirms == 0 and s["needed"] != 0 and "insufficient-exploration/reach" not in out:
        out.append("insufficient-exploration/reach")
    if s["undo_deficit"] >= max(2.0, s["resid_sd"]):
        out.append("irreversible-damage")
    elif s["undo_deficit"] >= 2.0:
        out.append("irreversible-damage?/phase-confounded")
    if s["repeat_arms"] >= 3:
        out.append("memory-failure/repeat-probe")
    if not [h for h in out if h not in ("SHORT", "peak-at-edge", "left-optimum", "oscillation")]:
        # name the dominant component rather than nothing: the gap is real either way
        out.append("slow-convergence" if s["pos_transient"] >= s["pos_steady"] else "wrong-equilibrium")
    return out


# ----------------------------------------------------------------------------- driver

def measure(trace, size, fmt, variants, seeds, runs, extra, dump_dir, label):
    """Runs each variant over the seeds (or N unseeded runs); returns {variant: (hrs, [lines...])}."""
    os.makedirs(dump_dir, exist_ok=True)
    out = {}
    passes = seeds if seeds else list(range(runs))
    for v in variants:
        hrs, dumps = [], []
        for i, s in enumerate(passes):
            tag = f"s{s}" if seeds else f"r{i}"
            dump = os.path.join(dump_dir, f"{label}.{v}.{tag}.traj")
            hr, lines = R.variant(trace, size, v, fmt, debug=True, dump=dump,
                                  seed=(s if seeds else None), extra=extra)
            if hr is None:
                sys.stderr.write(f"!! {label} {v} {tag}: no result\n")
                continue
            hrs.append(hr)
            dumps.append(lines)
        out[v] = (hrs, dumps)
    return out


def fmt_row(label, trace, size, v, seeds, lru, belady, start_hr, curve, hrs, sig, gap, headroom,
            closed, missx):
    row = {k: "" for k in FIELDS}
    row.update({
        "label": label, "trace": os.path.basename(trace), "size": size, "variant": v,
        "seeds": "/".join(map(str, seeds)) if seeds else f"x{len(hrs)}",
        "lru": f"{lru:.2f}", "belady": f"{belady:.2f}" if belady is not None else "",
        "start_hr": f"{start_hr:.2f}", "ceiling": f"{curve.ceiling:.2f}", "ceiling_w": curve.ceiling_w,
        "peak_edge": int(curve.ceiling_w >= curve.hi_w),
        "cache": f"{statistics.mean(hrs):.2f}", "cache_min": f"{min(hrs):.2f}",
        "cache_max": f"{max(hrs):.2f}", "gap": f"{gap:.2f}", "headroom": f"{headroom:.2f}",
        "closed": f"{closed:.2f}" if closed is not None else "",
        "missx": f"{missx:.3f}",
        "structural": f"{belady - curve.ceiling:.2f}" if belady is not None else "",
    })
    if sig:
        for k in FIELDS:
            if k in sig and row[k] == "":
                val = sig[k]
                row[k] = ("" if val is None else
                          f"{val:.2f}" if isinstance(val, float) else
                          "|".join(val) if isinstance(val, list) else val)
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", nargs="?", help="trace file or workload spec (.json)")
    ap.add_argument("--size", type=int)
    ap.add_argument("--seeds", default=None, help="comma-separated admission seeds (harness)")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--variants", default="hybrid")
    ap.add_argument("--start", type=float, default=0.01,
                    help="the window fraction the product starts at (harness startwin if != 0.01)")
    ap.add_argument("--belady", action="store_true")
    ap.add_argument("--fmt", default="lirs")
    ap.add_argument("--traces-dir", default=os.path.join(os.getcwd(), "traces"))
    ap.add_argument("--dump-dir", default=os.path.join(os.getcwd(), "dumps"))
    ap.add_argument("--csv", default=None)
    ap.add_argument("--label", default=None)
    ap.add_argument("--max-override", type=int, default=None, help="regenerate a spec at this maximum")
    ap.add_argument("--seed-override", type=int, default=None, help="regenerate a spec with this seed")
    ap.add_argument("--traj", default=None, help="analyze an existing dump instead of running")
    ap.add_argument("--static", default=None, help="with --traj and no trace: 'pct:hr,...'")
    ap.add_argument("--lru", type=float, default=None)
    ap.add_argument("--json", default=None, help="write the full analysis here")
    a = ap.parse_args()

    if a.traj and a.static:
        static = {int(k): float(v) for k, v in (kv.split(":") for kv in a.static.split(","))}
        curve = Curve(static)
        with open(a.traj) as f:
            rows = parse([ln for ln in f if ln.startswith("climb ")])
        size = a.size or int(rows[0]["max"])
        cache = statistics.mean(float(r["hr"]) for r in rows) * 100
        gap = curve.ceiling - cache
        sig = analyze(rows, size, curve, gap, a.lru or 0.0, curve.ceiling - curve.at(1.0))
        report_arm("dump", a.traj, size, a.lru or float("nan"), None, curve.at(1.0), curve,
                   [cache], sig, None, [sig])
        if a.json:
            with open(a.json, "w") as f:
                json.dump(sig, f, indent=1)
        return

    if not a.input:
        sys.exit("need a trace or spec (or --traj with --static)")
    trace, spec = resolve_trace(a.input, a.traces_dir, a.max_override, a.seed_override)
    size = a.size or (int(spec["max"]) if spec else None)
    if size is None:
        sys.exit("--size is required for a trace file")
    label = a.label or os.path.splitext(os.path.basename(trace))[0]
    seeds = [int(s) for s in a.seeds.split(",")] if a.seeds else None
    variants = [v for v in a.variants.split(",") if v]
    extra = []
    if abs(a.start - 0.01) > 1e-9:
        extra.append(f"-Dcaffeine.climber.startwin={a.start}")

    if a.traj:
        with open(a.traj) as f:
            lines = [ln.rstrip("\n") for ln in f if ln.startswith("climb ")]
        results = {"dump": ([statistics.mean(float(r["hr"]) for r in parse(lines)) * 100], [lines])}
    else:
        results = None
    rows_out = evaluate_cell(trace, spec, size, a.fmt, variants, seeds, a.runs, a.start, a.belady,
                             a.dump_dir, label, results=results, json_path=a.json)
    if a.csv and rows_out:
        new = not os.path.exists(a.csv)
        with open(a.csv, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            if new:
                w.writeheader()
            w.writerows(rows_out)


def evaluate_cell(trace, spec, size, fmt, variants, seeds, runs, start, belady, dump_dir, label,
                  results=None, json_path=None):
    """Anchors, product runs (unless `results` is supplied), decomposition and report for one
    cell; returns one CSV row per arm. Shared by regret.py and search.py so the two cannot drift."""
    extra = [] if abs(start - 0.01) < 1e-9 else [f"-Dcaffeine.climber.startwin={start}"]
    lru, static, bel = anchors(trace, size, fmt, belady)
    curve = Curve(static)
    start_hr = curve.at(100.0 * start)
    headroom = curve.ceiling - start_hr
    if spec:
        print(f"{label}: {W.samples(spec)} samples ({W.requests(spec)} requests) at max={spec['max']}")
    print(f"anchors @{size}: LRU={lru:.2f}  start({start:.0%})={start_hr:.2f}  "
          f"ceiling={curve.ceiling:.2f}@{curve.ceiling_w}%"
          + (f"  Belady={bel:.2f}" if bel is not None else "")
          + ("  [peak at the swept edge]" if curve.ceiling_w >= curve.hi_w else ""), flush=True)
    if results is None:
        results = measure(trace, size, fmt, variants, seeds, runs, extra, dump_dir, label)
    rows_out = []
    for v, (hrs, dumps) in results.items():
        if not hrs:
            continue
        cache = statistics.mean(hrs)
        gap = curve.ceiling - cache
        closed = ((cache - start_hr) / headroom) if headroom >= 1.0 else None
        missx = ((100 - cache) - (100 - curve.ceiling)) / max(1e-9, 100 - curve.ceiling)
        sigs = per_seed(dumps, hrs, size, curve, lru, headroom)
        sig = representative(sigs, hrs, cache)
        report_arm(v, trace, size, lru, bel, start_hr, curve, hrs, sig, seeds, sigs)
        row = fmt_row(label, trace, size, v, seeds, lru, bel, start_hr, curve, hrs,
                      sig, gap, headroom, closed, missx)
        if sigs:
            row["seed_hints"] = seed_hints(sigs)
            row["hints"] = "|".join(vote_hints(sigs))
        rows_out.append(row)
        if json_path and sigs:
            path = json_path if len(results) == 1 else f"{json_path}.{v}.json"
            with open(path, "w") as f:
                json.dump({"row": row, "signatures": sigs, "hrs": hrs}, f, indent=1)
    return rows_out


def per_seed(dumps, hrs, size, curve, lru, headroom):
    """One signature per dump, each judged against its own run's hit rate."""
    sigs = []
    for hr, lines in zip(hrs, dumps):
        if lines:
            sigs.append(analyze(parse(lines), size, curve, curve.ceiling - hr, lru, headroom))
        else:
            sigs.append(None)
    return [x for x in sigs if x]


def representative(sigs, hrs, mean_hr):
    """The signature of the run nearest the arm's mean hit rate (the detail block shows one)."""
    if not sigs:
        return None
    pairs = [(abs(h - mean_hr), i) for i, h in enumerate(hrs[:len(sigs)])]
    return sigs[min(pairs)[1]]


def vote_hints(sigs):
    """Hints across seeds with their counts, most common first: 'slow-convergence x3'."""
    counts = {}
    for sg in sigs:
        for h in sg["hints"]:
            counts[h] = counts.get(h, 0) + 1
    return [f"{h} x{c}" if len(sigs) > 1 else h
            for h, c in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]


def seed_hints(sigs):
    return " ; ".join(",".join(sg["hints"]) for sg in sigs)


def report_arm(v, trace, size, lru, belady, start_hr, curve, hrs, sig, seeds=None, sigs=None):
    cache = statistics.mean(hrs)
    gap = curve.ceiling - cache
    headroom = curve.ceiling - start_hr
    spread = f"±{(max(hrs) - min(hrs)) / 2:.2f}" if len(hrs) > 1 else ""
    closed = f"closed={(cache - start_hr) / headroom:.2f}" if headroom >= 1.0 else "HOLD cell (headroom<1pp)"
    missx = ((100 - cache) - (100 - curve.ceiling)) / max(1e-9, 100 - curve.ceiling)
    print(f"  {v:9s} hr={cache:6.2f}{spread}  gap={gap:+.2f}  headroom={headroom:.2f}  {closed}  "
          f"missx={missx:+.1%}  vsLRU={cache - lru:+.2f}"
          + (f"  structural={belady - curve.ceiling:.2f}" if belady is not None else ""))
    if len(hrs) > 1:
        print("            per-seed " + " ".join(f"{h:.2f}" for h in hrs))
    if not sig:
        print("            no trajectory (stock build or debug off): end-to-end regret only")
        return
    s = sig
    print(f"            n={s['n']} settle@{s['settle']}{'' if s['settled'] else '(never)'} "
          f"arrival@{s['arrival']} "
          f"pos={s['pos_regret']:.2f} (transient {s['pos_transient']:.2f} + steady {s['pos_steady']:.2f}) "
          f"residual={s['residual']:+.2f}")
    print(f"            window blocks " + " ".join(f"{b:.2f}" for b in s["blocks"])
          + f"  floor={s['floor_frac']:.0%} top={s['top_frac']:.0%} move={s['move']:.3f} "
          f"flips={s['flips']}/{s['block_flips']} wander={s['wander']:.1f} revisits={s['revisits']}")
    print(f"            modes steer={s['steer']} park={s['park']} hold={s['hold']} walk={s['walk']} "
          f"undo={s['undo']} veto={s['veto']}  longest park={s['longest_park']} hold={s['longest_hold']} "
          f"held={s['held_frac']:.0%}")
    print(f"            probes armed={s['probes']} confirm={s['probe_confirm']} crash={s['probe_crash']} "
          f"fail={s['probe_fail']} | audits armed={s['audits']} confirm={s['audit_confirm']} "
          f"crash={s['audit_crash']} fail={s['audit_fail']} refused={s['audit_refused']} | "
          f"rung<= {s['max_rung']} arung<= {s['max_arung']} wait<= {s['max_wait']}")
    want = {1: "grow", -1: "shrink", 0: "hold"}
    print(f"            last third: win={s['win_l3']:.2f} vs w*={s['wstar']:.2f} pos={s['pos_l3']:.2f} "
          f"quiet={s['quiet_l3']:.0%} law wants {want[s['law']]} (rest_err {s['rest_err']:+.2f}), "
          f"needed {want[s['needed']]}; progress={s['progress']:+.2f} of {s['dist_first']:.2f}")
    print(f"            early_confirm={s['early_confirm']} bad_veto={s['bad_veto']} "
          f"repeat_arms={s['repeat_arms']} undo_deficit={s['undo_deficit']:+.2f} "
          f"(hr scatter {s['resid_sd']:.2f})  "
          f"hints: {', '.join(s['hints'])}")
    if sigs and len(sigs) > 1:
        tags = [f"s{x}" for x in seeds] if seeds else [f"r{i}" for i in range(len(sigs))]
        for tag, h, sg in zip(tags, hrs, sigs):
            print(f"            {tag:4s} hr={h:6.2f} blocks " + " ".join(f"{b:.2f}" for b in sg["blocks"])
                  + f"  {', '.join(sg['hints'])}")
        print(f"            across seeds: {'; '.join(vote_hints(sigs))}")


if __name__ == "__main__":
    main()
