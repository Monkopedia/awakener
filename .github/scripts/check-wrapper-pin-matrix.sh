#!/bin/sh
# The wrapper-pin check's own suite. Run by `gradlew wrapperPinMatrixTest`, which `check` depends
# on, and by CI ahead of `check-wrapper-pin.sh` itself.
#
#   check-wrapper-pin-matrix.sh [--script <path>] [--work <dir>] [--report <path>] [-v]
#
# Every argument is optional, because CI invokes this with none and an agent types it with none.
#
# Three exit statuses, because a suite that did not run is not a suite that passed (#89):
#
#   0  every declared mutant ran and every row behaved
#   1  a row failed
#   2  the suite could not vouch — it never reached the state where a row verdict means anything
#      (a mutant that will not build, an anchor that has moved, a declared mutant that never ran)
#
# **Why a script and not a Kotlin test.** The repo's rule is that a check only exercisable by
# pushing to a branch is one nobody exercises — that is the comment on `test-artifacts` in
# build.yml and it is why `upload-outcome-matrix.sh` exists. The guard this exercises has to run
# *before* ./gradlew is invoked, so a JVM test could not stand in for it in CI anyway.
#
# **Why it is also a Gradle task** (#141). It used to say that holding it here rather than in
# `:cli` "keeps the suite's counts unchanged", as though that were the reason nothing local ran
# it. It never was: the three sibling matrices are `Exec` tasks and move no JUnit count either.
# The real state was that `.github/workflows/build.yml` was the only thing in the tree that named
# this file, so `./gradlew build` on a developer machine — the command CLAUDE.md calls the full
# autonomous check — did not exercise it, and a red arrived only after a push, which is after the
# reasoning that produced the change has already been reported as verified.
#
# **Why every row names a mutant.** A row that passes with and without the guard it is aimed at
# is not testing it, and this file had seventeen such rows: `AWAKENER_MATRIX_MIN_MUTANTS` appeared
# four times in each of the three sibling suites and zero times here. So each row below names one
# or more mutants — the committed `check-wrapper-pin.sh` with one guard spliced out — and the
# mutant phase asserts the row goes red against each. `pre101` is the era mutant: it is the
# committed script with an absent pin accepted, which is exactly the pre-#101 behaviour the guard
# exists to catch, and rows tagged `pre101!` assert it reports `pinned` — the defect reproduced
# rather than described.
#
# **Why the zeroed case must PASS, and what its mutant is.** Case `zeroed` asserts a 64-hex string
# of zeroes is reported `pinned`. That looks like a bug and is the point: the wrapper rejects that
# value and accepts a deleted line, this guard accepts that value and rejects a deleted line. If
# someone "fixes" the guard to also judge correctness, the two gates collapse into one that still
# misses the case the other one covered. The row is here so that change reddens — and until #141
# nothing checked that it would. `correctnessjudge` is that change, spliced in: the committed
# script plus a rejection of an all-zeroes value. The row must go red against it. That is the
# boundary this checker sits on, stated as a counterfactual instead of as a comment: it judges
# **presence, not correctness**.
#
# The mutant roster is a single table that is *also* the invocation list, so a mutant that is
# declared and never run is unrepresentable rather than merely detected (#89).
#
# `AWAKENER_MATRIX_MIN_MUTANTS` (default `all`) is how much of the counterfactual phase has to
# have executed for a 0 — the same switch, spelled the same way, as the other three matrices.
#
# Written for POSIX sh.
set -u

SCRIPT=''
WORK=''
REPORT=''
VERBOSE=0

