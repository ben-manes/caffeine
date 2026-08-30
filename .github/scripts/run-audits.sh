#!/usr/bin/env bash
#
# Run the snapshot audit battery across models and bank one report per (model, skill).
#
#   .github/scripts/run-audits.sh                 # everything not already done
#   .github/scripts/run-audits.sh --dry-run       # print the plan, run nothing
#   .github/scripts/run-audits.sh --smoke         # one cheap run per lane; wiring only, no consolidation
#   .github/scripts/run-audits.sh --lanes opus-5  # one lane
#   .github/scripts/run-audits.sh --skills audit-jmm,audit-liveness
#   .github/scripts/run-audits.sh --consolidate   # only the consolidation pass
#   .github/scripts/run-audits.sh --status        # what is banked and what is outstanding
#
# Reports land in .local/audits/<model>/<skill>.md, per .claude/docs/audit-output.md. A run whose
# report file is already present is skipped, so the script is resumable: re-run it after a quota
# stall, a laptop sleep, or a Ctrl-C and it picks up the remainder.
#
# Lanes. Claude lanes run SEQUENTIALLY against each other because a Fable or Opus audit draws on
# the same quota pool as an interactive session, and a mid-run exhaustion breaks the audit rather
# than pausing it. The Codex lane draws on a separate pool, so it runs in parallel with them.
#
# Quota. Checked BEFORE each Claude run with `claude -p /usage`, which reports session and weekly
# percentages and the reset time. Above AUDIT_QUOTA_LIMIT_PCT the lane sleeps until the stated
# reset rather than starting an audit it cannot finish -- a mid-run exhaustion breaks the audit
# instead of pausing it. Exhaustion hit mid-run is caught afterwards from the JSON envelope's
# `api_error_status` / `is_error` as a backstop. A lane never skips ahead: the order is
# value-descending, so a later cheap win is worth less than the run that was interrupted.
#
# Two failure modes, needing opposite handling.
#
# Claude, background subagents: `claude -p` terminates background tasks at a wait ceiling (600s by
# default) and still exits 0 with subtype "success", so a skill that fans out -- audit-adversarial
# spawns six hostile reviewers -- loses every reviewer mid-flight and writes no report, at full
# cost (~$30 an attempt). CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0 waits indefinitely instead. Do
# not remove it. This failure is deterministic, so retrying only re-buys it: Claude lanes get one
# attempt.
#
# Codex, cyber policy: a run can end with `codex_error_info: cyber_policy` ("flagged for possible
# cybersecurity risk"). Auditing a cache for exploitable defects reads as security work, and the
# classifier trips probabilistically -- roughly one session in nine. The run is NOT retried: the
# session survives, so the useful move is to resume it and ask for the findings in a form the
# classifier accepts. The script prints the resume command on failure. If it starts tripping
# consistently rather than occasionally, the durable fix is enrolment in Trusted Access for Cyber.
#
# Streaming. Claude lanes use `--output-format stream-json --verbose`, which emits one JSON event
# per line as work happens instead of buffering everything to the end. The raw stream is what
# lands in the log, so every parser below still reads the last line as the result envelope; a
# compact human-readable view is echoed to the terminal alongside it. Set AUDIT_QUIET=1 to drop
# the terminal view and keep only the log.
#
# A long silent `claude -p` is usually throttle backoff, not a hang. Do not kill it.

set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

# --------------------------------------------------------------------------------------------
# Configuration

