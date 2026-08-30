#!/usr/bin/env bash
#
# Regression cases for test-scope-guard.sh.
#
# The guard parses shell text, so it has two ways to fail: blocking a command that is
# fine (it once blocked a doc edit whose heredoc quoted a gradle line), and letting a
# real full-suite run through. Both directions are covered below. Run after any edit
# to the guard:  bash .claude/hooks/test-scope-guard.test.sh
#
# Each case: label, expected exit (0 = allow, 2 = deny).
set -u
G="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-scope-guard.sh"

run() {
  local label=$1 expected=$2 cmd=$3
  local out rc
  out=$(printf '%s' "$(jq -nc --arg c "$cmd" '{tool_name:"Bash",tool_input:{command:$c}}')" | bash "$G" 2>/dev/null)
  rc=$?
  if [ "$rc" = "$expected" ]; then printf 'PASS  %-46s (exit %s)\n' "$label" "$rc"
  else printf 'FAIL  %-46s (exit %s, wanted %s)\n' "$label" "$rc" "$expected"; fi
}

# --- must still DENY (exit 2) ---
run "full suite, no selector"          2 './gradlew :caffeine:test'
run "whole @CacheSpec class"           2 "./gradlew :caffeine:test --tests 'EvictionTest'"
run "class wildcard"                   2 "./gradlew :caffeine:test --tests 'Bounded*Test'"
run "one method + one class"           2 "./gradlew :caffeine:test --tests 'CacheTest.foo' --tests 'EvictionTest'"

# --- must ALLOW (exit 0) ---
run "single method"                    0 "./gradlew :caffeine:test --tests 'CacheTest.getIfPresent'"
run "class narrowed with -P"           0 "./gradlew :caffeine:test --tests 'EvictionTest' -Pkeys=strong"
run "named suite (frayTest)"           0 './gradlew :caffeine:frayTest'
run "build, not test"                  0 './gradlew :caffeine:build'
run "non-gradle command"               0 'grep -rn test .claude/rules'

# --- the false positive that prompted the fix ---
run "heredoc writing docs w/ gradlew"  0 "$(cat <<'OUTER'
python3 - <<'PY'
doc = """
Run a single test method:
  ./gradlew :caffeine:test --tests 'ClassName.methodName'
Do not run: ./gradlew :caffeine:test
"""
open('x.md','w').write(doc)
PY
OUTER
)"
run "heredoc to file, unquoted delim"  0 "$(cat <<'OUTER'
cat > notes.md <<EOF
./gradlew :caffeine:test --tests 'EvictionTest'
EOF
OUTER
)"

# --- heredoc must not mask a real invocation on another line ---
run "real run AFTER a heredoc"         2 "$(cat <<'OUTER'
cat > notes.md <<EOF
some text
EOF
./gradlew :caffeine:test
OUTER
)"
