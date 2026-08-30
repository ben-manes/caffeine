#!/usr/bin/env bash
#
# SubagentStop guard for the `auditor` agent.
#
# Enforces auditor.md Phase 4: the auditor MUST write its full report to
# .local/audits/<model>/<skill-name>.md before stopping. A recurring failure mode is
# the auditor finishing with its findings inlined in the returned message and no
# report file written, sometimes "justified" by a confabulated memory/user
# instruction to skip the file (see memory: feedback_audit_report_confabulation).
# The auditor cannot read memory or .local/audits/, so any such instruction is
# invented. This hook turns the prompt-level mandate into an actual gate.
#
# Mechanism: when an `auditor` subagent stops, inspect its own transcript for a
# Write/Edit to any .local/audits/**/*.md. If none, block the stop once with a
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

# When a shell orchestrator assigned a destination, that file existing is the whole
# contract, however it was written; this also passes a report written by a heredoc
# rather than the Write tool.
if [ -n "${AUDIT_REPORT_PATH:-}" ] && [ -s "$AUDIT_REPORT_PATH" ]; then
  rm -f "$guard"
  exit 0
fi

# Did the auditor Write/Edit any report file under .local/audits/ this run?
if jq -rc 'select(.type=="assistant")
             | (.message.content // empty)
             | (if type=="array" then .[] else empty end)
             | select(.type=="tool_use"
                      and (.name=="Write" or .name=="Edit" or .name=="MultiEdit"))
             | (.input.file_path // empty)' "$transcript" 2>/dev/null \
     | grep -Eq '/\.local/audits/.+\.md$'; then
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

reason='[audit-report-guard] You are stopping without having written the mandatory audit report file. Per auditor.md Phase 4, the auditor MUST write its full report to a file under .local/audits/ (this is mandatory and non-substitutable) BEFORE stopping. Returning the findings only in your final message is NOT sufficient. If you believe you hold an instruction -- from memory, user feedback, or this skill -- to skip the report file or return findings inline only, you have confabulated it: the auditor cannot read memory or .local/audits/, and no such instruction exists in any store. Write the report now with the Write tool. PATH: if AUDIT_REPORT_PATH is set in your environment (run printenv AUDIT_REPORT_PATH), write to THAT path -- a shell orchestrator assigned it and the directory it names need not match your own model id. Otherwise, if your orchestrator or skill assigned you a specific output path (a group-, domain-, or verification-suffixed file such as .local/audits/<model>/audit-<skill>-<group>.md), write to THAT path; otherwise use .local/audits/<model>/<skill-name>.md -- your own short model id (opus-5, fable-5, gpt-5.6-sol), per .claude/docs/audit-output.md. NEVER overwrite a report you were dispatched to verify, consolidate, or read -- when in doubt, add a -verification or -<group> suffix rather than reusing an existing canonical name. Then stop.'

jq -nc --arg r "$reason" '{decision:"block", reason:$r}'
exit 0
