#!/usr/bin/env bash
#
# Cases for run-audits.sh's quota detector. It runs unattended overnight, so both directions
# matter: a missed quota stop wastes the night, and a false positive parks a lane for
# QUOTA_MAX_WAIT. The nastiest false positive is a report that legitimately discusses rate
# limiting -- see the last two cases.
#
#   bash .github/scripts/run-audits.test.sh
set -u
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
# hit_quota depends on json_of/field_of, so extract all three (brace-aware: the one-liners end
# on their own line, so a /^}/ range would swallow whatever follows).
python3 - "$(dirname "${BASH_SOURCE[0]}")/run-audits.sh" > "$D/hq.sh" <<'EXTRACT'
import re, sys
# Brace counting is unusable here (json_of matches a literal '{' inside a quoted pattern), so:
# a one-liner is a definition whose own line already closes it; otherwise read to a bare '}'.
src = open(sys.argv[1]).read().split('\n')
want = {'json_of', 'field_of', 'hit_quota', 'refused_cyber', 'killed_background',
        'session_of', 'resume_cmd'}
out, i = [], 0
while i < len(src):
    m = re.match(r'^(\w+)\(\)\s*\{', src[i])
    if m and m.group(1) in want:
        out.append(src[i])
        if not src[i].rstrip().endswith('}'):
            i += 1
            while i < len(src):
                out.append(src[i])
                if src[i].rstrip() == '}':
                    break
                i += 1
    i += 1
print('\n'.join(out))
EXTRACT
# shellcheck source=/dev/null  # generated at runtime from run-audits.sh
source "$D/hq.sh"
fails=0
t() { printf '%s' "$1" > "$D/c.json"; if hit_quota "$D/c.json"; then r=QUOTA; else r=no; fi
      if [ "$r" = "$2" ]; then printf 'PASS  %s\n' "$3"
      else printf 'FAIL  %s (got %s want %s)\n' "$3" "$r" "$2"; fails=$((fails+1)); fi; }
printf "%s\n" "-- must detect quota --"
t '{"api_error_status":429,"is_error":true,"result":""}' QUOTA "envelope 429"
t '{"api_error_status":529,"is_error":true,"result":""}' QUOTA "envelope 529 (overloaded)"
t '{"is_error":true,"subtype":"error_during_execution","result":"Claude usage limit reached. Your limit resets at 3pm."}' QUOTA "usage limit reached"
t '{"is_error":true,"subtype":"error","result":"5-hour limit reached; resets in 2h"}' QUOTA "resets-in, errored"
t 'Error: 429 Too Many Requests' QUOTA "raw text, no JSON"
t 'error: rate limit exceeded, retry later' QUOTA "raw text rate limit"
printf "%s\n" "-- must NOT detect quota --"
t '{"type":"result","subtype":"success","is_error":false,"result":"Audit complete."}' no "clean success"
t '{"api_error_status":500,"is_error":true,"result":"internal server error"}' no "500 server error"
t '{"is_error":true,"subtype":"error","result":"tool execution failed: file not found"}' no "genuine non-quota failure"
t 'zsh: command not found: claude' no "raw text, unrelated"
t '' no "empty output"
printf "%s\n" "-- the false positive that would park a lane for a week --"
t '{"type":"result","subtype":"success","is_error":false,"result":"Finding: the rate limiter quota resets at the wrong time"}' no "success whose REPORT discusses rate limits"
t '{"is_error":false,"subtype":"success","result":"429 appears in the HTTP example above"}' no "success mentioning 429"

# --- the two failure modes seen in the field ------------------------------------------------
c() { printf '%s' "$2" > "$D/c.json"
      if $1 "$D/c.json"; then r=YES; else r=no; fi
      if [ "$r" = "$3" ]; then printf 'PASS  %s\n' "$4"
      else printf 'FAIL  %s (got %s want %s)\n' "$4" "$r" "$3"; fails=$((fails+1)); fi; }

printf '%s\n' "-- claude: background subagents killed at the print wait ceiling --"
c killed_background 'Background tasks still running after 600s; terminating.
{"is_error":false,"subtype":"success","subagent_stats":{"spawned":6,"completed":0,"killed":{"parent":0,"user":0,"system":6}}}' YES "warning line + killed.system=6"
c killed_background '{"is_error":false,"subtype":"success","subagent_stats":{"spawned":1,"completed":1,"killed":{"parent":0,"user":0,"system":0}}}' no "healthy run, nothing killed"

