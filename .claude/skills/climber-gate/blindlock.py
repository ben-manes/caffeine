#!/usr/bin/env python3
"""Measure the blind-corner lockout of the audit layer.

`densityClimb` evaluates `reading.hasBlindCorner()` before `anchor.vetoTriggered` and
`auditClock.isDue()`, so a sample the density signal calls blind never reaches the veto. The
audit is only half outranked: the branch routes `isBackingOff() ? holdOrAudit :
armStarvationProbe`, and `holdOrAudit` lets a due clock pre-empt the refractory hold. An ordered
audit is therefore refused only on the samples that arm a starvation probe instead.

Read the labels with that in mind. The harness stamps the branch "hold" or "ARM", then each
arming path overwrites the mode with its own, so a blind corner that armed an audit reads as
"AUDIT*" and not as "hold". Two columns follow from this and both changed meaning when
`holdOrAudit` shipped (2026-08-06) — a column compared against a reading older than that is
comparing two different quantities.

Columns:
  samples     density samples
  blind%      share taken by the blind-corner branch that did NOT become an audit (mode hold/ARM*)
  due%        share where the audit clock was due (stable >= auditWait)
  LOCKOUT     ARM*-with-due: a starvation probe outranking an audit the clock ordered. Hold-with-
              due can no longer occur, since `holdOrAudit` arms the audit rather than holding
  run         longest consecutive lockout run
  armed       audits actually armed / starvation probes armed
  wStarve%    share where the window earned under the starvation bar

Usage: blindlock.py <dump.traj> [...]
"""
import statistics, sys

def parse(path):
    rows = []
    for line in open(path, errors="replace"):
        if not line.startswith("climb "):
            continue
        d = {}
        for kv in line.split()[1:]:
            if "=" in kv:
                k, v = kv.split("=", 1)
                d[k] = v
        if "mode" in d and "stable" in d:
            rows.append(d)
    return rows


def analyze(path):
    rows = parse(path)
    if not rows:
        return None
    n = len(rows)
    counts = []
    for r in rows:
        hits = int(r["wh"]) + int(r["mh"])
        hr = float(r["hr"])
        if hr > 0 and hits:
            counts.append(hits / hr)
    nominal = statistics.median(counts) if counts else 0.0

    blind = due = lock = probes = audits = wstarve = 0
    run = best = 0
    for r in rows:
        mode, stable, wait = r["mode"], int(r["stable"]), int(r["auditWait"])
        hits = int(r["wh"]) + int(r["mh"])
        hr = float(r["hr"])
        req = hits / hr if hr > 0 and hits else nominal
        bar = max(4, int(req) >> 10)
        if int(r["wh"]) < bar:
            wstarve += 1
        # The blind-corner gate, minus the samples that left it as an audit (see the docstring).
        branch6 = (mode == "hold") or mode.startswith("ARM")
        isdue = stable >= wait
        blind += branch6
        due += isdue
        if mode.startswith("ARM"):
            probes += 1
        if mode.startswith("AUDIT") and mode != "AUDITCONFIRM":
            audits += 1
        if branch6 and isdue:
            lock += 1
            run += 1
            best = max(best, run)
        else:
            run = 0
    return dict(n=n, blind=100.0 * blind / n, due=100.0 * due / n, lock=lock,
                run=best, probes=probes, audits=audits, wst=100.0 * wstarve / n)


if __name__ == "__main__":
    hdr = (f"{'cell':<32}{'samples':>8}{'blind%':>8}{'due%':>7}{'LOCKOUT':>9}"
           f"{'run':>5}{'audits':>8}{'probes':>8}{'wStarve%':>9}")
    print(hdr)
    for p in sys.argv[1:]:
        a = analyze(p)
        name = p.split("/")[-1].replace(".hybrid.traj", "").replace(".traj", "")
        if a is None:
            print(f"{name:<32}  (no density samples)")
            continue
        print(f"{name:<32}{a['n']:>8}{a['blind']:>8.1f}{a['due']:>7.1f}{a['lock']:>9}"
              f"{a['run']:>5}{a['audits']:>8}{a['probes']:>8}{a['wst']:>9.1f}")
