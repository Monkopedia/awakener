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
# Written to run under dash and busybox sh as well as bash — and since #156 that sentence is a
# phase rather than an assertion. The bottom of this file re-runs the whole suite under each
# alternative shell on `ALT_ROSTER` and carries the child's three-state exit up unchanged.
# `AWAKENER_MATRIX_SHELLS=none` runs under the invoking shell only, and `AWAKENER_REQUIRE_SHELLS=1`
# — the flag CI already sets — turns an absent roster shell from a skip into a failure.
#
# Measured at `143318b`, before the phase existed: bash, dash and busybox sh each produced
# **byte-identical output**, 34 rows / 0 failed / 8 of 8 mutants, exit 0. So unlike the sibling —
# where the same sentence was false for busybox sh for as long as it had been written down (#138,
# #154: `link_tool` read busybox ash's bare `command -v` answer as an absence and died) — the
# claim here was true. It was still the same defect, because *true* and *unfalsifiable* are
# different properties and only the second one was established. Nothing in a run could have told
# the two apart, which is the whole complaint in #156.
#
# **Three exit statuses, because a suite that did not run is not a suite that passed** (#89,
# fixed in `test-summary-matrix.sh` by #129 and propagated here by #133):
#
#   0  every declared mutant ran and every row behaved
#   1  a row failed
#   2  the suite could not vouch — it never reached the state where a row verdict means anything
#
# `AWAKENER_MATRIX_MIN_MUTANTS` (default `all`) is how much of the counterfactual phase has to
# have executed for a 0; see the mutant table.
set -eu

SCRIPT=$1
WORK=$2
REPORT=$3

# Exit 2: the suite could not run, which is not the same fact as a row failing. See `die`.
[ -f "$SCRIPT" ] || { echo "upload-outcome-matrix: CANNOT VOUCH: no script at $SCRIPT" >&2; exit 2; }
SCRIPT=$(CDPATH= cd -P -- "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")

# This file's own path, absolutised now rather than when it is needed, because the alt-shell phase
# at the bottom re-runs this harness and `$0` is whatever the caller typed.
SELF=$(CDPATH= cd -P -- "$(dirname "$0")" && pwd)/$(basename "$0")

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

# Exit 2, not 1, and that is the whole of #89 in one line. `die` is reached when the suite could
# not get as far as testing what it claims to test — a mutant that will not build, an anchor that
# no longer exists, a guard naming nothing, a declared mutant that never ran. That is a different
# fact from "a row failed", which is exit 1, and until #133 both printed a red the caller could
# not tell apart. Three outcomes, and the caller can act on them without reading prose:
#
#   0  every declared mutant ran and every row behaved
#   1  a row failed
#   2  the suite could not vouch — it never reached the state where a row verdict means anything
die() {
    note "upload-outcome-matrix: CANNOT VOUCH: $1"
    exit 2
}

# ------------------------------------------------------------------ the mutant roster
#
# One record per mutant, and this table is also the invocation list — the loop at the bottom is
# the only route to `run_mutant`. That is #89's fix rather than a fourth check for it: before
# #133 this file held a bare `MUTANTS='…'` declaration and eight hand-maintained `run_mutant`
# lines beside it, so a name that was declared, named by rows, and missing its `run_mutant` line
# satisfied every check here — the guard-name check found it in `MUTANTS`, the roster check found
# it in `GUARDS_SEEN`, and the `run_count > before` check lives *inside* `run_mutant`, so it was
# never reached. The rows naming it then passed with and without the guard they exist to test,
# and the row count was the only trace.
#
# Deleting a record now deletes the declaration too, so the rows still naming that guard trip the
# guard-name check at the first row instead of going quietly green.
#
# Format, one record per line: `name|mutation [mutation …]`. Blank lines and lines whose first
# non-blank character is `#` are commentary, so an era mutant's justification lives beside it
# rather than in a comment block that can drift away from the entry it explains.
MUTANT_TABLE='
unexpected|unexpected
emptypair|emptypair
emptybranch|emptybranch
successarm|successarm
failarm|failarm
cancelarm|cancelarm
readboth|readboth

