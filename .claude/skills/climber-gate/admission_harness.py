#!/usr/bin/env python3
"""Wire admission precision/yield instrumentation into a worktree, by anchored snippet.

Merlin's Figure 20 prices its admission decisions directly — the fraction of promoted objects
later accessed again, and how many hits those objects earn — instead of inferring decision
quality from the end hit rate. Caffeine has no equivalent. This adds one for the static
W-TinyLFU anchor, so the measure can be read across a window sweep.

Per region it reports, over entries that were evicted (plus residents flushed at finish):

  precision = share of entries admitted to the region that were hit at least once while there
  yield     = mean hits earned by those productive entries
  density   = hits per unit of region capacity, which is what the shipped climber steers on

The point of the decomposition is that density = precision x yield x (entries per capacity),
so a region can be dense because it is often right or because it is occasionally very right,
and the steering signal cannot tell those apart.

A candidate demoted out of the window and evicted inside the same request never lived in main;
it is counted as `rejected`, not as a failed admission. That is what `mainSince` versus the
request counter distinguishes.

Every anchor is code, never a comment, so doc edits cannot move it; a missing anchor names
itself and stops. Applying is idempotent.

    admission_harness.py apply  <worktree>
    admission_harness.py verify <worktree>
    admission_harness.py strip  <worktree>

NEVER commit the result: it prints to stderr and ErrorProne flags the `SystemOut` by design.
"""
import sys, os

P = ("simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator"
     "/policy/sketch/WindowTinyLfuPolicy.java")

FIELDS = '''  // MEASUREMENT HARNESS (worktree-only): admission precision and yield per region.
  static final boolean ADMIT_MEASURE = Boolean.getBoolean("caffeine.admission.measure");
  private long opCount;
  private long winEntered, winProductive, winHits;
  private long mainAdmitted, mainProductive, mainHits, mainRejected;

'''

FIELDS_ANCHOR = "  private @Nullable Admitter admitter;\n"

OP_ANCHOR = """    policyStats.recordOperation();
"""
OP_SNIPPET = """    opCount++;
"""

HIT_ANCHORS = {
    "window": ("  private void onWindowHit(Node node, int weight) {\n"
               "    recordAccess(node.key);\n", "    node.hitsWindow++;\n"),
    "probation": ("  private void onProbationHit(Node node, int weight) {\n"
                  "    recordAccess(node.key);\n", "    node.hitsMain++;\n"),
    "protected": ("  private void onProtectedHit(Node node, int weight) {\n"
                  "    recordAccess(node.key);\n", "    node.hitsMain++;\n"),
}

DEMOTE_ANCHOR = "      candidate.appendToTail(headProbation);\n"
DEMOTE_SNIPPET = """      if (candidate.mainSince < 0) {
        candidate.mainSince = opCount;
      }
"""

EVICT_ANCHOR = "    policyStats.recordEviction();\n"
EVICT_SNIPPET = """    tally(node);
"""

HELPERS = '''
  /** Attributes one departing entry to the per-region precision and yield counters. */
  private void tally(Node node) {
    if (!ADMIT_MEASURE) {
      return;
    }
    winEntered++;
    if (node.hitsWindow > 0) {
      winProductive++;
      winHits += node.hitsWindow;
    }
    if (node.mainSince >= 0) {
      if (opCount > node.mainSince) {
        mainAdmitted++;
        if (node.hitsMain > 0) {
          mainProductive++;
          mainHits += node.hitsMain;
        }
      } else {
        // demoted and evicted inside one request: it never lived in main
        mainRejected++;
      }
    }
  }

  /** Emits the measure once the trace is exhausted, residents included. */
  private void reportAdmission() {
    for (Node node : data.values()) {
      tally(node);
    }
    long mainMax = maximumSize - maxWindow;
    double winPrec = (winEntered == 0) ? 0 : (double) winProductive / winEntered;
    double winYield = (winProductive == 0) ? 0 : (double) winHits / winProductive;
    double mainPrec = (mainAdmitted == 0) ? 0 : (double) mainProductive / mainAdmitted;
    double mainYield = (mainProductive == 0) ? 0 : (double) mainHits / mainProductive;
    System.err.printf("ADMISSION winpct=%.4f winEntered=%d winProd=%d winHits=%d "
        + "winPrec=%.5f winYield=%.4f winDensity=%.6f mainAdmitted=%d mainProd=%d mainHits=%d "
        + "mainRejected=%d mainPrec=%.5f mainYield=%.4f mainDensity=%.6f%n",
        1.0 - ((double) (maximumSize - maxWindow) / maximumSize),
        winEntered, winProductive, winHits, winPrec, winYield,
        (maxWindow == 0) ? 0 : (double) winHits / maxWindow,
        mainAdmitted, mainProductive, mainHits, mainRejected, mainPrec, mainYield,
        (mainMax == 0) ? 0 : (double) mainHits / mainMax);
  }
'''

