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
# **It must never print the reassurance from a failed measurement.** "skipped is zero, so
# every tool-gated test executed" is the exact claim CLAUDE.md tells agents to lean on, and a
# parse that read nothing produces 0/0/0 — which renders as that same sentence unless the
# script distinguishes "measured zero skips" from "measured nothing". That is the defect this
# repo keeps finding (a skip reported as PASSED, a cache replay of a run that verified
# nothing), and printing it into the run summary is where it would be most believed. So every
# total is normalised to an integer before any comparison, and the no-measurement case is
# reported as no measurement.
#
# **A partial measurement is not a measurement either** (#83). "No `<testsuite>` anywhere" was
# the only failure this distinguished at first, and the same reassurance prints just as
# readily from a parse that read four files out of five: the unread one contributes zero, the
# total looks like a total, and nothing in the output says a file could not be read. So `awk`
# reports how many files it actually got a `<testsuite>` out of, that is compared against how
# many the glob matched, and a shortfall renders the counts as a floor with the reassurance
# withheld.
#
# `.github/scripts/test-summary-matrix.sh` holds this script's suite, and it is not optional
# reading if you edit here: it asserts the behaviour above *and* asserts that each guard,
# removed, brings a case back red. `./gradlew check` runs it.
#
# Deliberately mawk-compatible (that is /usr/bin/awk on ubuntu-latest): no asorti, no gawk
# extensions, no ENDFILE.
set -uo pipefail

cd "${1:-.}" || exit 0

# Everything goes to stdout, and to the step summary as well when there is one. The summary
# is where a human will look; the log is the only one of the two an API can read back, which
# matters when the reader is an agent asked to confirm what a run established.
emit() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    tee -a "$GITHUB_STEP_SUMMARY"
  else
    cat
  fi
}

# Anything that is not a plain non-negative integer becomes 0, so no branch below can be
# reached with an empty or garbage value. Without this, a missing awk leaves the totals empty,
# `[ "" -gt 0 ]` errors, and — with no `set -e` — execution falls through to the else branch
# and prints the reassurance.
num() {
  case ${1:-} in
    '' | *[!0-9]*) printf '0' ;;
    *) printf '%s' "$1" ;;
  esac
}

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
  } | emit
  exit 0
fi