# Priority order, for when quota runs out mid-battery: what do you most want banked?
SKILLS_DEFAULT=(
  # Expensive; see the note above.
  audit-adversarial
  audit-regret

  # Highest surviving yield per unit cost.
  audit-jcache-conformance     # most surviving rows of any area, plus one of the three held highs
  audit-feature-interaction    # found remap:3114, the refresh family that landed five commits
  audit-liveness               # found the Pacer silent hang: default executor, nine months latent
  audit-sibling-divergence     # two highs across sweeps; the one lens still productive on adapters
  audit-subsystem-safety       # single-model coverage so far, and its weak-key M3 is still open
  audit-third-party-contracts  # caught a real config regression; only lens over examples/simulator

  # Core correctness.
  audit-exception-safety
  audit-lifecycle              # the jcache close-path trio survived adjudication
  audit-state-machine
  audit-contract-drift
  audit-memory-retention
  audit-iteration
  audit-map-contract           # its equals-vs-identity row was a real one-expression fix
  audit-adversarial-input      # int-wrap accumulation was re-raised and held

  # Formal lenses: cheap to run, several passes without a core defect.
  audit-jmm
  audit-linearizability
  audit-arithmetic
  audit-correctness-proof
  audit-serialization
  audit-reentrancy             # closed nine-for-nine declined, and the remedy is not wanted

  # Cheap, real but lower severity.
  audit-performance            # three validated wins last run
  audit-adaptivity             # climber yielded one row last sweep; last two runs found no defects
  audit-build-ci               # ties jcache on row count, but they are CI rows, not library defects
  audit-coverage-gaps
)

# lane := <report-dir>|<engine>|<model>|<effort>
LANES_DEFAULT=(
  "fable-5.1|claude|fable|max"
  "opus-5|claude|opus|max"
  "gpt-6-astra|codex||ultra"
)

# The one skill used by --smoke: cheap, narrow, and it exercises the whole path (skill resolution,
# the auditor role, the report write, the SubagentStop gate).
SMOKE_SKILL="audit-build-ci"

OUT_ROOT=".local/audits"
LOG_ROOT="$OUT_ROOT/logs"
MANIFEST="$LOG_ROOT/manifest.tsv"

# No wall-clock ceiling by default. An audit legitimately runs for hours, and a timeout can only
# destroy work that has already been paid for -- the failure mode this script exists to avoid.
# Set AUDIT_RUN_TIMEOUT to a number of seconds only if you want one.
RUN_TIMEOUT="${AUDIT_RUN_TIMEOUT:-0}"
QUOTA_SLEEP="${AUDIT_QUOTA_SLEEP:-1800}"          # 30m between quota retries
QUOTA_LIMIT_PCT="${AUDIT_QUOTA_LIMIT_PCT:-90}"    # pause a Claude lane at or above this %
QUOTA_MAX_WAIT="${AUDIT_QUOTA_MAX_WAIT:-604800}"  # give up after a week of waiting
# One attempt per run on both engines. A Claude failure is deterministic, and a codex refusal is
# better resumed by hand than re-asked blind: the session is preserved and the script prints the
# command to reopen it. Raise either only if you want blind re-asks.
QUIET="${AUDIT_QUIET:-0}"                         # 1 = log only, no live terminal view
RETRY_CLAUDE="${AUDIT_RETRY_CLAUDE:-1}"
RETRY_CODEX="${AUDIT_RETRY_CODEX:-1}"

# Never kill a background subagent for taking too long; see the header note.
export CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS="${CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS:-0}"

DRY_RUN=0; SMOKE=0; CONSOLIDATE_ONLY=0; NO_CONSOLIDATE=0; STATUS_ONLY=0
LANES=("${LANES_DEFAULT[@]}"); SKILLS=("${SKILLS_DEFAULT[@]}")

# --------------------------------------------------------------------------------------------
# Arguments

