#!/usr/bin/env bash
# Read tests=/skipped=/failures=/errors= straight out of the JUnit XML and put them in the
# run summary.
#
# CLAUDE.md makes those four attributes the control that catches a run which verified
# nothing: a tool-gated test now reports as genuinely skipped, so skipped="0" means those
# tests really executed. Until now that control could only be applied by someone with a
# checkout — CI threw the XML away (#65). Printing the numbers into the run summary means
# the check can be applied by reading the run, and the uploaded artifact is the backup for
# anyone who wants to diff or re-parse.
#
# This reports; it does not gate. Gradle already fails the build on a test failure, and the
# REQUIRE flags already fail it on a missing tool. A second gate here would duplicate those
# and could only add false reds.
#
# Deliberately mawk-compatible (that is /usr/bin/awk on ubuntu-latest): no asorti, no gawk
# extensions.
set -uo pipefail

cd "${1:-.}" || exit 0

summary=${GITHUB_STEP_SUMMARY:-/dev/stdout}

shopt -s nullglob
files=(*/build/test-results/*/*.xml)
shopt -u nullglob

if [ ${#files[@]} -eq 0 ]; then
  # Either nothing got as far as running a test, or the glob is wrong. Those look identical
  # from here, which is exactly how #65 survived, so say so out loud rather than staying
  # quiet about it.
  echo "::warning title=No JUnit XML::No files matched */build/test-results/*/*.xml. Either no test task ran, or the path this workflow reads is wrong."
  {
    echo "### Test counts (from the JUnit XML)"
    echo
    echo 'No JUnit XML found under `*/build/test-results/*/*.xml`.'
  } >> "$summary"
  exit 0
fi

counts=$(
  awk '
    function attr(text, name) {
      if (match(text, name "=\"[^\"]*\"")) {
        return substr(text, RSTART + length(name) + 2, RLENGTH - length(name) - 3) + 0
      }
      return 0
    }
    match($0, /<testsuite [^>]*>/) {
      line = substr($0, RSTART, RLENGTH)
      split(FILENAME, parts, "/")
      m = parts[1]
      seen[m] = 1
      suites[m] += 1;                  SUITES += 1
      t[m] += attr(line, "tests");     T += attr(line, "tests")
      s[m] += attr(line, "skipped");   S += attr(line, "skipped")
      f[m] += attr(line, "failures");  F += attr(line, "failures")
      e[m] += attr(line, "errors");    E += attr(line, "errors")
    }
    END {
      for (m in seen) printf "%s %d %d %d %d %d\n", m, suites[m], t[m], s[m], f[m], e[m]
      printf "TOTAL %d %d %d %d %d\n", SUITES, T, S, F, E
    }
  ' "${files[@]}"
)

modules=$(echo "$counts" | grep -v '^TOTAL ' | sort)
total=$(echo "$counts" | grep '^TOTAL ')
read -r _ tot_suites tot_tests tot_skipped tot_failures tot_errors <<< "$total"

{
  echo "### Test counts (from the JUnit XML)"
  echo
  echo "| module | suites | tests | skipped | failures | errors |"
  echo "|---|---:|---:|---:|---:|---:|"
  while read -r m su te sk fa er; do
    [ -n "$m" ] || continue
    printf '| `%s` | %s | %s | %s | %s | %s |\n' "$m" "$su" "$te" "$sk" "$fa" "$er"
  done <<< "$modules"
  printf '| **total** | **%s** | **%s** | **%s** | **%s** | **%s** |\n' \
    "$tot_suites" "$tot_tests" "$tot_skipped" "$tot_failures" "$tot_errors"
  echo
  if [ "$tot_skipped" -gt 0 ]; then
    echo '> `skipped` is not zero. A tool-gated test that skipped verified nothing; check which one.'
  else
    echo '> `skipped` is zero, so every tool-gated test executed rather than opting out.'
  fi
} >> "$summary"

exit 0