# Accumulate each file whole before matching, rather than matching line by line: a
# `<testsuite …>` tag split across lines is still one tag, and a per-line regex silently
# reads it as zero suites. Buffers are per file and these files are small.
counts=$(
  awk '
    function attr(text, name) {
      if (match(text, name "=\"[^\"]*\"")) {
        return substr(text, RSTART + length(name) + 2, RLENGTH - length(name) - 3) + 0
      }
      return 0
    }
    function flush(file,   m, parts, rest, tag, got) {
      if (file == "") return
      split(file, parts, "/")
      m = parts[1]
      rest = buf
      got = 0
      while (match(rest, /<testsuite [^>]*>/)) {
        tag = substr(rest, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
        got = 1
        seen[m] = 1
        suites[m] += 1;                 SUITES += 1
        t[m] += attr(tag, "tests");     T += attr(tag, "tests")
        s[m] += attr(tag, "skipped");   S += attr(tag, "skipped")
        f[m] += attr(tag, "failures");  F += attr(tag, "failures")
        e[m] += attr(tag, "errors");    E += attr(tag, "errors")
      }
      # Per file, not per suite: the question downstream is "did every file the glob matched
      # contribute", and a file holding two suites must not pay for one holding none.
      if (got) READ += 1
    }
    FNR == 1 { flush(cur); cur = FILENAME; buf = "" }
    { buf = buf $0 " " }
    END {
      flush(cur)
      for (m in seen) printf "%s %d %d %d %d %d\n", m, suites[m], t[m], s[m], f[m], e[m]
      printf "TOTAL %d %d %d %d %d %d\n", SUITES, T, S, F, E, READ
    }
  ' "${files[@]}" 2>/dev/null
)
awk_rc=$?

modules=$(printf '%s\n' "$counts" | grep -v '^TOTAL ' | sort)
total=$(printf '%s\n' "$counts" | grep '^TOTAL ' | head -1)

tot_suites=''; tot_tests=''; tot_skipped=''; tot_failures=''; tot_errors=''; tot_read=''
if [ -n "$total" ]; then
  read -r _ tot_suites tot_tests tot_skipped tot_failures tot_errors tot_read <<< "$total"
fi
tot_suites=$(num "$tot_suites")
tot_tests=$(num "$tot_tests")
tot_skipped=$(num "$tot_skipped")
tot_failures=$(num "$tot_failures")
tot_errors=$(num "$tot_errors")
tot_read=$(num "$tot_read")

# The parse produced nothing usable: awk failed or is absent, or it ran and found no
# `<testsuite>` element in files that exist. Truncated or corrupt XML from a hard-killed test
# worker lands here too. Files were present, so this is not the empty-glob case above — it is
# a failed measurement, and the one thing it must not do is read as a good one.
measured=yes
if [ "$awk_rc" -ne 0 ] || [ -z "$total" ] || [ "$tot_suites" -eq 0 ]; then
  measured=no
  echo "::warning title=Test counts unreadable::${#files[@]} JUnit XML file(s) matched, but no <testsuite> element could be read from them (awk exit ${awk_rc}). The counts below are absent, not zero."
fi

# Some of it read, and some of it did not (#83). The totals below are then a sum over a subset
# — arithmetically fine, and a lie about coverage, because nothing on the page says which
# subset. `skipped=0` over four files out of five is the same rendering as `skipped=0` over
# five, and the reader who could tell them apart is the one who already knew the answer.
unread=0
partial=no
if [ "$measured" = yes ] && [ "$tot_read" -lt "${#files[@]}" ]; then
  partial=yes
  unread=$(( ${#files[@]} - tot_read ))
  echo "::warning title=Test counts incomplete::${unread} of ${#files[@]} JUnit XML file(s) yielded no <testsuite> element. The counts below are a lower bound, not a total."
fi

{
  echo "### Test counts (from the JUnit XML)"
  echo
  echo "| module | suites | tests | skipped | failures | errors |"
  echo "|---|---:|---:|---:|---:|---:|"
  if [ "$measured" = yes ]; then
    while read -r m su te sk fa er; do
      [ -n "$m" ] || continue
      printf '| `%s` | %s | %s | %s | %s | %s |\n' "$m" "$su" "$te" "$sk" "$fa" "$er"
    done <<< "$modules"
    # The label carries the caveat, because the table is the part that gets copied out of the
    # summary and pasted into a PR body without the prose under it.
    if [ "$partial" = yes ]; then
      row_label='**total (lower bound)**'
    else
      row_label='**total**'
    fi
    printf '| %s | **%s** | **%s** | **%s** | **%s** | **%s** |\n' \
      "$row_label" "$tot_suites" "$tot_tests" "$tot_skipped" "$tot_failures" "$tot_errors"
  else
    printf '| **total** | *unread* | *unread* | *unread* | *unread* | *unread* |\n'
  fi
  echo
  if [ "$measured" = no ]; then
    echo "> **These counts were not measured.** ${#files[@]} JUnit XML file(s) were found, but no \`<testsuite>\` element could be read from them — a missing or broken \`awk\`, or truncated XML from a killed test worker. **This says nothing about whether the tests ran or skipped**; do not read it as \`skipped=0\`."
  elif [ "$tot_tests" -eq 0 ]; then
    echo "> **No tests were counted**, across ${tot_suites} suite(s). A suite element that reports zero tests is not evidence that anything executed, so the \`skipped\` figure carries no reassurance here."
  else
    # A shortfall and a non-zero skip count are separate facts, and both get said. The
    # reassurance is the only line withheld, because it is the only one that would be false.
    if [ "$partial" = yes ]; then
      echo "> **${unread} of ${#files[@]} JUnit XML file(s) could not be read.** The counts above are a lower bound, not a total, and \`skipped\` carries no reassurance: a file that yielded no \`<testsuite>\` contributed zero to every column, including that one."
    fi
    if [ "$tot_skipped" -gt 0 ]; then
      echo '> `skipped` is not zero. A tool-gated test that skipped verified nothing; check which one.'
    elif [ "$partial" = no ]; then
      echo '> `skipped` is zero, so every tool-gated test executed rather than opting out.'
    fi
  fi
} | emit

exit 0