usage() {
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

# Exit 2, with the siblings' word. "The suite could not run" and "a row failed" are different
# facts and a caller must not have to read prose to tell them apart.
die() {
    printf 'check-wrapper-pin-matrix: CANNOT VOUCH: %s\n' "$1"
    if [ -n "$REPORT" ]; then
        printf 'check-wrapper-pin-matrix: CANNOT VOUCH: %s\n' "$1" >>"$REPORT" 2>/dev/null
    fi
    exit 2
}

while [ $# -gt 0 ]; do
    case $1 in
        --script)
            [ $# -ge 2 ] || die "--script needs a value"
            SCRIPT=$2
            shift 2
            ;;
        --work)
            [ $# -ge 2 ] || die "--work needs a value"
            WORK=$2
            shift 2
            ;;
        --report)
            [ $# -ge 2 ] || die "--report needs a value"
            REPORT=$2
            shift 2
            ;;
        -v|--verbose) VERBOSE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unrecognised argument $1" ;;
    esac
done

if [ -z "$SCRIPT" ]; then
    here=$(dirname "$0")
    SCRIPT="$here/check-wrapper-pin.sh"
fi
[ -x "$SCRIPT" ] || die "$SCRIPT is not executable"
SCRIPT=$(CDPATH= cd -P -- "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")

# The guard under test deliberately uses no grep — see its header. This suite does: the mutant
# table is parsed with `sed` and the splice anchors are confirmed present-then-gone with `grep -F`.
# A missing one is the loud failure the tool fingerprints in build.gradle.kts exist to convert a
# silent one into, so it dies here rather than running with less coverage and staying green.
command -v grep >/dev/null 2>&1 || die "no grep; the splice cannot confirm its own anchors"
command -v awk >/dev/null 2>&1 || die "no awk; the splice needs one"

TMP=$(mktemp -d) || die "mktemp failed"
trap 'rm -rf "$TMP"' EXIT INT TERM

if [ -z "$WORK" ]; then
    WORK=$TMP/work
fi
rm -rf "$WORK"
mkdir -p "$WORK" || die "could not create work directory $WORK"
WORK=$(CDPATH= cd -P -- "$WORK" && pwd)

if [ -n "$REPORT" ]; then
    case $REPORT in */*) mkdir -p "${REPORT%/*}" || die "could not create report directory" ;; esac
    : >"$REPORT" || die "could not write report at $REPORT"
fi

note() {
    printf '%s\n' "$1"
    if [ -n "$REPORT" ]; then
        printf '%s\n' "$1" >>"$REPORT"
    fi
}

REAL=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
ZEROES=0000000000000000000000000000000000000000000000000000000000000000
URL='distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip'

fail_count=0
row_count=0
real_fail=0
real_rows=0

MODE=real
MUTANT=''
MUTANT_REASSURE=no
RUN_SCRIPT=$SCRIPT
GUARDS_SEEN=''

# check <name> <guards> <expected-status> <expected-first-word> <file-body-or-NOFILE>
#
# Naming no guard is rejected — a row nothing can break is a row that proves nothing.
check() {
    name=$1
    guards=$2
    want_status=$3
    want_word=$4
    body=$5

    if [ "$MODE" = mutant ]; then
        case " $guards " in
            *" $MUTANT "* | *" $MUTANT! "*) ;;
            *) return 0 ;;
        esac
    else
        [ -n "$guards" ] || die "row $name names no guard"
        for gd in $guards; do
            gd=${gd%!}
            case " $MUTANTS " in
                *" $gd "*) ;;
                *) die "row $name names guard '$gd', which is not a mutant. A guard that matches no mutant drops this row out of the counterfactual phase without failing anything." ;;
            esac
            case " $GUARDS_SEEN " in
                *" $gd "*) ;;
                *) GUARDS_SEEN="$GUARDS_SEEN $gd" ;;
            esac
        done
    fi

    props="$WORK/$name.properties"
    if [ "$body" = NOFILE ]; then
        rm -f "$props"
    else
        printf '%s\n' "$body" > "$props"
    fi

    out=$("$RUN_SCRIPT" --properties "$props" 2>&1)
    got_status=$?
    got_word=${out%% *}

    row_count=$((row_count + 1))
    matched=no
    if [ "$got_status" = "$want_status" ] && [ "$got_word" = "$want_word" ]; then
        matched=yes
    fi

    why=''
    if [ "$MODE" = mutant ]; then
        # Inverted: the guard is gone, so the row has to fail.
        [ "$matched" = no ] || why="the guard was removed and the row still matched, so it does not test it"
        # `!` says this row reproduces the defect rather than merely noticing its absence: under an
        # era mutant it must report the verdict the pre-fix guard reported.
        case " $guards " in
            *" $MUTANT! "*)
                if [ "$MUTANT_REASSURE" = yes ] && [ -z "$why" ]; then
                    if [ "$got_word" != pinned ] || [ "$got_status" != 0 ]; then
                        why="expected the mutant to report pinned/0 for this row, got $got_word/$got_status"
                    fi
                fi
                ;;
        esac
        if [ -n "$why" ]; then
            verdict=SURVIVED
            fail_count=$((fail_count + 1))
        else
            verdict=red
        fi
    else
        real_rows=$((real_rows + 1))
        if [ "$matched" = yes ]; then
            verdict=ok
        else
            verdict=MISMATCH
            fail_count=$((fail_count + 1))
            real_fail=$((real_fail + 1))
        fi
    fi

    note "$(printf '%-22s%-18s want %s/%-9s got %s/%-9s %s' \
        "$name" "${MUTANT:+[$MUTANT]}" "$want_status" "$want_word" "$got_status" "$got_word" "$verdict")"
    if [ "$VERBOSE" = 1 ] || [ -n "$why" ]; then
        note "$(printf '    %s' "$out")"
    fi
    if [ -n "$why" ]; then
        note "$(printf '    %s' "$why")"
    fi
}

rows() {
    # --- accepted: these are pins ------------------------------------------------------------
    check real            'keyname'          0 pinned    "$URL
distributionSha256Sum=$REAL"
    check colon-separator 'separator'        0 pinned    "$URL
distributionSha256Sum:$REAL"
    check spaces-around-eq 'spaceskip'       0 pinned    "$URL
  distributionSha256Sum = $REAL"
    check trailing-space  'trimws'           0 pinned    "$URL
distributionSha256Sum=$REAL   "
    check crlf            'crlfhandling'     0 pinned    "$(printf '%s\r\ndistributionSha256Sum=%s\r' "$URL" "$REAL")"
    # The control that keeps the two gates distinct. See the header.
    check zeroed          'correctnessjudge' 0 pinned    "$URL
distributionSha256Sum=$ZEROES"

    # --- rejected: these are not pins, and the wrapper would not notice most of them ----------
    check line-deleted    'pre101!'          1 UNPINNED: "$URL"
    check empty-value     'emptyaccept'      1 UNPINNED: "$URL
distributionSha256Sum="
    check whitespace-only 'emptyaccept'      1 UNPINNED: "$URL
distributionSha256Sum=   "
    check commented-out   'pre101!'          1 UNPINNED: "$URL
#distributionSha256Sum=$REAL"
    check commented-spaced 'pre101!'         1 UNPINNED: "$URL
# distributionSha256Sum=$REAL"
    check truncated       'length'           1 UNPINNED: "$URL
distributionSha256Sum=$(printf '%s' "$REAL" | cut -c1-63)"
    check too-long        'length'           1 UNPINNED: "$URL
distributionSha256Sum=${REAL}a"
    check uppercase       'hex'              1 UNPINNED: "$URL
distributionSha256Sum=BAFC141B619AD6350FD975FC903156DD5C151998CC8B058E8C1044AB5F7B031F"
    check placeholder     'hexlength'        1 UNPINNED: "$URL
distributionSha256Sum=TODO"

    # --- could not check: never reported as either of the above -------------------------------
    check missing-file    'verdictword unknownexit'          2 UNKNOWN:  NOFILE
    check not-a-wrapper   'verdictword unknownexit urlcheck' 2 UNKNOWN:  "someOtherKey=value
distributionSha256Sum=$REAL"
}

# ------------------------------------------------------------------ the mutant table
#
# Declaring a mutant and running it are the same act — see #89, and the identical note in
# `staleness-matrix.sh`. `MUTANTS` is derived from this table and the mutant phase is a loop over
# it, so a name that is declared, named by rows, and never run cannot be written.
#
# Format, one record per line: `name|reassure|mutation [mutation …]`. `reassure` is whether rows
# tagged `name!` must see the mutant report `pinned`/0 — the pre-#101 verdict for an absent pin,
# and the thing this guard exists about.
#
# Several records name more than one mutation, and that is the layering rather than padding:
# removing one of a pair alone still lands on another honest `UNPINNED`, so the row would stay
# green and assert nothing. `emptyaccept` is the clearest — drop only the empty-value check and a
# blank sum falls through to the length check, which rejects it for a different reason.
MUTANT_TABLE='
# The era mutant. An absent pin accepted is exactly the pre-#101 behaviour: the wrapper downloads
# the distribution, runs it, and exits 0 having verified nothing. Rows tagged pre101! reproduce
# that verdict rather than describing it.
pre101|yes|presence

# The value guards. `emptyaccept` and `hexlength` take two mutations each because the remaining
# guard would otherwise catch the same fixture for a different reason and the row would stay green.
emptyaccept|no|emptycheck lengthcheck
length|no|lengthcheck
hex|no|hexcheck
hexlength|no|hexcheck lengthcheck

# "Could not check" keeping its own word and its own exit status, separately, because a caller may
# be reading only one of them — the #123 shape, one instrument over.
verdictword|no|verdictword
unknownexit|no|unknownexit
urlcheck|no|urlcheck

# The parse. Each is one accepted spelling of the property line, and each row above is the only
# thing that would notice the parse narrowing under it. `crlfhandling` takes both the CR strip and
# the trailing-whitespace strip: measured on GNU sed 4.10, `[[:space:]]` matches CR, so removing
# the `tr -d` alone leaves the trailing-space sed to clean up after it and the row stays green.
separator|no|separator
spaceskip|no|spaceskip
trimws|no|trimws
crlfhandling|no|crstrip trimws

# The boundary. See the header: this guard judges presence, not correctness, and `zeroed` is the
# row that says so. `correctnessjudge` is the "improvement" that would collapse the two gates into
# one, and `zeroed` must go red against it.
correctnessjudge|no|judgecorrectness

# The realistic accident during a wrapper bump: the guard left reading a property name the wrapper
# no longer writes. `real` is the row that notices.
keyname|no|keyname
'

mutant_rows() {
    printf '%s\n' "$MUTANT_TABLE" | sed -e 's/^[[:space:]]*//' -e '/^#/d' -e '/^$/d'
}

