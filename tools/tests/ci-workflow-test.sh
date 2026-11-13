#!/usr/bin/env sh
# Pull requests must run the unit-test and lint gate on a GitHub-hosted runner with a read-only token and no
# secrets.

set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/../.." && pwd)
ci_workflow="$repo_root/.github/workflows/ci.yml"

[ -f "$ci_workflow" ]
# Assertions read the workflow without comment lines, so prose can mention what the workflow must not do.
workflow_body=$(grep -v -E '^[[:space:]]*#' "$ci_workflow")
grep -E '^  pull_request:' "$ci_workflow" >/dev/null
grep -F 'contents: read' "$ci_workflow" >/dev/null
grep -F 'runs-on: ubuntu-latest' "$ci_workflow" >/dev/null
grep -F 'for test_script in tools/tests/*-test.sh' "$ci_workflow" >/dev/null

gate_line=$(grep -F './gradlew' "$ci_workflow")
for task in testDebugUnitTest :app:lintDebug :data:lintDebug :player:lintDebug verifyLintBaseline; do
  case " $gate_line " in
    *" $task "* | *" $task") ;;
    *)
      printf 'ci.yml must run %s\n' "$task" >&2
      exit 1
      ;;
  esac
done

forbid() {
  if printf '%s\n' "$workflow_body" | grep -F -- "$1" >/dev/null; then
    printf 'ci.yml must not %s\n' "$2" >&2
    exit 1
  fi
}

forbid 'pull_request_target' 'use pull_request_target'
forbid 'secrets.' 'read repository secrets'
forbid 'contents: write' 'request a write token'
forbid 'self-hosted' 'use a self-hosted runner'