# The shape the step had when #99 was filed: only the literal string `failure` reddens, and the
# both-empty case notices whatever the build did. Every row tagged `pre99!` asserts it exits 0
# against this, which is the defect reproduced rather than described.
pre99|unexpected emptypair
'

# The one place that knows the record format. Everything else asks this.
mutant_rows() {
    printf '%s\n' "$MUTANT_TABLE" | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' || true
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
    # A duplicate would run its mutant twice and read in the log as extra coverage.
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

# How many of the declared mutants must actually have run before this suite's green is worth
# anything. `all` — the default, and the behaviour that would otherwise be hard-coded — means
# every one: a green then says the whole counterfactual phase executed, which is the only thing
# that makes the row verdicts evidence. An integer instead lets a deliberately partial run (a
# bisect, a single mutant under a debugger) still report, and it is the *number* that keeps that
# honest rather than a boolean, because "I meant to run a subset" and "the loop silently skipped
# one" are otherwise the same output. Anything short of the bar exits 2, never 0 and never 1.
# Same switch, same name and same default as the two sibling matrices, so one setting governs the
# whole family rather than three spellings of the same idea.
MIN_MUTANTS=${AWAKENER_MATRIX_MIN_MUTANTS:-all}
case $MIN_MUTANTS in
    all) ;;
    ''|*[!0-9]*)
        die "AWAKENER_MATRIX_MIN_MUTANTS must be 'all' or a non-negative integer, got '$MIN_MUTANTS'" ;;
esac

# Whether the alt-shell phase at the bottom runs. `auto` — the default, and the behaviour that
# would otherwise be hard-coded — re-runs this whole harness under each alternative shell on the
# roster. `none` runs it under the invoking shell only, and is what the re-runs themselves are
# handed, so the recursion terminates at depth one by a switch a reader can see rather than by an
# internal marker. Validated here rather than at the bottom, so a typo costs nothing instead of a
# full run. Same name, same values and same default as the sibling matrix, so one setting governs
# the family.
SHELLS_MODE=${AWAKENER_MATRIX_SHELLS:-auto}
case $SHELLS_MODE in
    auto|none) ;;
    *) die "AWAKENER_MATRIX_SHELLS must be 'auto' or 'none', got '$SHELLS_MODE'" ;;
esac

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

# Runs one record of MUTANT_TABLE. Never called by hand — see the loop below.
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
    # Belt to the roster check's braces: that one catches a guard naming no mutant, this one
    # catches a mutant whose rows all sat out for any other reason.
    [ "$run_count" -gt "$before" ] || die "mutant $MUTANT ran no rows at all"
    RUN_SCRIPT=$SCRIPT
    MUTANTS_RUN="$MUTANTS_RUN $MUTANT"
}

