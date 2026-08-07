#!/bin/sh
# `.github/scripts/upload-outcome.sh`'s own suite. Run by `gradlew uploadOutcomeMatrixTest`,
# which `check` depends on.
#
#   upload-outcome-matrix.sh <script-under-test> <work-dir> <report>
#
# #99 was found by running the `test-artifacts` step body by hand over thirteen inputs, and three
# of the thirteen — `''`, `skipped`, `typo-not-an-outcome` — exited 0 from a check whose entire
# job is to notice that the evidence for a run is missing. Running it by hand is exactly what
# nobody does again, so the thirteen inputs live here.
#
# Same two disciplines as `test-summary-matrix.sh`, for the same reason: every row names a mutant
# it must go red against, and each mutant is the committed script with one guard textually
# removed. A row that passes against the script with its guard taken out is testing nothing, and
# a guard no row names is a guard nobody checked. `pre99` restores the shape the step had when
# the issue was filed — only the literal string `failure` reddens — and the rows tagged `pre99!`
# assert it goes *green*, which is the defect reproduced rather than described.
#
# Written to run under dash and busybox sh as well as bash.
set -eu

SCRIPT=$1
WORK=$2
REPORT=$3

[ -f "$SCRIPT" ] || { echo "upload-outcome-matrix: no script at $SCRIPT" >&2; exit 1; }
SCRIPT=$(CDPATH= cd -P -- "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")

rm -rf "$WORK"
mkdir -p "$WORK"
WORK=$(CDPATH= cd -P -- "$WORK" && pwd)