die() { printf '%s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)        DRY_RUN=1 ;;
    --smoke)          SMOKE=1 ;;
    --consolidate)    CONSOLIDATE_ONLY=1 ;;
    --status)         STATUS_ONLY=1 ;;
    --no-consolidate) NO_CONSOLIDATE=1 ;;
    --lanes)
      [ $# -ge 2 ] || die "--lanes needs a value"
      arg=$2; shift
      IFS=',' read -ra want <<< "$arg"
      sel=()
      for w in "${want[@]}"; do
        for l in "${LANES_DEFAULT[@]}"; do [ "${l%%|*}" = "$w" ] && sel+=("$l"); done
      done
      [ ${#sel[@]} -gt 0 ] || die "no lane matched: $arg (have: fable-5.1, opus-5, gpt-6-astra)"
      LANES=("${sel[@]}") ;;
    --skills)
      [ $# -ge 2 ] || die "--skills needs a value"
      arg=$2; shift
      IFS=',' read -ra SKILLS <<< "$arg" ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# A smoke run answers whether the wiring works. Consolidating behind it triages the whole
# banked tree, which is minutes of work and real money nobody asked for; run --consolidate
# separately when that is what you want.
[ "$SMOKE" = 1 ] && { SKILLS=("$SMOKE_SKILL"); NO_CONSOLIDATE=1; }

for s in "${SKILLS[@]}"; do
  [ -f ".claude/skills/$s/SKILL.md" ] || die "no such skill: .claude/skills/$s/SKILL.md"
done

command -v claude >/dev/null || die "claude CLI not on PATH"
command -v codex  >/dev/null || die "codex CLI not on PATH"
command -v jq     >/dev/null || die "jq not on PATH"
# shellcheck disable=SC2016  # the literal $skill is the codex invocation syntax
[ -L .agents/skills ] || printf 'warning: .agents/skills symlink missing; codex may not resolve $skill names\n' >&2

mkdir -p "$LOG_ROOT"
[ -f "$MANIFEST" ] || printf 'started\tfinished\tlane\tskill\tstatus\tsecs\tattempts\treport_bytes\tsession\tresume\n' > "$MANIFEST"

ts()  { date +%Y-%m-%dT%H:%M:%S; }
log() { printf '[%s] %s\n' "$(ts)" "$*"; }

# --------------------------------------------------------------------------------------------
# Running one audit

report_path() { printf '%s/%s/%s.md' "$OUT_ROOT" "$1" "$2"; }

# The CLI prefixes its JSON result with plain-text warnings on some paths, so jq over the whole
# file fails and every field silently reads empty. Take the last line that is a JSON object.
json_of() { grep -a '^{' "$1" 2>/dev/null | tail -1; }

# Ask one field of that object.
field_of() { json_of "$1" | jq -r "$2" 2>/dev/null; }

# The session id, so a run that did real work but wrote no report can be resumed by hand.
# Claude reports it in the JSON envelope; codex prints it in its startup header. A codex log also
# contains subagent thread ids, so anchor on the label and take the root session (the first).
session_of() {
  local id
  id=$(field_of "$1" '.session_id // empty')
  [ -n "$id" ] && { printf '%s' "$id"; return; }
  grep -aoE '^session id: [0-9a-f-]{36}' "$1" 2>/dev/null | head -1 | awk '{print $3}'
}

# How to reopen that session, per engine.
resume_cmd() {
  case "$1" in
    codex) printf 'codex resume %s' "$2" ;;
    *)     printf 'claude --resume %s' "$2" ;;
  esac
}

# Did codex refuse on its cybersecurity classifier? The message is printed on the failing run,
# and the session record carries `codex_error_info: cyber_policy`.
refused_cyber() {
  grep -qa 'cyber_policy\|flagged for possible cybersecurity risk' "$1" 2>/dev/null
}

# Did print mode kill background subagents at the wait ceiling? That exits 0 with subtype
# "success", so the ordinary error checks cannot see it.
killed_background() {
  local n; n=$(field_of "$1" '.subagent_stats.killed.system // 0')
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  [ "$n" -gt 0 ] && return 0
  grep -qa 'Background tasks still running after' "$1" 2>/dev/null
}

# A report that exists but is a stub is not a completed run.
report_ok() {
  local f; f=$(report_path "$1" "$2")
  [ -f "$f" ] && [ "$(wc -c < "$f" | tr -d ' ')" -ge 800 ]
}

# Progress is derived from the filesystem rather than a variable, so it stays correct across the
# background codex lane and across a re-run that resumes a partly finished battery.
TOTAL_RUNS=0
banked_count() {
  local n=0 spec lane s
  for spec in "${LANES[@]}"; do
    lane=${spec%%|*}
    for s in "${SKILLS[@]}"; do report_ok "$lane" "$s" && n=$((n + 1)); done
  done
  printf '%s' "$n"
}

outstanding_list() {
  local spec lane s
  for spec in "${LANES[@]}"; do
    lane=${spec%%|*}
    for s in "${SKILLS[@]}"; do report_ok "$lane" "$s" || printf '  %s/%s\n' "$lane" "$s"; done
  done
}

# "[banked/total]" for prefixing a progress line.
counter() { printf '[%s/%s]' "$(banked_count)" "$TOTAL_RUNS"; }

show_status() {
  local done_n; done_n=$(banked_count)
  printf 'banked %s/%s\n' "$done_n" "$TOTAL_RUNS"
  if [ "$done_n" -lt "$TOTAL_RUNS" ]; then
    printf 'outstanding:\n'; outstanding_list
  fi
  if [ -f "$MANIFEST" ]; then
    local bad; bad=$(awk -F'\t' '$5=="failed" || $5=="quota-exhausted"' "$MANIFEST" | wc -l | tr -d ' ')
    [ "$bad" -gt 0 ] && { printf 'failed:\n'
      awk -F'\t' '$5=="failed" || $5=="quota-exhausted" {
        printf "  %s/%s (%s)\n", $3, $4, $5
        if ($10 != "") printf "      resume: %s\n", $10
      }' "$MANIFEST"; }
  fi
}

# Read the live quota for ONE model. Echoes
#   "<session-pct> <week-pct> <session-reset-epoch> <week-reset-epoch>"
# with zeros if the probe fails, which makes the caller fall through and simply attempt the run.
# Both resets are reported because they differ by days: sleeping to the session reset when the
# WEEK is what is exhausted would just wake, re-read the same number, and sleep again.
#
# Weekly limits are per model and reported as "Current week (Fable): 98% used". A bucket labelled
# with a model name gates ONLY that model -- Fable being exhausted must not stall the Opus lane.
# An unlabelled "Current week:" bucket is a shared limit and gates every model.
usage_probe() {
  local for_model=${1:-}
  local raw
  raw=$(timeout 900 claude -p "/usage" --model haiku --output-format json 2>/dev/null \
          | jq -r '.result // empty' 2>/dev/null)
  [ -n "$raw" ] || { printf '0 0 0\n'; return; }
  printf '%s' "$raw" | MODEL="$for_model" python3 -c '
import os, re, sys, time, datetime
t = sys.stdin.read()
sess = max([int(m) for m in re.findall(r"Current session:\s*(\d+)%", t)] or [0])

# Only a bucket that applies to this model counts. An unlabelled bucket applies to all.
model = os.environ.get("MODEL", "").strip().lower()
week = 0
for label, pct in re.findall(r"Current week\s*(\([^)]*\))?\s*:\s*(\d+)%", t):
    name = label.strip("() ").lower()
    if not name or (model and (model in name or name in model)):
        week = max(week, int(pct))
months = {n: i for i, n in enumerate(
    ["jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"], 1)}

def epoch_of(fragment):
    m = re.search(r"resets\s+(\w+)\s+(\d+)\s+at\s+(\d+)(?::(\d+))?\s*([ap]m)", fragment, re.I)
    if not m:
        return 0
    mon, day, hr, mi, ap = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4) or 0), m.group(5).lower()
    mnum = months.get(mon[:3].lower())
    if not mnum:
        return 0
    if ap == "pm" and hr != 12: hr += 12
    if ap == "am" and hr == 12: hr = 0
    now = datetime.datetime.now()
    try:
        dt = datetime.datetime(now.year, mnum, day, hr, mi)
        if (dt - now).total_seconds() < -86400:
            dt = dt.replace(year=now.year + 1)
        return int(time.mktime(dt.timetuple()))
    except ValueError:
        return 0