printf '%s\n' "-- codex: cybersecurity-policy refusal --"
c refused_cyber '{"error":{"message":"This content was flagged for possible cybersecurity risk.","codex_error_info":"cyber_policy"}}' YES "cyber_policy record"
c refused_cyber '{"subtype":"success","result":"Audit complete; no findings."}' no "healthy codex run"

# --- session capture: claude reports it as JSON, codex prints it in a header ------------------
sid() { printf '%s' "$2" > "$D/c.json"; got=$(session_of "$D/c.json")
        if [ "$got" = "$3" ]; then printf 'PASS  %s\n' "$1"
        else printf 'FAIL  %s (got "%s" want "%s")\n' "$1" "$got" "$3"; fails=$((fails+1)); fi; }

printf '%s\n' "-- session id extraction --"
sid "claude, from JSON" \
    '{"session_id":"f01103eb-2bcd-4e4f-a1d9-71cf1377122c","subtype":"success"}' \
    "f01103eb-2bcd-4e4f-a1d9-71cf1377122c"
sid "claude, JSON behind a warning line" \
    'Background tasks still running after 600s; terminating.
{"session_id":"f01103eb-2bcd-4e4f-a1d9-71cf1377122c"}' \
    "f01103eb-2bcd-4e4f-a1d9-71cf1377122c"
sid "codex, from the header" \
    'OpenAI Codex v0.151.0
session id: 01a05389-0824-7803-b1d7-702d67700f6f
--------' \
    "01a05389-0824-7803-b1d7-702d67700f6f"
sid "codex, root session not a subagent thread_id" \
    'session id: 01a05389-0824-7803-b1d7-702d67700f6f
thread_id=019feecb-9850-7a43-9440-a4ca7a92b08d
session id: 019ff7a3-f077-7841-b1bc-751de4600b6a' \
    "01a05389-0824-7803-b1d7-702d67700f6f"
sid "no session id anywhere" 'some unrelated output' ""

if [ "$(resume_cmd codex ABC)" = "codex resume ABC" ]; then printf 'PASS  codex resume command\n'
else printf 'FAIL  codex resume command\n'; fails=$((fails+1)); fi
if [ "$(resume_cmd claude ABC)" = "claude --resume ABC" ]; then printf 'PASS  claude resume command\n'
else printf 'FAIL  claude resume command\n'; fails=$((fails+1)); fi

# --- the /usage parser -------------------------------------------------------------------
sed -n "/python3 -c '/,/^' 2>\/dev\/null/p" "$(dirname "${BASH_SOURCE[0]}")/run-audits.sh" \
  | sed "1s/.*python3 -c '//; \$d" > "$D/parse.py"
u() { got=$(printf '%s' "$2" | MODEL="${MODEL:-}" python3 "$D/parse.py" 2>/dev/null)
      if [ "$got" = "$3" ]; then printf 'PASS  %s\n' "$1"
      else printf 'FAIL  %s (got "%s" want "%s")\n' "$1" "$got" "$3"; fails=$((fails+1)); fi; }

printf '%s\n' "-- /usage parsing --"
# A weekly bucket is per model: Fable at 98% must not gate the Opus lane.
MODEL=fable u "fable sees its own weekly"  "Current session: 4% used
Current week (Fable): 98% used"                                       "4 98 0 0"
MODEL=opus  u "opus ignores fable weekly"  "Current session: 4% used
Current week (Fable): 98% used"                                       "4 0 0 0"
MODEL=opus  u "opus sees its own weekly"   "Current session: 4% used
Current week (Fable): 98% used
Current week (Opus): 87% used"                                        "4 87 0 0"
MODEL=opus  u "unlabelled weekly gates all" "Current session: 4% used
Current week: 91% used"                                               "4 91 0 0"
u "unparseable input"      "some unrelated output"                    "0 0 0 0"

printf '%s\n' "-- reset-time decoding (epoch varies, so check it is non-zero and ordered) --"
a=$(printf 'Current session: 5%% used resets Aug 31 at 12:00am (X)' | python3 "$D/parse.py" | cut -d' ' -f3)
b=$(printf 'Current session: 5%% used resets Aug 31 at 12:00pm (X)' | python3 "$D/parse.py" | cut -d' ' -f3)
if [ "$a" -gt 0 ] && [ "$b" -gt "$a" ]; then printf 'PASS  12am decodes before 12pm\n'
else printf 'FAIL  12am/12pm boundary (12am=%s 12pm=%s)\n' "$a" "$b"; fails=$((fails+1)); fi

exit $(( fails > 0 ))
