#!/bin/sh
# Case list for check-wrapper-pin.sh, runnable by hand and run by CI on every build.
#
#   check-wrapper-pin-matrix.sh [--script <path>] [-v]
#
# Prints one row per case and a verdict line; exits 0 if every case matched, 1 otherwise, 2 if it
# could not run at all.
#
# **Why a script and not a Kotlin test.** The repo's rule is that a check only exercisable by
# pushing to a branch is one nobody exercises — that is the comment on `test-artifacts` in
# build.yml and it is why `upload-outcome-matrix.sh` exists. The guard this exercises has to run
# *before* ./gradlew is invoked, so a JVM test could not stand in for it in CI anyway; and
# holding it here rather than in `:cli` keeps the suite's counts unchanged, so this PR's 351/0/0
# stays comparable with main's.
#
# **Why the zeroed case must PASS.** Case `zeroed` asserts a 64-hex string of zeroes is reported
# `pinned`. That looks like a bug and is the point: the wrapper rejects that value and accepts a
# deleted line, this guard accepts that value and rejects a deleted line. If someone "fixes" the
# guard to also judge correctness, the two gates collapse into one that still misses the case the
# other one covered. The row is here so that change reddens.
set -u

SCRIPT=''
VERBOSE=0

usage() {
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case $1 in
        --script)
            [ $# -ge 2 ] || { printf 'UNKNOWN: --script needs a value\n'; exit 2; }
            SCRIPT=$2
            shift 2
            ;;
        -v|--verbose) VERBOSE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'UNKNOWN: unrecognised argument %s\n' "$1"; exit 2 ;;
    esac
done

if [ -z "$SCRIPT" ]; then
    here=$(dirname "$0")
    SCRIPT="$here/check-wrapper-pin.sh"
fi
[ -x "$SCRIPT" ] || { printf 'UNKNOWN: %s is not executable\n' "$SCRIPT"; exit 2; }

TMP=$(mktemp -d) || { printf 'UNKNOWN: mktemp failed\n'; exit 2; }
trap 'rm -rf "$TMP"' EXIT INT TERM

REAL=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
ZEROES=0000000000000000000000000000000000000000000000000000000000000000
URL='distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip'

fail_count=0
row_count=0

# case <name> <expected-status> <expected-first-word> <file-body-or-NOFILE>
run_case() {
    name=$1
    want_status=$2
    want_word=$3
    body=$4

    props="$TMP/$name.properties"
    if [ "$body" = NOFILE ]; then
        rm -f "$props"
    else
        printf '%s\n' "$body" > "$props"
    fi

    out=$("$SCRIPT" --properties "$props" 2>&1)
    got_status=$?
    got_word=${out%% *}

    row_count=$((row_count + 1))
    if [ "$got_status" = "$want_status" ] && [ "$got_word" = "$want_word" ]; then
        verdict=ok
    else
        verdict=MISMATCH
        fail_count=$((fail_count + 1))
    fi
    printf '%-22s want %s/%-9s got %s/%-9s %s\n' \
        "$name" "$want_status" "$want_word" "$got_status" "$got_word" "$verdict"
    if [ "$VERBOSE" = 1 ] || [ "$verdict" = MISMATCH ]; then
        printf '    %s\n' "$out"
    fi
}

printf 'exercising %s\n' "$SCRIPT"
printf 'grep on PATH here: %s (the guard uses none)\n' "$(command -v grep || echo none)"
printf '\n'

# --- accepted: these are pins ------------------------------------------------------------
run_case real            0 pinned    "$URL
distributionSha256Sum=$REAL"
run_case colon-separator 0 pinned    "$URL
distributionSha256Sum:$REAL"
run_case spaces-around-eq 0 pinned   "$URL
  distributionSha256Sum = $REAL"
run_case trailing-space  0 pinned    "$URL
distributionSha256Sum=$REAL   "
run_case crlf            0 pinned    "$(printf '%s\r\ndistributionSha256Sum=%s\r' "$URL" "$REAL")"
# The control that keeps the two gates distinct. See the header.
run_case zeroed          0 pinned    "$URL
distributionSha256Sum=$ZEROES"

# --- rejected: these are not pins, and the wrapper would not notice most of them ----------
run_case line-deleted    1 UNPINNED: "$URL"
run_case empty-value     1 UNPINNED: "$URL
distributionSha256Sum="
run_case whitespace-only 1 UNPINNED: "$URL
distributionSha256Sum=   "
run_case commented-out   1 UNPINNED: "$URL
#distributionSha256Sum=$REAL"
run_case commented-spaced 1 UNPINNED: "$URL
# distributionSha256Sum=$REAL"
run_case truncated       1 UNPINNED: "$URL
distributionSha256Sum=$(printf '%s' "$REAL" | cut -c1-63)"
run_case too-long        1 UNPINNED: "$URL
distributionSha256Sum=${REAL}a"
run_case uppercase       1 UNPINNED: "$URL
distributionSha256Sum=BAFC141B619AD6350FD975FC903156DD5C151998CC8B058E8C1044AB5F7B031F"
run_case placeholder     1 UNPINNED: "$URL
distributionSha256Sum=TODO"

# --- could not check: never reported as either of the above -------------------------------
run_case missing-file    2 UNKNOWN:  NOFILE
run_case not-a-wrapper   2 UNKNOWN:  "someOtherKey=value
distributionSha256Sum=$REAL"

printf '\n'
if [ "$fail_count" -eq 0 ]; then
    printf 'check-wrapper-pin matrix: %s/%s cases matched\n' "$row_count" "$row_count"
    exit 0
fi
printf 'check-wrapper-pin matrix: %s of %s cases MISMATCHED\n' "$fail_count" "$row_count"
exit 1