def line_for(pat):
    for ln in t.split("\n"):
        if re.search(pat, ln):
            return ln
    return ""

sess_reset = epoch_of(line_for(r"Current session:"))
week_reset = 0
for ln in t.split("\n"):
    m = re.match(r"\s*Current week\s*(\([^)]*\))?\s*:\s*(\d+)%", ln)
    if not m:
        continue
    name = (m.group(1) or "").strip("() ").lower()
    if not name or (model and (model in name or name in model)):
        week_reset = max(week_reset, epoch_of(ln))

print(sess, week, sess_reset, week_reset)
' 2>/dev/null || printf '0 0 0\n'
}

# Block until this model's Claude quota is under the limit. Never called for the codex lane,
# which draws on a separate pool, and a no-op when the probe cannot read a percentage.
wait_for_quota() {
  local lane=$1 model=${2:-} sess week sess_reset week_reset reset now sleep_for waited=0
  while :; do
    read -r sess week sess_reset week_reset <<< "$(usage_probe "$model")"
    [ "${sess:-0}" -lt "$QUOTA_LIMIT_PCT" ] && [ "${week:-0}" -lt "$QUOTA_LIMIT_PCT" ] && return 0
    # Sleep to the reset of whichever limit is actually binding.
    if [ "${week:-0}" -ge "$QUOTA_LIMIT_PCT" ]; then reset=${week_reset:-0}; else reset=${sess_reset:-0}; fi

    if [ "$waited" -ge "$QUOTA_MAX_WAIT" ]; then
      log "  quota  $lane: still at session ${sess}% / week ${week}% after $((waited / 3600))h; attempting anyway"
      return 1
    fi
    now=$(date +%s)
    if [ "${reset:-0}" -gt "$now" ]; then
      sleep_for=$(( reset - now + 60 ))
      log "  quota  $lane: session ${sess}% / week ${week}%; sleeping $((sleep_for / 60))m until reset"
    else
      sleep_for=$QUOTA_SLEEP
      log "  quota  $lane: session ${sess}% / week ${week}%; no reset time, sleeping $((sleep_for / 60))m"
    fi
    sleep "$sleep_for"; waited=$((waited + sleep_for))
  done
}

