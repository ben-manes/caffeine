#!/usr/bin/env bash
#
# PreToolUse(Bash) guard for the Gradle `test` task.
#
# Enforces .claude/rules/testing.md: a single method (`--tests 'Class.method'`) is fine
# even when it sweeps the full @CacheSpec matrix, but a whole class or the full suite is
# not, unless narrowed with the -P filters. The rule is unambiguous and still gets broken,
# because a change with a wide blast radius reads as a licence to run wide instead of as
# work to convert "wide" into named methods.
#
# Scope: only the plain `test` task. The named suites (frayTest, lincheckTest, fuzzTest,
# jcstress, googleTest, apacheTest, ...) are meant to be run whole and are left alone.
#
# Escape hatches, both sanctioned by the rule: add a -P filter, or name the methods.
#
# Fails OPEN on any tooling/parse problem so it can never wedge a command.
#
# stdin: PreToolUse hook JSON (tool_name, tool_input, ...).
set -u

input=$(cat)

command -v jq >/dev/null 2>&1 || exit 0
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""' 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0

# Strip heredoc bodies before matching. A `./gradlew ... --tests ...` line inside a
# heredoc is data being written to a file (documentation, a script, a report), not a
# command this shell runs; matching it blocks legitimate edits to the very rules this
# guard enforces. A real gradle invocation is never inside a heredoc body.
scan=$(printf '%s\n' "$cmd" | awk '
  BEGIN { delim = "" }
  delim != "" { if ($0 == delim || $0 == delim"\r") { delim = "" } ; next }
  {
    line = $0
    if (match(line, /<<-?[[:space:]]*'"'"'[^'"'"']+'"'"'/) \
     || match(line, /<<-?[[:space:]]*"[^"]+"/) \
     || match(line, /<<-?[[:space:]]*[A-Za-z_][A-Za-z0-9_]*/)) {
      d = substr(line, RSTART, RLENGTH)
      gsub(/^<<-?[[:space:]]*/, "", d)
      gsub(/['"'"'"]/, "", d)
      delim = d
    }
    print line
  }')
[ -n "$scan" ] || exit 0

# A gradle invocation running the plain `test` task, in any module.
printf '%s' "$scan" | grep -qE 'gradlew' || exit 0
printf '%s' "$scan" | grep -qE '(^|[[:space:]])(:[A-Za-z0-9_.:-]+:)?test([[:space:]]|$)' || exit 0

# A -P filter is the sanctioned way to narrow a class-scoped run.
printf '%s' "$scan" | grep -qE '\-P(implementation|keys|values|compute|stats)=' && exit 0

deny() {
  cat >&2 <<EOF
[test-scope-guard] Blocked: this runs a whole @CacheSpec class or the full suite.

.claude/rules/testing.md: "Run a single test method with --tests 'Class.method' -- fine
even if it sweeps the full @CacheSpec matrix. Don't run a whole @CacheSpec class or the
full suite locally; avoid them, or narrow with -P flags when you must (CI runs the full
matrix, sharded across 40 workers)."

A change with a wide blast radius is not an exemption. It is the work of naming the
methods that blast radius actually reaches: read the tests that pin the behaviour you
changed and list them.

  ./gradlew :caffeine:test --tests 'BoundedLocalCacheTest.someMethod' --tests 'CacheTest.other'
  ./gradlew :caffeine:test --tests 'EvictionTest' -Pkeys=strong -Pvalues=strong

Over-pinning can empty a method's matrix and report a JUnit initializationError, which is
a filter artifact and not a failure.

Offending command:
  $cmd
EOF
  exit 2
}

selectors=$(printf '%s' "$scan" \
  | grep -oE "\-\-tests[[:space:]]+('[^']*'|\"[^\"]*\"|[^[:space:]]+)" \
  | sed -E "s/^--tests[[:space:]]+//; s/^['\"]//; s/['\"]\$//")

# No selector at all is the whole suite.
[ -n "$selectors" ] || deny

while IFS= read -r selector; do
  [ -n "$selector" ] || continue
  case "$selector" in
    *.*) ;;
    *) deny ;;
  esac
  # The trailing segment must be a method: lowercase-initial, or a wildcard that could
  # only match one.
  case "${selector##*.}" in
    [a-z_]*|\**) ;;
    *) deny ;;
  esac
done <<EOF
$selectors
EOF

exit 0
