#!/usr/bin/env python3
"""Compositional workload generator: a JSON spec of phases over named primitives -> a lirs trace.

The regret audit searches over workloads, so a workload has to be a data structure that can be
mutated, shrunk, and swept, not a hand-written script per shape. Every size is relative to the
cell's maximum (the density trap is scale-relative: the floor is 2% of max), and time is measured
in density samples (4 x max requests each), so a spec at one maximum re-synthesizes at another and
its decision budget is visible before a run.

Spec:
  {
    "max": 8192, "seed": 7, "repeat": 1,
    "members": {
      "hot":  {"kind": "zipf",  "n": 0.6, "alpha": 0.9},
      "band": {"kind": "pairs", "d": 0.25, "k": 2},
      "rider": {"kind": "trickle", "gap": 1}
    },
    "segments": [
      {"samples": 30, "shares": {"hot": 0.7, "band": 0.3, "rider": 0.002}},
      {"samples": 30, "shares": {"hot": 1.0,
                                 "band": {"share": 0.3, "mod": {"kind": "sine", "period": 6, "amp": 0.8}}}}
    ]
  }

Members (sizes x max unless noted; each owns a disjoint key space, so two members never alias):
  zipf     n, alpha           steady popularity over n keys (feeds main, defends it by frequency)
  loop     n                  cyclic scan over n keys (a frequency loop; > max thrashes LRU)
  uniform  n                  uniform over n keys
  scan     -                  one-shot keys, never reused
  pairs    d | dreq, k        each fresh key is referenced k times at spacing d (x max) or dreq
                              (requests), then retired; k=2 is the recency band, k=6 at a large d
                              is the norank shape (every key seen k times, the sketch cannot rank)
  trickle  gap                a fresh key re-read `gap` requests later (whisper's rider: pairs k=2)
  rotation n, step, period    uniform over a hot set of n keys that shifts by step x max keys
                              every period samples (a drifting regime)
Re-references (pairs, trickle) are due at an exact global index and pre-empt the share draw, as
gen.py's delay ring does, so the reuse distance is exact and the member's fresh-key share is what
the spec says.

Shares are per segment, renormalized per request. A share may carry a modulation of its weight
over the segment's time t (in samples): sine/square (period, amp, phase) or ramp (amp: from 1-amp
to 1+amp across the segment). A member absent from a segment's shares is silent there but keeps
its state (a hot set persists across a phase that does not reference it).

Usage:
  workload.py spec.json --out trace.lirs [--max 16384] [--seed 11]
  workload.py spec.json --describe          # samples, requests, per-segment composition; no output
"""
import argparse, bisect, heapq, json, math, os, random, sys

BLOCK = 1 << 40  # key-space stride per member


def load(path, max_=None, seed=None):
    with open(path) as f:
        spec = json.load(f)
    if max_ is not None:
        spec["max"] = max_
    if seed is not None:
        spec["seed"] = seed
    spec.setdefault("seed", 7)
    spec.setdefault("repeat", 1)
    if "max" not in spec or "members" not in spec or "segments" not in spec:
        sys.exit("spec needs max, members, segments")
    return spec


def sample_period(spec):
    return 4 * int(spec["max"])


def samples(spec):
    return int(spec["repeat"]) * sum(int(s["samples"]) for s in spec["segments"])


def requests(spec):
    return samples(spec) * sample_period(spec)


class Zipf:
    def __init__(self, n, alpha, rng):
        w = [1.0 / (i ** alpha) for i in range(1, n + 1)]
        t = sum(w)
        acc, self.cum = 0.0, []
        for x in w:
            acc += x / t
            self.cum.append(acc)
        self.rng = rng

    def __call__(self):
        return bisect.bisect_left(self.cum, self.rng.random())


class Member:
    """One primitive: `fresh(g)` emits a new reference at global index g and may schedule
    re-references through `due` (a heap of (index, serial, key) shared by all members)."""

    def __init__(self, name, cfg, index, max_, rng, due):
        self.name, self.kind, self.cfg, self.rng, self.due = name, cfg["kind"], cfg, rng, due
        self.base = (index + 1) * BLOCK
        self.max = max_
        self.next = 0        # fresh-key counter (scan, pairs, trickle)
        self.cursor = 0      # loop
        self.offset = 0      # rotation
        k = self.kind
        if k == "zipf":
            self.n = max(1, int(cfg["n"] * max_))
            self.zipf = Zipf(self.n, float(cfg.get("alpha", 0.9)), rng)
        elif k in ("loop", "uniform"):
            self.n = max(1, int(cfg["n"] * max_))
        elif k == "pairs":
            self.k = int(cfg.get("k", 2))
            self.d = int(cfg["dreq"]) if "dreq" in cfg else max(1, int(float(cfg["d"]) * max_))
        elif k == "trickle":
            self.k = 2
            self.d = int(cfg.get("gap", 1))
        elif k == "rotation":
            self.n = max(1, int(cfg["n"] * max_))
            self.step = max(1, int(float(cfg["step"]) * max_))
            self.period = float(cfg.get("period", 1.0))
        elif k == "scan":
            pass
        else:
            sys.exit(f"unknown member kind {k!r} ({name})")

    def fresh(self, g, t):
        """A fresh reference at global index g, sample time t (float samples since trace start)."""
        k = self.kind
        if k == "zipf":
            return self.base + self.zipf()
        if k == "uniform":
            return self.base + self.rng.randrange(self.n)
        if k == "loop":
            key = self.base + self.cursor
            self.cursor = (self.cursor + 1) % self.n
            return key
        if k == "scan":
            key = self.base + self.next
            self.next += 1
            return key
        if k in ("pairs", "trickle"):
            key = self.base + self.next
            self.next += 1
            for j in range(1, self.k):
                heapq.heappush(self.due, (g + j * self.d, self.next, key))
            return key
        if k == "rotation":
            shift = int(t / self.period) * self.step
            return self.base + shift + self.rng.randrange(self.n)
        raise AssertionError(k)


