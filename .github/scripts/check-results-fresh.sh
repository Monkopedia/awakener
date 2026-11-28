#!/bin/sh
# Fail unless every JUnit XML handed to it was written by the run that started at <since> (#121).
#
#   check-results-fresh.sh <since-file> <results.xml>...
#
# <since-file> holds one UTC time, `YYYY-MM-DDTHH:MM:SS`, written immediately before the build
# that produced the results. Each file's `<testsuite timestamp=…>` is when that suite *executed*,
# and a suite restored from the build cache — or replayed UP-TO-DATE from a restored project
# directory — carries the timestamp of the run that recorded it, not this one. That is the whole
# test: it reads the artefact rather than the console, so it holds whichever route the replay took.
#
# Why it exists: CI ran `./gradlew build` with the build cache live, and on all 38 of the 39 most
# recent runs that reached Gradle (up to `ad62f4b`), `:config:jvmTest` and `:connector:jvmTest`
# came out FROM-CACHE — on `ad62f4b`'s own run, both modules' XML said 2026-11-19, eight days
# before the run that uploaded it. `:wm:jvmTest` came out FROM-CACHE on six of them, two of those
# runs on `main`. `test-summary.sh` counted all of it as executed, and the floors cannot tell
# either, because a replayed suite reports the same count as a real one. The CI step now passes
# `--no-build-cache`; this is what says so on every run, and what reddens the day that stops being
# true.
#
# Fails closed: no files, a file that does not exist (an unmatched glob arrives literally), a
# missing or malformed <since>, or a suite with no readable UTC timestamp are all reds. Each is a
# run this check could not vouch for, and green is the claim that it could. Each guard is one line
# so that `check-results-fresh-matrix.sh` can remove it whole and watch its row stop reddening.
#
# Second precision against a marker taken before the build began, so a suite that ran in the same
# second the marker was written is fresh. Comparison is as strings, which orders correctly for
# this fixed-width UTC form.
set -eu

[ $# -ge 1 ] || { echo "::error::usage: check-results-fresh.sh <since-file> <results.xml>..."; exit 1; }
since_file=$1
shift

since=$(cat "$since_file" 2>/dev/null || true)
pattern='[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]'
# shellcheck disable=SC2254 # $pattern is a glob on purpose.
case $since in $pattern) ;; *) echo "::error::No readable start time in '$since_file' (got '$since'), so nothing here can be called fresh."; exit 1 ;; esac

if [ $# -eq 0 ]; then echo "::error::No JUnit XML to check. A build that ran tests leaves some; none is not a pass."; exit 1; fi

checked=0
bad=0
for f do
  ts=$(sed -n 's/.*<testsuite [^>]*timestamp="\([^"]*\)".*/\1/p' "$f" 2>/dev/null | head -n 1)
  # A file that does not exist lands here too, which is how an unmatched glob arrives.
  case $ts in ${pattern}*Z) ;; *) echo "::error file=$f::Missing, unreadable, or no UTC suite timestamp (got '$ts')."; bad=$((bad + 1)); continue ;; esac
  ts=$(printf '%s' "$ts" | cut -c1-19)
  checked=$((checked + 1))
  if expr "$ts" \< "$since" >/dev/null; then echo "::error file=$f::Executed at ${ts}Z, before this run started at ${since}Z: a replayed result, not a run."; bad=$((bad + 1)); fi
done

if [ "$bad" -ne 0 ]; then
  echo "$bad of $# results files are not from this run."
  exit 1
fi
echo "All $checked results files executed in this run (since ${since}Z)."