# The mutant phase is a loop over the table, and there is no other way to reach `run_mutant`. A
# mutant that is declared and not run is therefore unrepresentable rather than merely detected —
# deleting a record removes the mutant from `MUTANTS` too, and the rows still naming it then trip
# the guard-name check with a message that says so.
MODE=mutant
for mutant_name in $MUTANTS; do
    mutant_record=$(mutant_row_for "$mutant_name")
    [ -n "$mutant_record" ] || die "no MUTANT_TABLE record for '$mutant_name'"
    mutant_muts=${mutant_record#*|}
    [ -n "$mutant_muts" ] || die "mutant '$mutant_name' names no mutation to apply"
    run_mutant "$mutant_name" $mutant_muts
done

# And the belt to that: what was declared, confronted with what actually ran. The loop above makes
# the #89 hole unwritable; this makes it unreachable by any other route — an edit to the loop, an
# early `continue`, a filter someone adds later. It reports the pair of numbers rather than a
# boolean, because "I ran a subset on purpose" and "one silently sat out" are the same green
# otherwise. Exit 2: a suite that did not run its counterfactuals has not failed, it has abstained.
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

# ------------------------------------------------------------- the shells this claims to run under
#
# The header says "written to run under dash and busybox sh as well as bash", and until #156
# **nothing confronted that** — this file was only ever executed by the shell Gradle's `Exec`
# picks, which is one shell. #156's complaint is not that the sentence was false; measured at
# `143318b` it was true, all three shells producing byte-identical 34-row output. The complaint is
# that a run rendered identically whether it was true or not, which is the same instrument-shaped
# defect this suite exists to catch in `upload-outcome.sh` one level up, and the same one the
# header two paragraphs above is proud of resisting for its own guards.
#
# So the claim is now a phase. Each alternative shell on the roster re-runs this entire harness —
# every row, every mutant, the same `MIN_MUTANTS` bar — against the same script under test, in its
# own work directory, and its exit status is read with the same three-state meaning the rest of
# this file uses: 0 it held, 1 a row failed there, 2 it could not vouch there.
#
# **Transferred from `test-summary-matrix.sh` deliberately, not copied.** What came across is the
# roster, the recursion, the `AWAKENER_MATRIX_SHELLS=none` terminator, the `AWAKENER_REQUIRE_SHELLS`
# judgement and the separate alt-shell counter — each argued below. What did **not** come across is
# that file's `no_leak` assertion over the phase, because it has no counterpart here: this harness
# never writes a `GITHUB_STEP_SUMMARY`, and neither does the script under test, so there is no leak
# path for a child to open. Copying the call would have asserted a property of a file that does not
# exist, which reads like coverage and is not.
#
# **Whether a missing shell should fail is a judgement, and it takes the shape `cli/launcher-matrix.sh`
# and `test-summary-matrix.sh` already chose for exactly this question**: not by default, and yes on
# CI. kaladin has dash and busybox, adolin has neither, and dying by default would make the owner's
# other machine unable to run `./gradlew build` at all over a suite that is not what that build is
# about. `AWAKENER_REQUIRE_SHELLS=1` — the existing flag, the one CI already sets next to
# REQUIRE_SWAY and REQUIRE_SPANREED, and deliberately *not* a fourth spelling of the same idea —
# turns the skip into a `die`. Present or absent, the count is said out loud below, so what the run
# covered is a fact in the log rather than an inference from a row count.
ALT_ROSTER='dash|busybox sh'
ALT_TOTAL=0
ALT_PRESENT=0
ALT_MISSING=''
ALT_RAN=''
# Counted apart from `fail_count`, which counts *rows*. An alt shell is a whole suite, not a row,
# and folding one into the other would print `34 rows, 1 failed` when no row had failed and 34 was
# not the denominator that failure came out of. Both still exit 1; they are just reported as the
# two different facts they are.
ALT_FAILED=0

# Resolve one command name to an absolute path to a real executable file, or fail.
#
# This harness has no bare-PATH farm, so it does not have the sibling's `link_tool` caller — but
# the question this phase asks is the same one, and so is the trap. **busybox ash resolves its own
# applets before it walks PATH, and answers `command -v` with the bare applet name** — `busybox`,
# not `/usr/bin/busybox`, rc 0 even on an empty PATH, where dash correctly gives rc 127. Two
# consequences here, both of which this function removes: the log line below would name an applet
# namespace instead of the binary that answered, which is exactly the provenance the line exists
# for; and on a host where the roster shell is genuinely unreachable through PATH the phase would
# report it as present and then fail at exec for a reason with no relation to the claim. Under bash
# and dash the first branch always wins, so this is the behaviour those shells always had.
resolve_tool() {
    _rt=$(command -v "$1" 2>/dev/null) || _rt=''
    case $_rt in
        /*) printf '%s\n' "$_rt"; return 0 ;;
    esac
    _rt_oifs=$IFS
    IFS=:
    for _rt_dir in $PATH; do
        IFS=$_rt_oifs
        [ -n "$_rt_dir" ] || _rt_dir=.
        case $_rt_dir in /*) ;; *) _rt_dir=$(pwd)/$_rt_dir ;; esac
        if [ -f "$_rt_dir/$1" ] && [ -x "$_rt_dir/$1" ]; then
            printf '%s\n' "$_rt_dir/$1"
            return 0
        fi
        IFS=:
    done
    IFS=$_rt_oifs
    return 1
}

if [ "$SHELLS_MODE" != none ]; then
    note ""
    note "# the same suite again under every alternative shell the header claims"
    mkdir -p "$WORK/shells"
    OLDIFS=$IFS
    IFS='|'
    for alt in $ALT_ROSTER; do
        IFS=$OLDIFS
        ALT_TOTAL=$((ALT_TOTAL + 1))
        alt_label=$(printf '%s' "$alt" | tr ' ' '-')
        alt_bin=${alt%% *}
        case $alt in *' '*) alt_args=${alt#* } ;; *) alt_args='' ;; esac
        if ! alt_path=$(resolve_tool "$alt_bin"); then
            ALT_MISSING="$ALT_MISSING $alt_bin,"
            note "  shell/(absent) $alt — no $alt_bin on PATH"
            IFS='|'
            continue
        fi
        ALT_PRESENT=$((ALT_PRESENT + 1))
        set +e
        alt_out=$(AWAKENER_MATRIX_SHELLS=none \
            "$alt_path" $alt_args "$SELF" "$SCRIPT" \
            "$WORK/shells/$alt_label" "$WORK/shells/$alt_label.txt" 2>&1 </dev/null)
        alt_rc=$?
        set -e
        alt_tail=$(printf '%s\n' "$alt_out" | grep '^upload-outcome-matrix: ' | tail -1)
        case $alt_rc in
            0)
                ALT_RAN="$ALT_RAN $alt"
                note "  shell/$alt -> $alt_path $alt_args — ${alt_tail:-exit 0}"
                ;;
            1)
                # A row failed *there* and not here. That is a red, not a could-not-vouch, so it
                # exits 1 — but in its own counter, because it is one shell's whole suite rather
                # than one of this run's rows.
                ALT_FAILED=$((ALT_FAILED + 1))
                note "FAIL shell/$alt: the suite went red under $alt_path $alt_args — ${alt_tail:-exit 1}"
                detail "$alt_out"
                ;;
            *)
                # Anything else is the child saying it never reached a verdict, and a parent that
                # folded that into "a row failed" would be committing the defect this file is
                # about — the three statuses are the whole point of the design #133 propagated
                # here. Its word carries up unchanged.
                die "under $alt_path $alt_args the suite could not vouch (exit $alt_rc): ${alt_tail:-$alt_out}"
                ;;
        esac
        IFS='|'
    done
    IFS=$OLDIFS
    if [ -n "$ALT_MISSING" ]; then
        if [ "${AWAKENER_REQUIRE_SHELLS:-}" = 1 ]; then
            die "AWAKENER_REQUIRE_SHELLS=1 and${ALT_MISSING%,} is not installed. The header claims this harness runs under it, and a run that never tried cannot contradict that claim however green it is."
        fi
        note "  not installed:${ALT_MISSING%,} — the header's claim about it is untested on this host. Set AWAKENER_REQUIRE_SHELLS=1 to make that a failure instead."
    fi
fi

note ""
note "upload-outcome-matrix: $run_count rows, $fail_count failed"
# Printed on the green path too, and that is the point: the row count was the only trace #89 left
# behind, and nobody compares row counts between runs. This states the fact the reader needs —
# how much of the counterfactual phase actually happened — instead of leaving it to be inferred.
note "  mutants: $mutants_ran of $MUTANTS_DECLARED declared ran (required: $MIN_MUTANTS)"
if [ "$SHELLS_MODE" = none ]; then
    note "  shell coverage: this shell only (AWAKENER_MATRIX_SHELLS=none)"
else
    # `${ALT_FAILED:+…}` would be wrong here: it tests set-and-non-empty, and `0` is both.
    alt_red=''
    [ "$ALT_FAILED" -eq 0 ] || alt_red=" ($ALT_FAILED went red)"
    note "  shell coverage: the invoking shell plus $ALT_PRESENT of $ALT_TOTAL alternatives —${ALT_RAN:- none held}$alt_red"
fi
[ "$fail_count" -eq 0 ] || exit 1
[ "$ALT_FAILED" -eq 0 ] || exit 1