def base_share(entry):
    """A segment share entry's unmodulated share (number or {share, mod})."""
    return float(entry) if isinstance(entry, (int, float)) else float(entry["share"])


def weight(entry, t, seg_samples):
    """A segment share entry (number or {share, mod}) evaluated at segment time t (samples)."""
    if isinstance(entry, (int, float)):
        return float(entry)
    share = float(entry["share"])
    mod = entry.get("mod")
    if not mod:
        return share
    kind, amp = mod["kind"], float(mod.get("amp", 0.5))
    if kind == "sine":
        return share * (1.0 + amp * math.sin(2 * math.pi * (t / float(mod["period"]))
                                             + float(mod.get("phase", 0.0))))
    if kind == "square":
        s = math.sin(2 * math.pi * (t / float(mod["period"])) + float(mod.get("phase", 0.0)))
        return share * (1.0 + amp * (1.0 if s >= 0 else -1.0))
    if kind == "ramp":
        return share * (1.0 - amp + 2.0 * amp * (t / max(1e-9, seg_samples)))
    sys.exit(f"unknown mod kind {kind!r}")


def describe(spec):
    per = sample_period(spec)
    print(f"max={spec['max']} seed={spec['seed']} period={per} samples={samples(spec)} "
          f"requests={requests(spec)} repeat={spec['repeat']}")
    for i, seg in enumerate(spec["segments"]):
        parts = []
        for name, entry in seg["shares"].items():
            m = spec["members"][name]
            desc = ",".join(f"{k}={v}" for k, v in m.items() if k != "kind")
            w = weight(entry, 0.0, seg["samples"])
            mod = "" if isinstance(entry, (int, float)) or not entry.get("mod") else \
                f" mod={entry['mod']['kind']}"
            parts.append(f"{name}[{m['kind']} {desc}]={w:g}{mod}")
        print(f"  seg{i}: {seg['samples']} samples  " + "  ".join(parts))


def generate(spec, out):
    max_ = int(spec["max"])
    rng = random.Random(int(spec["seed"]))
    due = []
    members = {name: Member(name, cfg, i, max_, rng, due)
               for i, (name, cfg) in enumerate(spec["members"].items())}
    per = sample_period(spec)
    plan = []  # (member list, entry list, seg_samples) per segment, repeated
    for _ in range(int(spec["repeat"])):
        for seg in spec["segments"]:
            names = list(seg["shares"].keys())
            for n in names:
                if n not in members:
                    sys.exit(f"segment references unknown member {n!r}")
            plan.append(([members[n] for n in names], [seg["shares"][n] for n in names],
                         int(seg["samples"])))
    g = 0
    buf = []
    with open(out, "w") as f:
        for mems, entries, seg_samples in plan:
            seg_start = g
            total = seg_samples * per
            # weights are re-evaluated once per 1/16 sample so modulation is smooth but cheap
            chunk = max(1, per // 16)
            for c0 in range(0, total, chunk):
                t = (c0) / per
                ws = [max(0.0, weight(e, t, seg_samples)) for e in entries]
                cum, acc = [], 0.0
                for w in ws:
                    acc += w
                    cum.append(acc)
                if acc <= 0:
                    sys.exit("a segment has no positive share")
                for _ in range(min(chunk, total - c0)):
                    if due and due[0][0] <= g:
                        _, _, key = heapq.heappop(due)
                    else:
                        r = rng.random() * acc
                        key = mems[bisect.bisect_left(cum, r)].fresh(g, (g) / per)
                    buf.append(key)
                    g += 1
                    if len(buf) >= 65536:
                        f.write("\n".join(map(str, buf)) + "\n")
                        buf.clear()
            assert g - seg_start == total
        if buf:
            f.write("\n".join(map(str, buf)) + "\n")
    return g


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec")
    ap.add_argument("--out")
    ap.add_argument("--max", type=int)
    ap.add_argument("--seed", type=int)
    ap.add_argument("--describe", action="store_true")
    a = ap.parse_args()
    spec = load(a.spec, a.max, a.seed)
    if a.describe or not a.out:
        describe(spec)
        if not a.out:
            return
    n = generate(spec, a.out)
    print(f"{a.out}: {n} requests ({samples(spec)} samples of {sample_period(spec)} at max={spec['max']})")


if __name__ == "__main__":
    main()