mutant_row_for() {
    mutant_rows | while IFS= read -r r; do
        case $r in
            "$1|"*) printf '%s\n' "$r"; return 0 ;;
        esac
    done
}

MUTANTS=''
for _n in $(mutant_rows | sed 's/|.*//'); do
    case " $MUTANTS " in
        *" $_n "*) die "mutant '$_n' appears twice in MUTANT_TABLE" ;;
    esac
    MUTANTS="$MUTANTS $_n"
done
[ -n "$MUTANTS" ] || die "MUTANT_TABLE parsed to no mutants at all"
MUTANTS=${MUTANTS# }
MUTANTS_DECLARED=0
for _n in $MUTANTS; do MUTANTS_DECLARED=$((MUTANTS_DECLARED + 1)); done
MUTANTS_RUN=''

MIN_MUTANTS=${AWAKENER_MATRIX_MIN_MUTANTS:-all}
case $MIN_MUTANTS in
    all) ;;
    ''|*[!0-9]*)
        die "AWAKENER_MATRIX_MIN_MUTANTS must be 'all' or a non-negative integer, got '$MIN_MUTANTS'" ;;
esac

# ------------------------------------------------------------------ phase 1: the real script
note "exercising $SCRIPT"
note "grep on PATH here: $(command -v grep || echo none) (the guard uses none; this suite does)"
note ''
note '# every row against the committed script'
rows

