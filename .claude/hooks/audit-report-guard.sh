#!/usr/bin/env bash
#
# SubagentStop guard for the `auditor` agent.
#
# Enforces auditor.md Phase 4: the auditor MUST write its full report to
# .claude/reports/<skill-name>.md before stopping. A recurring failure mode is
# the auditor finishing with its findings inlined in the returned message and no
# report file written, sometimes "justified" by a confabulated memory/user
# instruction to skip the file (see memory: feedback_audit_report_confabulation).
# The auditor cannot read memory or .claude/reports/, so any such instruction is
# invented. This hook turns the prompt-level mandate into an actual gate.
#
# Mechanism: when an `auditor` subagent stops, inspect its own transcript for a
# Write/Edit to any .claude/reports/*.md. If none, block the stop once with a
# corrective message; the auditor then writes the file and the next stop passes.
# Fails OPEN on any tooling/parse problem so it can never wedge a stop.
#
# stdin: SubagentStop hook JSON (agent_type, transcript_path, ...).
set -u

input=$(cat)

# Fail open if jq is unavailable.
command -v jq >/dev/null 2>&1 || exit 0

agent_type=$(printf '%s' "$input" | jq -r '.agent_type // ""' 2>/dev/null)
# Only the auditor is held to the report contract. The SubagentStop matcher
# should already scope this, but re-check so a broader match never blocks
# other agents (Explore, Plan, workflow subagents, ...).
[ "$agent_type" = "auditor" ] || exit 0

transcript=$(printf '%s' "$input" | jq -r '.transcript_path // ""' 2>/dev/null)
{ [ -n "$transcript" ] && [ -f "$transcript" ]; } || exit 0

guard="${TMPDIR:-/tmp}/claude-audit-report-guard.$(basename "$transcript" .jsonl)"

# Did the auditor Write/Edit any report file under .claude/reports/ this run?
if jq -rc 'select(.type=="assistant")
             | (.message.content // empty)
             | (if type=="array" then .[] else empty end)
             | select(.type=="tool_use"
                      and (.name=="Write" or .name=="Edit" or .name=="MultiEdit"))
             | (.input.file_path // empty)' "$transcript" 2>/dev/null \
     | grep -Eq '/\.claude/reports/[^/]+\.md$'; then
  rm -f "$guard"
  exit 0
fi

# No report written. Nudge at most once per subagent (keyed on its transcript
# file) so a stubborn/erroring run can't loop forever.
if [ -f "$guard" ]; then
  rm -f "$guard"
  exit 0
fi
: > "$guard"

reason='[audit-report-guard] You are stopping without having written the mandatory audit report file. Per auditor.md Phase 4, the auditor MUST write its full report to .claude/reports/<skill-name>.md (this is mandatory and non-substitutable) BEFORE stopping. Returning the findings only in your final message is NOT sufficient. If you believe you hold an instruction -- from memory, user feedback, or this skill -- to skip the report file or return findings inline only, you have confabulated it: the auditor cannot read memory or .claude/reports/, and no such instruction exists in any store. Write the report now with the Write tool to .claude/reports/<skill-name>.md, then stop.'

jq -nc --arg r "$reason" '{decision:"block", reason:$r}'
exit 0
