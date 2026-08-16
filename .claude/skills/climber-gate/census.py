#!/usr/bin/env python3
"""Where do audits confirm and park, and what does the parked window earn afterwards?

Two steps over the harness's per-sample trajectory line (`harness.py apply`, then
`-Dcaffeine.climber.debug=true`, which `run.py` dumps): `run` writes one trajectory per
(cell, seed) for gate.py's cells plus real.py's, and `parse` emits one CSV row per AUDITCONFIRM.

  CAF_TREE=<wired worktree> census.py run   <traces-dir> <dumpdir> <seeds,comma> [variant]
                            census.py parse <dumpdir> [out.csv]

Per confirm: the walk's direction and base, the confirmed window as a share of the maximum,
`blind` (`hasBlindCorner()` on the confirm sample), `gstarved` (the region the walk grew is
starved on that sample: the window for an up-walk, main for a down-walk), the park's length, how
many park samples starve the grown region and the window, how the park ended (the mode of the
first non-park sample), and the mean hit rate over the park against the 32 samples before the
arm. Everything derives from `win`, `wh`, `mh`, `mode`, `hold`; the starvation bar is
max(4, (4 * max) >> 10), the sample period's.

What it measured on 2026-08-15 (battery seeds 1-8 plus the real corpus, ~490 confirms): 2 blind
at the confirm and both parked for 0 samples (the blind branch outranks the park); 0 with the
grown region starved at the confirm; 3 parks whose window starved afterwards. Read those before
proposing a rule about parks on starved positions (`hill-climber.md` section 7, 2026-08-15).
"""
import csv, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402
import gate as G  # noqa: E402
import real as REAL  # noqa: E402


def cells(traces):
    out = [(label, os.path.join(traces, name), "lirs", size) for label, name, size, _ in G.CELLS]
    out += [(label, path, fmt, size) for label, path, fmt, size in REAL.CELLS]
    return out


def do_run(traces, dumpdir, seeds, variant):
    os.makedirs(dumpdir, exist_ok=True)
    for label, path, fmt, size in cells(traces):
        if not os.path.exists(path):
            print(f"SKIP {label}: missing {path}", flush=True)
            continue
        for seed in seeds:
            dump = f"{dumpdir}/{label}_{variant}_s{seed}.traj"
            if os.path.exists(dump):
                continue
            hr, lines = R.variant(path, size, variant, fmt, debug=True, dump=dump, seed=seed)
            print(f"{label} s{seed} {variant} hr={hr}", flush=True)
            if not lines:
                print(f"  NO TRAJECTORY for {label} (stock build or debug off)", flush=True)


def parse_line(ln):
    return dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)


def do_parse(dumpdir, out):
    rows = []
    for fn in sorted(os.listdir(dumpdir)):
        if not fn.endswith(".traj"):
            continue
        cell, variant, seed = fn[:-5].rsplit("_", 2)
        lines = [parse_line(l) for l in open(os.path.join(dumpdir, fn)) if l.startswith("climb ")]
        if not lines:
            continue
        mx = int(lines[0]["max"])
        bar = max(4, (4 * mx) >> 10)
        n = len(lines)
        for i, d in enumerate(lines):
            if d["mode"] != "AUDITCONFIRM":
                continue
            j = i - 1
            while j >= 0 and not lines[j]["mode"].startswith("AUDIT"):
                j -= 1
            arm = lines[j] if j >= 0 else None
            direction = ("up" if arm and arm["mode"] == "AUDITup"
                         else "dn" if arm and arm["mode"] == "AUDITdn" else "?")
            base = int(arm["win"]) if arm else -1
            win, wh, mh = int(d["win"]), int(d["wh"]), int(d["mh"])
            wst, mst = wh < bar, mh < bar
            blind = (wst and mst) or (wst and win <= (mx >> 2)) or (mst and win >= mx - (mx >> 2))
            grown = (lambda w, m: w) if direction == "up" else (lambda w, m: m)
            k = i + 1
            park = park_gst = park_wst = 0
            park_hr = []
            while k < n and lines[k]["mode"] == "park":
                pk = lines[k]
                pwh, pmh = int(pk["wh"]) < bar, int(pk["mh"]) < bar
                park += 1
                park_wst += pwh
                park_gst += grown(pwh, pmh)
                park_hr.append(float(pk["hr"]))
                k += 1
            ended = lines[k]["mode"] if k < n else "END"
            pre = [float(x["hr"]) for x in lines[max(0, j - 32):j]] if j >= 0 else []
            rows.append(dict(
                cell=cell, variant=variant, seed=seed, sample=i, n=n, dir=direction,
                base_pct=round(100.0 * base / mx, 1), win_pct=round(100.0 * win / mx, 1),
                wh=wh, mh=mh, bar=bar, blind=int(blind), wstarved=int(wst), mstarved=int(mst),
                gstarved=int(grown(wst, mst)), park=park, park_gstarved=park_gst,
                park_wstarved=park_wst, ended=ended,
                hr_pre=round(statistics.mean(pre), 4) if pre else "",
                hr_confirm=float(d["hr"]),
                hr_park=round(statistics.mean(park_hr), 4) if park_hr else ""))
    if rows:
        with open(out, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
    print(f"{len(rows)} confirms -> {out}")


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("run", "parse"):
        sys.exit(__doc__)
    if sys.argv[1] == "run":
        traces, dumpdir, seeds = sys.argv[2], sys.argv[3], [int(s) for s in sys.argv[4].split(",")]
        do_run(traces, dumpdir, seeds, sys.argv[5] if len(sys.argv) > 5 else "hybrid")
    else:
        do_parse(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "census.csv")


if __name__ == "__main__":
    main()