note ''
if [ "$real_fail" -eq 0 ]; then
    note "check-wrapper-pin matrix: $real_rows/$real_rows cases matched"
else
    note "check-wrapper-pin matrix: $real_fail of $real_rows cases MISMATCHED"
fi

for m in $MUTANTS; do
    case " $GUARDS_SEEN " in
        *" $m "*) ;;
        *) die "mutant '$m' is named by no row, so its phase asserts nothing" ;;
    esac
done

# ------------------------------------------------------------------ mutants
#
# Literal splices, both halves asserted: the anchor has to be present before and gone after, so a
# mutant cannot silently become a copy of the original and pass its whole phase by testing nothing.
splice() {
    grep -Fq -- "$2" "$1" || die "mutant anchor absent in $1: $2"
    OLD=$2 NEW=$3 awk '
        BEGIN { old = ENVIRON["OLD"]; new = ENVIRON["NEW"] }
        {
          line = $0; out = ""
          while ((i = index(line, old)) > 0) {
            out = out substr(line, 1, i - 1) new
            line = substr(line, i + length(old))
            n++
          }
          print out line
        }
        END { if (n == 0) exit 3 }
    ' "$1" >"$1.spliced" || die "splice failed for anchor: $2"
    mv "$1.spliced" "$1"
    chmod +x "$1"
    if grep -Fq -- "$2" "$1"; then
        die "mutant anchor survived the splice in $1: $2"
    fi
}