HELPERS_ANCHOR = "  enum Status {\n"

FINISH_ANCHOR = "    checkState(sizeData <= maximumSize);\n"
FINISH_SNIPPET = """    if (ADMIT_MEASURE) {
      reportAdmission();
    }
"""

NODE_ANCHOR = "    @Nullable Status status;\n"
NODE_SNIPPET = """    int hitsWindow;
    int hitsMain;
    long mainSince = -1;
"""

EDITS = [
    (FIELDS_ANCHOR, FIELDS, "before"),
    (OP_ANCHOR, OP_SNIPPET, "after"),
    (DEMOTE_ANCHOR, DEMOTE_SNIPPET, "after"),
    (EVICT_ANCHOR, EVICT_SNIPPET, "after"),
    (HELPERS_ANCHOR, HELPERS, "before"),
    (FINISH_ANCHOR, FINISH_SNIPPET, "after"),
    (NODE_ANCHOR, NODE_SNIPPET, "after"),
] + [(a, s, "after") for a, s in HIT_ANCHORS.values()]


def check_edits():
    """Applied forms must be distinct, or the second of a pair is invisible to every operation."""
    forms = [placed(a, s, w) for a, s, w in EDITS]
    if len(set(forms)) != len(forms):
        sys.exit("admission: two edits share an applied form — see placed()")


def load(wt):
    path = os.path.join(wt, P)
    if not os.path.exists(path):
        sys.exit(f"missing {path}")
    return path, open(path).read()


def placed(anchor, snippet, where):
    """The edit as it looks once applied.

    Presence must be tested against this, never against the snippet alone: two edits here insert
    the identical line (`node.hitsMain++;`) at different anchors, and a snippet-only check treats
    the second as already applied. That silently dropped every protected-region hit on the first
    run — the measure still printed, and still looked plausible.
    """
    return (snippet + anchor) if where == "before" else (anchor + snippet)


def apply(wt):
    path, s = load(wt)
    done = 0
    for anchor, snippet, where in EDITS:
        if placed(anchor, snippet, where) in s:
            done += 1
            continue
        if s.count(anchor) != 1:
            sys.exit(f"anchor not unique ({s.count(anchor)}x): {anchor.strip()[:70]}")
        s = s.replace(anchor, placed(anchor, snippet, where))
        done += 1
    open(path, "w").write(s)
    print(f"admission apply: ok — {done}/{len(EDITS)} wired")


def verify(wt):
    _, s = load(wt)
    missing = [sn.strip().splitlines()[0][:60]
               for a, sn, w in EDITS if placed(a, sn, w) not in s]
    if missing:
        sys.exit("admission verify: MISSING\n  " + "\n  ".join(missing))
    print(f"admission verify: ok — {len(EDITS)}/{len(EDITS)} present")


def strip(wt):
    path, s = load(wt)
    n = 0
    for anchor, snippet, where in EDITS:
        p = placed(anchor, snippet, where)
        if p in s:
            s = s.replace(p, anchor)
            n += 1
    open(path, "w").write(s)
    print(f"admission strip: ok — {n}/{len(EDITS)} removed")


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("apply", "verify", "strip"):
        sys.exit(__doc__)
    check_edits()
    {"apply": apply, "verify": verify, "strip": strip}[sys.argv[1]](sys.argv[2])
