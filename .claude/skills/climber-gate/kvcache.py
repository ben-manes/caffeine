#!/usr/bin/env python3
"""The LLM prefix-cache cells: recency-favorable real traces the machine does not climb on.

`hill-climber.md` §3 (terrain classes): on the Qwen Bailian traces the static window response rises
toward 80%, a window planted there holds, and the MiniSim leader reaches it within one pass, but
the shipped climber starts starved at 1%. A walked window leaves the hot set in main and reads
sparse to the density law, the average law is bistable on the static terrain, and probes and audits
are judged across phase swings several times the window's effect. These cells price that, against
the To-C and To-B controls at 16384, whose static optimum is the 1% floor that a repair must keep.

A cell with too few density samples to hold a walk is replayed back to back (`passes`), as
`repeat.py` does: a pass's blocks recur only at the whole trace's distance, so LRU reads the same
on every pass. The gate row in SKILL.md carries the seeded records and bars. The traces read with
the `bailian` format, which keys each block by its prefix; Mooncake's own reader leaves To-C and
To-B unchained and overstates their reuse.

Seeded admission (`-Dcaffeine.climber.seed`) needs the harness worktree in CAF_TREE; on a stock
tree the seeds are ignored and every run is an independent draw. Missing traces are skipped.

Usage:
  kvcache.py <arm1,arm2,...> <out.csv> [seeds]     # seeds default 1-8
  kvcache.py --anchors <out.csv>                   # LRU, ARC and the static sweep only
"""
import csv, os, re, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

T = os.path.expanduser("~/Documents/traces/kvcache/qwen-bailian")
CELLS = [
    # (label, trace, size, passes)
    ("thinking@16384", f"{T}/qwen_thinking_blksz_16.jsonl", 16384, 1),
    ("thinking@32768x4", f"{T}/qwen_thinking_blksz_16.jsonl", 32768, 4),
    ("coder@65536", f"{T}/qwen_coder_blksz_16.jsonl", 65536, 1),
    ("toc@185364x8", f"{T}/qwen_traceA_blksz_16.jsonl", 185364, 8),
    ("toc@16384", f"{T}/qwen_traceA_blksz_16.jsonl", 16384, 1),
    ("tob@16384", f"{T}/qwen_traceB_blksz_16.jsonl", 16384, 1),
]
FMT = "bailian"
WINDOWS = [0.01, 0.20, 0.50, 0.80]


def replays(trace, passes):
  return [f"-Dcaffeine.simulator.files.paths.{i}={FMT}:{os.path.abspath(trace)}"
          for i in range(1, passes)]


def anchors(trace, size, passes):
  """Returns {policy: hit rate} for LRU, ARC and the static windows over the replayed trace."""
  args = ["-Dcaffeine.simulator.policies.0=linked.Lru",
          "-Dcaffeine.simulator.policies.1=adaptive.Arc",
          "-Dcaffeine.simulator.policies.2=sketch.WindowTinyLfu"]
  args += [f"-Dcaffeine.simulator.window-tiny-lfu.percent-main.{i}={1.0 - w:.2f}"
           for i, w in enumerate(WINDOWS)]
  r = R.gradle(size, trace, args + replays(trace, passes), FMT)
  out = {}
  for ln in r.stdout.splitlines():
    m = re.match(r"(linked\.Lru|adaptive\.Arc|sketch\.WindowTinyLfu \(\d+%\)),([\d.]+),", ln)
    if m:
      out[m.group(1)] = float(m.group(2))
  if "linked.Lru" not in out:
    sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
  return out


def main():
  if len(sys.argv) < 3:
    sys.exit(__doc__)
  if sys.argv[1] == "--anchors":
    with open(sys.argv[2], "w", newline="") as fh:
      w = csv.writer(fh)
      w.writerow(["cell", "size", "passes", "policy", "hit_rate"])
      for label, path, size, passes in CELLS:
        if not os.path.exists(path):
          print(f"SKIP {label}: missing {path}", flush=True)
          continue
        for policy, hr in anchors(path, size, passes).items():
          w.writerow([label, size, passes, policy, hr])
          print(label, policy, hr, flush=True)
        fh.flush()
    return

  arms = sys.argv[1].split(",")
  out = sys.argv[2]
  seeds = R.parse_seeds(sys.argv[3] if len(sys.argv) > 3 else "1,2,3,4,5,6,7,8")
  with open(out, "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["cell", "size", "passes"] + [c for a in arms for c in (a, a + "_sd", a + "_seeds")])
    for label, path, size, passes in CELLS:
      if not os.path.exists(path):
        print(f"SKIP {label}: missing {path}", flush=True)
        continue
      got = {a: [] for a in arms}
      for seed in seeds:
        for a in arms:                      # rotate arms inside each seed
          hr = R.variant(path, size, a, FMT, seed=seed, extra=replays(path, passes))[0]
          if hr is None:
            raise SystemExit(f"missing hit rate for {label} arm={a} seed={seed}")
          got[a].append(hr)
      row = [label, size, passes]
      for a in arms:
        v = got[a]
        row += [round(statistics.mean(v), 2), round(statistics.stdev(v), 2) if len(v) > 1 else 0,
                " ".join(f"{x:.2f}" for x in v)]
      w.writerow(row)
      fh.flush()
      print(" ".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
  main()