apply() {
    f=$WORK/mutant/$1.sh
    case $2 in
        presence)
            splice "$f" '[ -n "$present" ] ||' \
                '[ -z "$present" ] && { printf "pinned absent\n"; exit 0; }; [ "x$present" != x ] ||' ;;
        emptycheck)
            splice "$f" '[ -n "$value" ] ||' '[ 1 = 1 ] ||' ;;
        lengthcheck)
            splice "$f" '[ "${#value}" -eq 64 ] ||' '[ 1 = 1 ] ||' ;;
        hexcheck)
            # `abcdef` is all lowercase hex, so the one arm below it can never match and the
            # charset guard is gone. A non-hex sentinel would have *fired* the arm instead.
            splice "$f" 'case $value in' 'case abcdef in' ;;
        verdictword)
            splice "$f" "printf 'UNKNOWN: %s\\n' \"\$1\"" "printf 'UNPINNED: %s\\n' \"\$1\"" ;;
        unknownexit)
            splice "$f" 'exit 2  # the status that means "could not check"' 'exit 1' ;;
        urlcheck)
            splice "$f" '[ -n "$url" ] ||' '[ 1 = 1 ] ||' ;;
        separator)
            splice "$f" '${KEY}[[:space:]]*[=:]' '${KEY}[[:space:]]*=' ;;
        spaceskip)
            splice "$f" '^[[:space:]]*${KEY}[[:space:]]*[=:]' '^${KEY}[=:]' ;;
        trimws)
            splice "$f" "| sed 's/[[:space:]]*\$//'" '| cat' ;;
        crstrip)
            splice "$f" "| tr -d '\\r'" '| cat' ;;
        judgecorrectness)
            splice "$f" "printf 'pinned %s\\n' \"\$value\"" \
                'case $value in *[!0]*) ;; *) unpinned "the checksum is all zeroes" ;; esac; printf "pinned %s\n" "$value"' ;;
        keyname)
            splice "$f" 'KEY=distributionSha256Sum' 'KEY=distributionShaTwoFiveSix' ;;
        *) die "apply: unknown mutation '$2'" ;;
    esac
}

# The fixture the probe below uses: a well-formed, really-pinned properties file. Every mutant must
# still be a working script against it, or "red" would mean the mutant is broken rather than that
# the row tests its guard.
PROBE=$WORK/probe.properties
printf '%s\ndistributionSha256Sum=%s\n' "$URL" "$REAL" >"$PROBE"