case $REPORT in */*) mkdir -p "${REPORT%/*}" ;; esac
: >"$REPORT"

run_count=0
fail_count=0

note() {
    printf '%s\n' "$1" | tee -a "$REPORT"
}

detail() {
    printf '%s\n' "$1" | head -12 | sed 's/^/      | /' | tee -a "$REPORT" >/dev/null
}

die() {
    note "upload-outcome-matrix: $1"
    exit 1
}

MUTANTS='unexpected emptypair emptybranch successarm failarm cancelarm readboth pre99'
GUARDS_SEEN=''
MODE=real
MUTANT=''
RUN_SCRIPT=$SCRIPT

# check <label> <guards> <build> <xml> <html> <assertion…>
#
#   guards     mutants this row must go red against. A trailing `!` additionally requires the
#              mutant to exit **0** on this row — "it went green here" being the actual #99
#              defect, where the general form is only "something changed".
#   assertion  `+text` must appear, `-text` must not, `=N` exit status (default 0).
check() {
    label=$1; guards=$2; b=$3; x=$4; h=$5
    shift 5

    if [ "$MODE" = mutant ]; then
        case " $guards " in
            *" $MUTANT "* | *" $MUTANT! "*) ;;
            *) return 0 ;;
        esac
    else
        [ -n "$guards" ] || die "check: row $label names no guard"
        for g in $guards; do
            g=${g%!}
            case " $MUTANTS " in
                *" $g "*) ;;
                *) die "check: row $label names guard '$g', which is not a mutant" ;;
            esac
            case " $GUARDS_SEEN " in
                *" $g "*) ;;
                *) GUARDS_SEEN="$GUARDS_SEEN $g" ;;
            esac
        done
    fi

    run_count=$((run_count + 1))
    set +e
    got=$(BUILD="$b" XML="$x" HTML="$h" "$RUN_SCRIPT" 2>&1 </dev/null)
    rc=$?
    set -e

    why=''
    want_exit=0
    for a in "$@"; do
        case $a in =*) want_exit=${a#=} ;; esac
    done
    [ "$rc" = "$want_exit" ] || why="exit $rc, wanted $want_exit"

    for a in "$@"; do
        [ -z "$why" ] || break
        case $a in
            =*) ;;
            +*)
                case $got in
                    *"${a#+}"*) ;;
                    *) why="output lacks '${a#+}'" ;;
                esac
                ;;
            -*)
                case $got in
                    *"${a#-}"*) why="output contains '${a#-}' and must not" ;;
                esac
                ;;
            *) die "check: malformed assertion '$a' in row $label" ;;
        esac
    done

    if [ "$MODE" = mutant ]; then
        if [ -z "$why" ]; then
            note "FAIL $label [$MUTANT]: the guard was removed and the row still passed, so it does not test it"
            detail "$got"
            fail_count=$((fail_count + 1))
            return 0
        fi
        case " $guards " in
            *" $MUTANT! "*)
                if [ "$rc" != 0 ]; then
                    note "FAIL $label [$MUTANT]: went red, but not by exiting 0 — the regression this row claims is that the check passes, not that it differs"
                    detail "$got"
                    fail_count=$((fail_count + 1))
                    return 0
                fi
                ;;
        esac
        note "ok   $label [$MUTANT] red as required: $why"
        return 0
    fi

    if [ -n "$why" ]; then
        note "FAIL $label: $why"
        detail "$got"
        fail_count=$((fail_count + 1))
    else
        note "ok   $label"
    fi
}

# ------------------------------------------------------------------ the cases
#
# The thirteen inputs from #99 plus the build-result axis, which the issue could not settle by
# hand: no run in history has `build: failure` alongside a `test-artifacts` job.
cases() {
    # The ordinary green. Also the row that stops "always red" passing the whole matrix.
    check both-uploaded 'successarm' success success success \
        '+JUnit XML upload: success' \
        '-::error'

    check xml-upload-failed 'failarm' success failure success \
        '+::error title=JUnit XML not uploaded' '=1'

    check html-upload-failed 'failarm' success success failure \
        '+::error title=HTML report not uploaded' '=1'

    # #99's measurement. Every one of these exited 0 before, from a check that is the only place
    # an upload failure is legible at all.
    check xml-outcome-empty 'unexpected! pre99!' success '' success \
        '+::error title=Upload outcome unrecognised' '=1'

    check xml-outcome-skipped 'unexpected! pre99!' success skipped success \
        '+::error title=Upload outcome unrecognised' '=1'

    check xml-outcome-typo 'unexpected! pre99!' success typo-not-an-outcome success \
        '+::error title=Upload outcome unrecognised' '=1'

    check html-outcome-empty 'unexpected! pre99!' success success '' \
        '+::error title=Upload outcome unrecognised' '=1'

    # The step id renamed on both, under a green build: the shape that goes green forever.
    check both-outcomes-empty-green-build 'emptypair! emptybranch pre99!' success '' '' \
        '+::error title=No upload outcome' '=1'

    # The same absence under a build that did not succeed — a lost runner, or a failure before the
    # steps were reached. The build's own red is the signal; asserting a wiring fault from here
    # would be a claim this cannot see.
    check both-outcomes-empty-red-build 'emptybranch' failure '' '' \
        '+::notice title=No upload outcome' '-::error'

    # A run stopped mid-upload. `!cancelled()` normally skips this job entirely, so this is the
    # remainder: no verdict, and not a fault.
    check upload-cancelled 'cancelarm successarm' success cancelled success \
        '+::notice title=JUnit XML upload cancelled' '-::error'

    # A red build with the evidence intact has to stay green here, or the two checks stop being
    # separate facts — which is the whole reason this job exists.
    check red-build-evidence-intact 'successarm' failure success success \
        '-::error'

    check red-build-and-upload-failed 'failarm readboth' failure failure success \
        '+::error title=JUnit XML not uploaded' \
        '+::notice title=Read both checks' '=1'

    # BUILD itself unset, which is what a `needs` expression yields if the job name changes.
    check build-result-missing 'successarm' '' success success \
        '+build job: unknown' '-::error'
}

note "upload-outcome-matrix against $SCRIPT"
note ""
note "# every row against the committed script"
cases

for m in $MUTANTS; do
    case " $GUARDS_SEEN " in
        *" $m "*) ;;
        *) die "mutant '$m' is named by no row, so its phase asserts nothing" ;;
    esac
done

# ------------------------------------------------------------------ mutants
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
    grep -Fq -- "$2" "$1" && die "mutant anchor survived the splice in $1: $2"
    return 0
}

apply() {
    case $2 in
        unexpected)
            splice "$WORK/$1.sh" '    *) unexpected "$1" "${2:-}"; return 1 ;;' \
                '    *) return 0 ;;'
            ;;
        emptypair)
            splice "$WORK/$1.sh" '  if [ "$BUILD" = success ]; then' '  if [ 1 -eq 2 ]; then'
            ;;
        emptybranch)
            splice "$WORK/$1.sh" 'if [ -z "$XML" ] && [ -z "$HTML" ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        # The three arms of the classifier, taken off one at a time. Removing an arm sends its
        # value to the catch-all, so each of these is "this outcome is no longer recognised as
        # what it is" rather than "the script stopped working" — which is what makes a row that
        # survives it a row that was not reading that arm.
        successarm)
            splice "$WORK/$1.sh" '    success)' '    notsuccess)'
            ;;
        failarm)
            splice "$WORK/$1.sh" '    failure)' '    notfailure)'
            ;;
        cancelarm)
            splice "$WORK/$1.sh" '    cancelled)' '    notcancelled)'
            ;;
        readboth)
            splice "$WORK/$1.sh" 'if [ "$rc" = "1" ] && [ "$BUILD" != "success" ]; then' \
                'if [ 1 -eq 2 ]; then'
            ;;
        *) die "apply: unknown mutation '$2'" ;;
    esac
}

run_mutant() {
    MUTANT=$1
    shift
    cp "$SCRIPT" "$WORK/$MUTANT.sh"
    chmod +x "$WORK/$MUTANT.sh"
    for m in "$@"; do apply "$MUTANT" "$m"; done
    bash -n "$WORK/$MUTANT.sh" || die "mutant $MUTANT does not parse"

    # A mutant that cannot run goes red on every row for a reason that has nothing to do with the
    # guard, and "red" is the whole verdict this phase reads. The probe cannot assert a green,
    # because half these mutants are meant to change what a green is — so it asserts the weaker
    # thing that still separates the two: the script ran to a verdict of its own rather than dying
    # on a syntax error or a lost execute bit.
    set +e
    BUILD=success XML=success HTML=success "$WORK/$MUTANT.sh" >"$WORK/$MUTANT.probe" 2>&1 </dev/null
    probe_rc=$?
    set -e
    case $probe_rc in
        0 | 1) ;;
        *) die "mutant $MUTANT exits $probe_rc, so every row it reddens would be red for the wrong reason: $(head -3 "$WORK/$MUTANT.probe")" ;;
    esac
    grep -q 'This check reports one thing' "$WORK/$MUTANT.probe" ||
        die "mutant $MUTANT produced no report at all"

    RUN_SCRIPT=$WORK/$MUTANT.sh
    note ""
    note "# counterfactual: the rows that name '$MUTANT' must go red without it"
    before=$run_count
    cases
    [ "$run_count" -gt "$before" ] || die "mutant $MUTANT ran no rows at all"
    RUN_SCRIPT=$SCRIPT
}

MODE=mutant
run_mutant unexpected  unexpected
run_mutant emptypair   emptypair
run_mutant emptybranch emptybranch
run_mutant successarm  successarm
run_mutant failarm     failarm
run_mutant cancelarm   cancelarm
run_mutant readboth    readboth
# The shape the step had when #99 was filed: only the literal string `failure` reddens, and the
# both-empty case notices whatever the build did.
run_mutant pre99 unexpected emptypair

note ""
note "upload-outcome-matrix: $run_count rows, $fail_count failed"
[ "$fail_count" -eq 0 ] || exit 1
