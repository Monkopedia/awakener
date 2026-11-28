#!/bin/sh
# `.github/scripts/check-results-fresh.sh`'s own suite. Run by `gradlew resultsFreshMatrixTest`,
# which `check` depends on, for the reason its three siblings are: the script is edited here, and
# a red that only arrives after a push arrives after the change has been reported as verified.
#
#   check-results-fresh-matrix.sh <script-under-test> <work-dir> <report>
#
# Same discipline as the siblings, smaller: every row names the mutant it must go red against,
# and each mutant is the committed script with one guard textually removed. A row that still
# passes with its guard taken out is testing nothing, and an anchor that no longer matches is a
# mutant that was never built — exit 2, because that suite could not vouch, which is a different
# fact from a row failing (exit 1).
set -eu

SCRIPT=$1
WORK=$2
REPORT=$3

[ -f "$SCRIPT" ] || { echo "results-fresh-matrix: CANNOT VOUCH: no script at $SCRIPT" >&2; exit 2; }
rm -rf "$WORK"
mkdir -p "$WORK"
case $REPORT in */*) mkdir -p "${REPORT%/*}" ;; esac
: >"$REPORT"

note() { printf '%s\n' "$1" | tee -a "$REPORT"; }
die() { note "CANNOT VOUCH: $1"; exit 2; }

suite() { # <file> <timestamp>
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="x" tests="1" skipped="0" failures="0" errors="0" timestamp="%s" hostname="h" time="0.1">\n</testsuite>\n' "$2" >"$1"
}

F=$WORK/fixtures
mkdir -p "$F"
echo 2026-11-27T07:42:30 >"$F/since"
# The realistic mistake — `date +%s` rather than the ISO form — and one that sorts before every
# timestamp, so without its guard it would call any suite fresh.
echo 1764229350 >"$F/bad-since"
suite "$F/fresh.xml" 2026-11-27T07:51:16.323Z
suite "$F/same-second.xml" 2026-11-27T07:42:30.001Z
suite "$F/stale.xml" 2026-11-19T06:12:51.805Z
# One millisecond before the start, on the same day and in the same hour and minute. A same-day
# replay is the realistic one — all six `:wm` hits in #121 were a PR's results replayed into its
# own merge run — and only this fixture stops a comparison coarsened to the day, hour or minute
# from passing the whole matrix.
suite "$F/just-before.xml" 2026-11-27T07:42:29.999Z
suite "$F/no-zone.xml" 2026-11-27T07:51:16.323
printf '<testsuite name="x" tests="1">\n</testsuite>\n' >"$F/no-stamp.xml"

# Mutants: name, then the exact line removed from the script. Removing the line must change the
# script, or the mutant is the original under another name.
mutant() { # <name> <fixed-string line>
  m=$WORK/mutant-$1.sh
  grep -vF -- "$2" "$SCRIPT" >"$m" || true
  cmp -s "$m" "$SCRIPT" && die "mutant $1: anchor not found: $2"
  [ "$(wc -l <"$m")" -eq $(($(wc -l <"$SCRIPT") - 1)) ] || die "mutant $1: anchor matched more than one line: $2"
}
mutant stale     'a replayed result, not a run.'
mutant nofiles   'none is not a pass.'
mutant badsince  'nothing here can be called fresh.'
mutant badstamp  'no UTC suite timestamp (got'

# Rows: expected exit, the mutant that must NOT give that exit ('-' for a row that guards no
# line, such as a pass), then arguments.
rows=0
failed=0
row() {
  want=$1 kills=$2
  shift 2
  args=$(for a do printf '%s ' "${a##*/}"; done)
  rows=$((rows + 1))
  got=0
  sh "$SCRIPT" "$@" >"$WORK/out" 2>&1 || got=$?
  if [ "$got" -ne "$want" ]; then
    failed=$((failed + 1))
    note "FAIL  want $want got $got: $args"
    sed 's/^/      | /' "$WORK/out" | head -8 | tee -a "$REPORT" >/dev/null
    return
  fi
  if [ "$kills" != - ]; then
    mgot=0
    sh "$WORK/mutant-$kills.sh" "$@" >/dev/null 2>&1 || mgot=$?
    if [ "$mgot" -eq "$want" ]; then
      failed=$((failed + 1))
      note "FAIL  mutant $kills also exits $want, so this row tests nothing: $args"
      return
    fi
  fi
  note "ok    $want ($kills): $args"
}

row 0 -        "$F/since" "$F/fresh.xml"
row 0 -        "$F/since" "$F/same-second.xml" "$F/fresh.xml"
row 1 stale    "$F/since" "$F/stale.xml"
row 1 stale    "$F/since" "$F/fresh.xml" "$F/stale.xml"
row 1 stale    "$F/since" "$F/just-before.xml"
row 1 nofiles  "$F/since"
# Unguarded by a line of their own: with the timestamp guard removed, an empty timestamp still
# sorts before any start time and reddens as a replay. Rows, so a change that greens them shows.
row 1 -        "$F/since" "$F/*/build/test-results/*/TEST-*.xml"
row 1 -        "$F/since" "$F/no-stamp.xml"
row 1 badsince "$F/bad-since" "$F/fresh.xml"
row 1 badsince "$F/absent-since" "$F/fresh.xml"
# A fresh suite whose timestamp is not UTC: only the timestamp guard stops it passing as fresh.
row 1 badstamp "$F/since" "$F/no-zone.xml"

note "$rows rows, $failed failed, 4 of 4 mutants built"
[ "$failed" -eq 0 ]
