#!/usr/bin/env python3
"""Measure the audit layer's natural EXPOSURE on (trace, size) cells.

Requires a CAF_TREE worktree carrying the skill's harness (`harness.py apply`) — the
per-sample telemetry is what this reads. For each cell it reports how often the density
arm holds the window still enough for the audit clock to run (`still` = fraction of
samples with stableSamples > 0, `maxrun` = the longest stillness count reached), how
many audits and probes actually armed, and the hit rate per variant. A cell whose
maxrun never reaches the audit wait can never re-audit; one that never reaches the
calibration wait never even calibrates (the corda blind-hold and w097 at-the-bar
findings both came from this instrument).

Usage:
  exposure.py --csv out.csv --cells 'name|fmt|path|size[;...]' [--variants hybrid,noaudit]
"""
import argparse, csv, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402


def stats(lines):
    st = [dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv) for ln in lines]
    if not st:
        return None
    stable = [int(d["stable"]) for d in st]
    modes = [d["mode"] for d in st]
    return {
        "n": len(st),
        "still": round(sum(1 for s in stable if s > 0) / len(st), 3),
        "maxrun": max(stable),
        "audits": sum(1 for m in modes if m.startswith("AUDIT") and "CONFIRM" not in m),
        "confirms": sum(1 for m in modes if "AUDITCONFIRM" in m),
        "probes": sum(1 for m in modes if m.startswith("ARM")),
        "meanwin": round(sum(int(d["win"]) for d in st) / len(st), 1),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--cells", required=True, help="'name|fmt|path|size' cells, ';'-separated")
    ap.add_argument("--variants", default="hybrid,noaudit")
    args = ap.parse_args()

    fields = ["cell", "size", "variant", "hr", "n", "still", "maxrun",
              "audits", "confirms", "probes", "meanwin"]
    with open(args.csv, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(fields)
        for spec in args.cells.split(";"):
            name, fmt, path, size = spec.split("|")
            for v in args.variants.split(","):
                hr, lines = R.variant(path, int(size), v, fmt=fmt, debug=True)
                st = stats(lines) or {}
                if not st:
                    print(f"{name} {v}: no telemetry — is the harness patch applied "
                          "to CAF_TREE?", file=sys.stderr, flush=True)
                w.writerow([name, size, v, hr] + [st.get(k, "") for k in fields[4:]])
                fh.flush()
                print(f"{name}@{size} {v}: hr={hr} {st}", flush=True)


if __name__ == "__main__":
    main()