# Did this attempt hit a usage limit? Checks the JSON envelope first, then the raw text, so it
# still works when the CLI fails before emitting JSON.
hit_quota() {
  local out=$1 status err text
  status=$(field_of "$out" '.api_error_status // empty')
  case "$status" in 429|529) return 0 ;; esac

  # Text matching is a fallback for failures that never produced a usable envelope. It must not
  # run on a well-formed non-error result: an audit report legitimately discusses rate limiting
  # and quotas, and mistaking that for exhaustion parks the lane for the whole QUOTA_MAX_WAIT.
  err=$(field_of "$out" 'if .is_error == true then "yes" else "no" end')
  case "$err" in
    no)  return 1 ;;                                    # valid JSON, ran cleanly: not a quota stop
    yes) text=$(field_of "$out" '(.result // "") + " " + (.subtype // "") + " " + (.terminal_reason // "")') ;;
    *)   text=$(head -c 4000 "$out") ;;                 # no parsable JSON at all
  esac

  printf '%s' "$text" | grep -qiE \
    'rate.?limit|usage limit|quota|too many requests|429|resets? (at|in)|upgrade to increase|out of (credit|usage)'
}

# One line per interesting stream event, so a long run is observable. Anything unrecognized is
# skipped rather than printed raw; the log keeps the full stream either way. A rate_limit_event
# is a periodic usage heartbeat, not a stop, so print its status and windows; hit_quota decides.
stream_view() {
  local tag=$1
  jq -r --unbuffered --arg t "$tag" \
    'def pct($w): ((.rate_limit_info.unifiedWindows[$w].utilization // 0) * 100 | round);
     if .type == "assistant" then
        ((.message.content // [])[]
         | if .type == "tool_use" then "  \($t) · \(.name)\(if .input.description then ": " + .input.description else "" end)"
           elif .type == "text" and (.text | length) > 0 then "  \($t) · \(.text | gsub("\n"; " ") | .[0:120])"
           else empty end)
      elif .type == "result" then "  \($t) · done (\(.subtype), $\(.total_cost_usd // 0 | .*100 | round / 100))"
      elif .type == "rate_limit_event" then
        "  \($t) · quota \(.rate_limit_info.status // "?") (\(pct("five_hour"))% 5h,"
        + " \(pct("seven_day_overage_included"))% 7d)"
      else empty end' 2>/dev/null || true
}

run_claude() {
  local lane=$1 skill=$2 model=$3 effort=$4 out=$5
  local -a cmd=(claude -p "/$skill"
                 --model "$model" --effort "$effort"
                 --permission-mode bypassPermissions
                 --output-format stream-json --verbose)
  [ "$RUN_TIMEOUT" -gt 0 ] && cmd=(timeout "$RUN_TIMEOUT" "${cmd[@]}")
  if [ "$QUIET" = 1 ]; then
    "${cmd[@]}" < /dev/null > "$out" 2>&1
  else
    # tee keeps the raw stream in the log; the view is cosmetic and must never decide the run.
    "${cmd[@]}" < /dev/null 2>&1 | tee "$out" | stream_view "$lane/$skill"
    return "${PIPESTATUS[0]}"
  fi
}

run_codex() {
  local lane=$1 skill=$2 model=$3 effort=$4 out=$5
  # Model and reasoning effort come from ~/.codex/config.toml (gpt-6-astra / ultra); passed
  # explicitly so the run does not silently change if that config is edited.
  local -a cmd=(codex exec "\$$skill"
                 --dangerously-bypass-approvals-and-sandbox
                 -c model_reasoning_effort="$effort")
  [ "$RUN_TIMEOUT" -gt 0 ] && cmd=(timeout "$RUN_TIMEOUT" "${cmd[@]}")
  "${cmd[@]}" > "$out" 2>&1
}

# One (lane, skill), with quota waiting and bounded retries. Returns 0 on a banked report.
run_one() {
  local lane=$1 engine=$2 model=$3 effort=$4 skill=$5
  local out="$LOG_ROOT/$lane.$skill.log"
  local retry_max; [ "$engine" = codex ] && retry_max=$RETRY_CODEX || retry_max=$RETRY_CLAUDE
  local started attempts=0 waited=0 rc t0 t1

  if report_ok "$lane" "$skill"; then
    log "$(counter) skip  $lane/$skill"
    return 0
  fi

  # The lane directory is this script's name for the run, not the agent's name for itself. An
  # auditor told only to use "your own short model id" writes to that id, so a lane label the
  # model does not share (gpt-6-astra against a model that calls itself gpt-6) banks the report
  # where nothing looks for it, and a finished audit reads as a failure. Both engines take the
  # destination from the environment; see .claude/docs/audit-output.md.
  export AUDIT_REPORT_PATH
  AUDIT_REPORT_PATH=$(report_path "$lane" "$skill")

  started=$(ts)
  while :; do
    attempts=$((attempts + 1))
    [ "$engine" = claude ] && wait_for_quota "$lane" "$model"
    t0=$(date +%s)
    log "$(counter) start $lane/$skill${attempts:+ (attempt $attempts)}"

    if [ "$engine" = claude ]; then
      run_claude "$lane" "$skill" "$model" "$effort" "$out"; rc=$?
    else
      run_codex  "$lane" "$skill" "$model" "$effort" "$out"; rc=$?
    fi
    t1=$(date +%s)
    # Keep this attempt's output; a retry would otherwise overwrite the only evidence of why
    # the previous one failed.
    [ -f "$out" ] && cp "$out" "$out.attempt$attempts"

    if report_ok "$lane" "$skill"; then
      local bytes; bytes=$(wc -c < "$(report_path "$lane" "$skill")" | tr -d ' ')
      log "$(counter) done  $lane/$skill in $(( (t1 - t0) / 60 ))m (${bytes}B)"
      printf '%s\t%s\t%s\t%s\tok\t%s\t%s\t%s\t%s\n' \
        "$started" "$(ts)" "$lane" "$skill" "$((t1 - t0))" "$attempts" "$bytes" \
        "$(session_of "$out")" >> "$MANIFEST"
      return 0
    fi

    # No report. Quota, or a real failure?
    if hit_quota "$out"; then
      if [ "$waited" -ge "$QUOTA_MAX_WAIT" ]; then
        log "$(counter) ABORT $lane/$skill: still quota-limited after $((waited / 3600))h"
        printf '%s\t%s\t%s\t%s\tquota-exhausted\t%s\t%s\t0\t%s\n' \
          "$started" "$(ts)" "$lane" "$skill" "$((t1 - t0))" "$attempts" "$(session_of "$out")" >> "$MANIFEST"
        return 2
      fi
      log "  quota  $lane/$skill: limit hit, sleeping ${QUOTA_SLEEP}s (waited $((waited / 60))m)"
      sleep "$QUOTA_SLEEP"; waited=$((waited + QUOTA_SLEEP))
      attempts=$((attempts - 1))   # a quota wait is not a failed attempt
      continue
    fi

    if [ "$attempts" -ge "$retry_max" ]; then
      local sid; sid=$(session_of "$out")
      log "$(counter) FAIL  $lane/$skill (rc=$rc, no report); log: $out"
      if killed_background "$out"; then
        log "        cause: background subagents killed at the print wait ceiling"
        log "        this run did real work before it was cut off -- resume and ask for the report"
      elif refused_cyber "$out"; then
        log "        cause: codex cyber-policy refusal; usually clears on a re-ask"
      fi
      # A report under some other model directory means the run finished and misfiled it, which
      # is a different problem from a run that produced nothing.
      local stray
      stray=$(find "$OUT_ROOT" -mindepth 2 -maxdepth 2 -name "$skill.md" -newermt "$started" \
                ! -path "$(report_path "$lane" "$skill")" 2>/dev/null | head -1)
      if [ -n "$stray" ]; then
        log "        cause: report written to $stray, not $(report_path "$lane" "$skill")"
        log "        the audit finished; move it into place rather than re-running"
      fi
      [ -n "$sid" ] && log "        resume: $(resume_cmd "$engine" "$sid")"
      printf '%s\t%s\t%s\t%s\tfailed\t%s\t%s\t0\t%s\t%s\n' \
        "$started" "$(ts)" "$lane" "$skill" "$((t1 - t0))" "$attempts" "${sid:--}" \
        "$([ -n "$sid" ] && resume_cmd "$engine" "$sid")" >> "$MANIFEST"
      return 1
    fi
    log "  retry  $lane/$skill (rc=$rc, no report written)"
    sleep 60
  done
}

run_lane() {
  local spec=$1 lane engine model effort
  IFS='|' read -r lane engine model effort <<< "$spec"
  mkdir -p "$OUT_ROOT/$lane"
  log "$(counter) lane $lane starting ($engine${model:+ $model} @ $effort)"
  local s
  for s in "${SKILLS[@]}"; do
    run_one "$lane" "$engine" "$model" "$effort" "$s"
  done
  log "$(counter) lane $lane complete"
}

# --------------------------------------------------------------------------------------------
# Consolidation

consolidate() {
  local target="$OUT_ROOT/shared/audit-consolidated.md"
  mkdir -p "$OUT_ROOT/shared"
  log "consolidating into $target"

  local n; n=$(find "$OUT_ROOT" -mindepth 2 -maxdepth 2 -name '*.md' ! -path "$OUT_ROOT/shared/*" \
                 ! -path "$LOG_ROOT/*" | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] || { log "  no reports to consolidate"; return 1; }
  log "  $n reports"

  # general-purpose, NOT the auditor: the auditor carries a mandatory-report-write gate that will
  # pick a canonical filename and overwrite a source report it was asked to read.
  local prompt
  prompt=$(cat <<'PROMPT'
Consolidate every audit report under .local/audits/<model>/*.md into a single triaged work queue
at .local/audits/shared/audit-consolidated.md. Read every report; do not sample.

You are triaging CLAIMS, not findings. The last full sweep put 186 rows through verification and
7 survived. Expect a similar ratio and prune hard.

For every claim, in this order:
1. Deduplicate across models. The same defect is often reported by several skills under different
   ids. Merge them into one row and list every reporting (model, skill, id) as its reference.
2. Apply the standing rulings: .claude/docs/ruled-out.md (its Standing principles plus the row's
   module section), .claude/docs/design-decisions.md, .claude/rules/design-decisions.md, and
   .claude/docs/jsr107-conformance.md for jcache rows. Most rows die here. A ruling disposes of a
   row only when the MECHANISM and the CONSEQUENCE both match; if the row has a reachable trigger
   the ruling does not name, it survives, and you must say which part differs.
3. Verify what survives against the source. Read the code. `git log -L <start>,<end>:<file>` before
   calling any line an oversight.
4. Reject anything whose trigger the report constructed (a Weigher that mutates the cache, a
   throwing Ticker, a hostile CompletableFuture, a broken executor), anything reachable only via
   Cache.unwrap, anything that is stats-only, and anything whose only impact needs a FakeTicker or
   executor(Runnable::run).
5. Price severity on a production configuration: system ticker, common pool. Say so when a
   report's magnitude does not reproduce, and give the number you measured.

PRUNE every rejected row from the report. Do not carry them as a rejected section; a one-line
note in a "Refuted, do not re-raise" appendix is enough, and only for rows a future audit is
likely to re-report.

Structure the output as:
- A header: date, commit (`git rev-parse HEAD`), how many reports were read, how many raw claims
  came in, how many survived, and the survival ratio.
- Then one section per AREA, in this order: core, async, jcache, guava, simulator, examples,
  build/CI, docs. Within a section, order rows by severity then by area.
- Each row is a table entry with these columns, and every row gets a stable id `<area>.<n>`:
  | # | Status | Severity | Claim | Reference | Verification |
  where Status is `open` (verified, needs work) or `resolved` (already fixed in tree, with the
  commit subject), Reference lists every (model, skill, finding-id) that reported it, and
  Verification is the repro or A/B you ran and what it showed.
- A final "Refuted, do not re-raise" appendix: one line each, with the reason.

Rules: never overwrite a source report. Cite file:line. State plainly when you could not verify a
row rather than guessing, and mark it `unverified` in Status.
PROMPT
)

  local out="$LOG_ROOT/consolidate.log"
  local -a cmd=(claude -p "$prompt" --model opus --effort max
                 --permission-mode bypassPermissions --output-format json)
  [ "$RUN_TIMEOUT" -gt 0 ] && cmd=(timeout "$RUN_TIMEOUT" "${cmd[@]}")

  if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] would consolidate $n reports"; return 0; fi

  "${cmd[@]}" > "$out" 2>&1
  if [ -f "$target" ]; then
    log "  consolidated -> $target ($(wc -l < "$target" | tr -d ' ') lines)"
  else
    log "  FAILED: no $target written; see $out"
    return 1
  fi
}

# --------------------------------------------------------------------------------------------
# Main

TOTAL_RUNS=$(( ${#LANES[@]} * ${#SKILLS[@]} ))

if [ "$STATUS_ONLY" = 1 ]; then show_status; exit 0; fi
if [ "$CONSOLIDATE_ONLY" = 1 ]; then consolidate; exit $?; fi

banked=$(banked_count)
log "plan: ${#LANES[@]} lanes x ${#SKILLS[@]} skills = $TOTAL_RUNS runs; $banked banked, $((TOTAL_RUNS - banked)) outstanding"
for spec in "${LANES[@]}"; do
  IFS='|' read -r lane engine model effort <<< "$spec"
  printf '  %-14s %-7s %-7s %s\n' "$lane" "$engine" "${model:-—}" "$effort"
done

if [ "$DRY_RUN" = 1 ]; then
  log "dry run: nothing executed"
  for spec in "${LANES[@]}"; do
    lane=${spec%%|*}
    for s in "${SKILLS[@]}"; do
      report_ok "$lane" "$s" && printf '  have %s/%s\n' "$lane" "$s" || printf '  RUN  %s/%s\n' "$lane" "$s"
    done
  done
  [ "$NO_CONSOLIDATE" = 1 ] || consolidate
  exit 0
fi

start_all=$(date +%s)

# Codex draws on a separate quota pool, so it runs alongside the Claude lanes. The Claude lanes
# run one after another, because a mid-run exhaustion breaks an audit rather than pausing it.
codex_pid=""
for spec in "${LANES[@]}"; do
  if [ "$(printf '%s' "$spec" | cut -d'|' -f2)" = codex ]; then
    log "starting codex lane in background"
    run_lane "$spec" & codex_pid=$!
  fi
done

for spec in "${LANES[@]}"; do
  [ "$(printf '%s' "$spec" | cut -d'|' -f2)" = codex ] && continue
  run_lane "$spec"
done

if [ -n "$codex_pid" ]; then
  log "waiting for the codex lane"
  wait "$codex_pid"
fi

log "all lanes finished in $(( ($(date +%s) - start_all) / 60 ))m"

show_status

[ "$NO_CONSOLIDATE" = 1 ] || consolidate
log "done"