run_mutant() {
    MUTANT=$1
    MUTANT_REASSURE=$2
    shift 2
    mkdir -p "$WORK/mutant" || die "could not create $WORK/mutant"
    cp "$SCRIPT" "$WORK/mutant/$MUTANT.sh" || die "could not copy the script under test"
    chmod +x "$WORK/mutant/$MUTANT.sh"
    for mm in "$@"; do apply "$MUTANT" "$mm"; done
    sh -n "$WORK/mutant/$MUTANT.sh" || die "mutant $MUTANT does not parse"

    # Deliberately not "must print `pinned`" for every mutant — `keyname` legitimately does not,
    # and a probe that hard-codes one verdict would rule out the mutants that change which verdict
    # is right. What it does assert is that the mutant is still this instrument: one line, one of
    # the three verdicts, one of the three statuses.
    probe=$("$WORK/mutant/$MUTANT.sh" --properties "$PROBE" 2>&1)
    probe_rc=$?
    case $probe_rc in
        0|1|2) ;;
        *) die "mutant $MUTANT exits $probe_rc on the real-pin fixture, so every row it reddens would be red for the wrong reason: $probe" ;;
    esac
    case $probe in
        pinned\ *|UNPINNED:\ *|UNKNOWN:\ *) ;;
        *) die "mutant $MUTANT printed '$probe' on the real-pin fixture, which is none of the three verdicts" ;;
    esac
    if [ "$(printf '%s\n' "$probe" | wc -l)" -ne 1 ]; then
        die "mutant $MUTANT printed more than one line on the real-pin fixture: $probe"
    fi

    RUN_SCRIPT=$WORK/mutant/$MUTANT.sh
    note ''
    note "# counterfactual: the rows that name '$MUTANT' must go red without it"
    before=$row_count
    rows
    [ "$row_count" -gt "$before" ] || die "mutant $MUTANT ran no rows at all"
    MUTANTS_RUN="$MUTANTS_RUN $MUTANT"
}

MODE=mutant
for mutant_name in $MUTANTS; do
    mutant_record=$(mutant_row_for "$mutant_name")
    [ -n "$mutant_record" ] || die "no MUTANT_TABLE record for '$mutant_name'"
    mutant_rest=${mutant_record#*|}
    mutant_reassure=${mutant_rest%%|*}
    mutant_muts=${mutant_rest#*|}
    case $mutant_reassure in
        yes|no) ;;
        *) die "mutant '$mutant_name' declares reassure '$mutant_reassure', wanted yes or no" ;;
    esac
    [ -n "$mutant_muts" ] || die "mutant '$mutant_name' names no mutation to apply"
    run_mutant "$mutant_name" "$mutant_reassure" $mutant_muts
done

mutants_ran=0
mutants_missing=''
for m in $MUTANTS; do
    case " $MUTANTS_RUN " in
        *" $m "*) mutants_ran=$((mutants_ran + 1)) ;;
        *) mutants_missing="$mutants_missing $m" ;;
    esac
done
if [ "$MIN_MUTANTS" = all ]; then
    [ -z "$mutants_missing" ] ||
        die "declared but never run:$mutants_missing — $mutants_ran of $MUTANTS_DECLARED mutants ran, so the rows naming the rest passed with and without the guard they exist to test"
elif [ "$mutants_ran" -lt "$MIN_MUTANTS" ]; then
    die "$mutants_ran of $MUTANTS_DECLARED mutants ran, below the $MIN_MUTANTS required by AWAKENER_MATRIX_MIN_MUTANTS"
fi

note ''
note "check-wrapper-pin-matrix: $row_count rows, $fail_count failed"
note "  mutants: $mutants_ran of $MUTANTS_DECLARED declared ran (required: $MIN_MUTANTS)"
[ "$fail_count" -eq 0 ] || exit 1
exit 0
