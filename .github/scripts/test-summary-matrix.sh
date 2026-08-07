#!/bin/sh
# The test-summary script's own suite. Run by `gradlew summaryMatrixTest`, which `check`
# depends on.
#
#   test-summary-matrix.sh <script-under-test> <work-dir> <report>
#
# `.github/scripts/test-summary.sh` writes the one sentence CLAUDE.md tells every agent to
# lean on — "skipped is zero, so every tool-gated test executed rather than opting out" — and
# its first version printed that sentence from a *failed* measurement along three separate
# paths. Two review rounds have now found two defects in it by hand, and the failure mode is
# silent by construction: a wrong reassurance renders identically to a right one. So the
# instrument that tells agents their verification was real is the last thing that should be
# exempt from being verified itself (#84).
#
# Three properties make this a suite rather than a demonstration:
#
#  1. **Every case has a counterfactual.** A case that passes against the current script and
#     also against a script without the fix proves nothing. So each case names one or more
#     *mutants* — the current script with one guard textually removed — and the second phase
#     asserts the case goes **red** against each of them. A guard whose removal breaks nothing
#     is not being tested; a case that survives its own guard's removal is not testing it. The
#     `pre76` mutant reverts all four of the #76-era guards at once, reproducing the shape the
#     script had when the defect was live, and the cases tagged `pre76!` additionally assert
#     that it prints the reassurance — which is the exact regression, not a proxy for it.
#
#  2. **Every fixture that mocks a PATH binary leaves a marker.** #76's author had a case that
#     shadowed `awk` with a file that was not executable; bash skipped it during the PATH walk,
#     found the real `awk`, and the case reported a pass having tested nothing. Each stub here
#     touches a file in the marker directory before doing anything else, the directory is
#     emptied before every run, and a case whose stub left no marker fails — whatever else the
#     output said. That generalises: it catches an unexecutable stub, a stub behind another
#     PATH entry, and a stub that was never reached at all.
#
#  3. **It runs under more than one `awk`.** The script's parser is a hand-rolled awk program
#     that must stay mawk-compatible, because mawk is `/usr/bin/awk` on ubuntu-latest. The
#     roster below is tried in full and every implementation found runs the whole case list;
#     the run fails if fewer than two *distinct* binaries answered, so "portable" is never
#     asserted from a single implementation. An entry resolving to a binary already on the
#     roster is dropped rather than re-run, so the row count is coverage rather than repetition.
#     On CI that is mawk plus gawk (the workflow installs it for this reason) plus busybox awk;
#     on kaladin it is gawk plus busybox awk.
#
# And the question none of those three was asking, which is how the leak below survived five
# rounds of controls: **what does this suite write, where does it go, and who reads it?** See
# the canary a few lines down.
#
# Since #97 the script also *gates*, on a committed per-module floor, and that half is covered
# the same way: one floor file per fixture at `$WORK/fx/<name>.floors` (and an optional
# `<name>.modules` standing in for the build's roster), handed to the script through
# `AWAKENER_TEST_FLOORS`. Per-fixture floors are what let a row say "this module reported less
# than it should have" without every other row having to agree about what the real modules are —
# and the price is that the *default* location, the only one CI uses, is then exercised by no
# row at all. `check_default_resolution` below pays it back explicitly.
#
# Written to run under dash and busybox sh as well as bash, so the harness cannot depend on
# the shell whose behaviour it is testing around.
set -eu

SCRIPT=$1
WORK=$2
REPORT=$3

[ -f "$SCRIPT" ] || { echo "test-summary-matrix: no script at $SCRIPT" >&2; exit 1; }
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
    printf '%s\n' "$1" | head -20 | sed 's/^/      | /' >>"$REPORT"
    printf '%s\n' "$1" | head -20 | sed 's/^/      | /'
}

die() {
    note "test-summary-matrix: $1"
    exit 1
}

MARKERS=$WORK/markers
mkdir -p "$MARKERS"

# ------------------------------------------------------------- what this suite writes, where
#
# The script under test writes to two places: stdout, and — whenever `GITHUB_STEP_SUMMARY` is
# set — the file that variable names. `check()` runs every row with the variable stripped or
# redirected, so rows were never the problem. The mutant-validity probe six lines from that
# code was, because it inherited the environment: run under Gradle's `Exec` inside the `Build
# and test` step, where GitHub sets the variable, the ten probes appended their full reports to
# the **live** run summary. Eight of the ten print the reassurance on the clean fixture, so the
# suite written to stop the script reassuring from nothing emitted eight false reassurances per
# run, into the artefact CLAUDE.md points agents at — ahead of the real one, since `check` runs
# before `Summarise test counts`. Measured on this branch's head: 2840 bytes, eight verbatim
# copies, over a table reading `| wm | 2 | 101 | 0 | 0 | 0 |`.
#
# None of the five controls in this file was asking "what does this suite write, and where does
# it go" — the probe's stdout was captured, so it was invisible in the matrix log, in the report
# file, and in the CI step log. It surfaced only in the rendered summary. So the fix is not the
# missing `env -u`; it is that the variable is repointed at a canary here, once, before anything
# runs, and asserted empty at the end. The real path becomes unreachable through the
# environment, and any future invocation that forgets to strip it writes somewhere that fails
# the suite loudly instead of somewhere nobody looks.
#
# The canary fires identically on kaladin and on CI, which the direct check below cannot: a
# guard that can only fail on the runner is a guard nobody exercises while writing the code.
AMBIENT_SUMMARY=${GITHUB_STEP_SUMMARY:-}
AMBIENT_SUMMARY_SIZE=0
if [ -n "$AMBIENT_SUMMARY" ] && [ -f "$AMBIENT_SUMMARY" ]; then
    AMBIENT_SUMMARY_SIZE=$(wc -c <"$AMBIENT_SUMMARY")
fi
CANARY=$WORK/step-summary-canary.md
: >"$CANARY"
GITHUB_STEP_SUMMARY=$CANARY
export GITHUB_STEP_SUMMARY

# Called after every row and every probe, so a leak names the thing that leaked rather than
# being discovered at the end with nothing to attribute it to.
no_leak() {
    if [ -s "$CANARY" ]; then
        note "test-summary-matrix: $1 wrote $(wc -c <"$CANARY") bytes to the inherited"
        note "  GITHUB_STEP_SUMMARY. Every invocation must strip it (env -u) or redirect it to a"
        note "  fixture path; nothing here may write to the caller's run summary. Leaked:"
        detail "$(head -12 "$CANARY")"
        exit 1
    fi
    if [ -n "$AMBIENT_SUMMARY" ] && [ -f "$AMBIENT_SUMMARY" ]; then
        if [ "$(wc -c <"$AMBIENT_SUMMARY")" != "$AMBIENT_SUMMARY_SIZE" ]; then
            note "test-summary-matrix: $1 changed the real step summary at $AMBIENT_SUMMARY"
            exit 1
        fi
    fi
}

# ------------------------------------------------------------------ the reference sentences
#
# Written out once, because a case that asserts the absence of a *slightly different* sentence
# to the one the script prints asserts nothing at all. Anything that changes these strings in
# the script has to change them here, which is the point.
REASSURE='`skipped` is zero, so every tool-gated test executed rather than opting out.'
SKIPS='`skipped` is not zero.'
UNMEASURED='**These counts were not measured.**'
NOTESTS='**No tests were counted**'
LOWER='could not be read.** The counts above are a lower bound'
NOFILES='No JUnit XML found under'
BELOWFLOOR='**Coverage is below the committed floor**'
NORESULTS='produced no test results at all; the floor says'
UNDECLARED='is not declared in the floor file'
BADFLOORS='**The test floor file could not be used**'

# ------------------------------------------------------------------ fixtures
#
# One directory per case, laid out the way Gradle lays results out, because the script's glob
# is `*/build/test-results/*/*.xml` and a fixture that flattens it would exercise a path
# nothing produces.
suitedir() {
    mkdir -p "$WORK/fx/$1/$2/build/test-results/jvmTest"
    printf '%s' "$WORK/fx/$1/$2/build/test-results/jvmTest"
}

fx() {
    mkdir -p "$WORK/fx/$1"
    printf '%s' "$WORK/fx/$1"
}

# The floor file for one fixture, and — where a row needs one — the module roster the build would
# have handed the script. `check` picks both up by fixture name, so a row cannot forget to point
# the script at its own floors and silently read someone else's. A fixture with *no* floors file
# is the "the floor file is missing" case, and it is written as an absence on purpose: that is
# the shape the failure has in the tree.
floors() {
    name=$1
    shift
    : >"$WORK/fx/$name.floors"
    for entry in "$@"; do
        printf '%s\n' "$entry" >>"$WORK/fx/$name.floors"
    done
}

modules_of() {
    printf '%s' "$2" >"$WORK/fx/$1.modules"
}

# A single well-formed suite, the shape Gradle writes: one `<testsuite>` per class, on one
# line, attributes in Gradle's order.
suite() {
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        printf '<testsuite name="%s" tests="%s" skipped="%s" failures="%s" errors="%s" timestamp="2026-08-05T00:00:00" hostname="kaladin" time="1.5">\n' \
            "$2" "$3" "$4" "$5" "$6"
        echo '  <properties/>'
        printf '  <testcase name="one" classname="%s" time="0.1"/>\n' "$2"
        echo '</testsuite>'
    } >"$1"
}

# clean: two files in one module, nothing skipped. The positive case, and the one that stops
# "always report it as unmeasured" from passing the whole matrix.
d=$(suitedir clean wm)
suite "$d/TEST-a.xml" AlphaTest 60 0 0 0
suite "$d/TEST-b.xml" BetaTest 41 0 0 0
floors clean 'wm 101'

# multi: three modules, so the per-module rows and the sum over them are both asserted.
d=$(suitedir multi wm);       suite "$d/TEST-a.xml" WmTest 101 0 0 0
d=$(suitedir multi registry); suite "$d/TEST-a.xml" RegTest 78 0 0 0
d=$(suitedir multi cli);      suite "$d/TEST-a.xml" CliTest 55 0 0 0
floors multi '# three modules, all of them at their floor' 'cli 55' 'registry 78' 'wm 101'

# skips: a tool-gated test opted out. The alarm, not the reassurance.
d=$(suitedir skips wm)
suite "$d/TEST-a.xml" GatedTest 101 3 0 0
floors skips 'wm 101'

# failures: a red suite with nothing skipped. `skipped=0` is still true and still worth
# saying — the reassurance is about opting out, not about passing — so this pins that the
# script does not conflate the two.
d=$(suitedir failures wm)
suite "$d/TEST-a.xml" BrokenTest 101 0 2 1
floors failures 'wm 101'

# multiline: the `<testsuite …>` tag split across lines. Still one tag; a per-line regex reads
# it as zero suites, which was the first of #76's three paths.
d=$(suitedir multiline wm)
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuite name="WrappedTest" tests="101"'
    echo '           skipped="0" failures="0" errors="0">'
    echo '</testsuite>'
} >"$d/TEST-a.xml"
floors multiline 'wm 101'

# truncated: a hard-killed test worker's output. Nothing readable in the only file there is.
d=$(suitedir truncated wm)
printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="KilledTest" tests="91" skipp' \
    >"$d/TEST-a.xml"
# The floor is deliberately one this fixture cannot meet. A failed parse has no counts to compare,
# so the floor must *not* fire here — reporting a shortfall from a measurement that did not happen
# would be the same class of claim the script exists to refuse.
floors truncated 'wm 91'

# zerotests: a suite element that reports nothing ran. Parsed fine; says nothing.
d=$(suitedir zerotests wm)
suite "$d/TEST-a.xml" EmptyTest 0 0 0 0
floors zerotests 'wm 0'

# partial: #83. One good file and one truncated one — the good file parses, the truncated one
# contributes zero to every column including `skipped`, and the total renders as a total.
d=$(suitedir partial wm)
suite "$d/TEST-a.xml" GoodTest 10 0 0 0
printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="KilledTest" tests="91" skipp' \
    >"$d/TEST-b.xml"
# Met by the part that *did* read, so the row is about the shortfall message and not about the
# floor. A lower bound above the floor establishes nothing about the floor either way, and the
# script says so by leaving the reassurance withheld for #83's reason rather than this one.
floors partial 'wm 10'

# partialskips: the two alarms are independent facts and both have to be said.
d=$(suitedir partialskips wm)
suite "$d/TEST-a.xml" GoodTest 10 2 0 0
printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="KilledTest" tests="91" skipp' \
    >"$d/TEST-b.xml"
floors partialskips 'wm 10'

# twosuites: two `<testsuite>` elements in one file. Both counted.
d=$(suitedir twosuites wm)
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuite name="OneTest" tests="9" skipped="0" failures="0" errors="0"></testsuite>'
    echo '<testsuite name="TwoTest" tests="6" skipped="0" failures="0" errors="0"></testsuite>'
} >"$d/TEST-a.xml"
floors twosuites 'wm 15'

# twosuitespartial: the discriminator for *how* the read-file count is kept. Two suites in one
# file plus one file that yields none. Counted per suite, the tally is 2 against 2 files
# matched and the shortfall vanishes; counted per file it is 1 against 2 and the shortfall is
# reported. Only the second is right, and only this fixture tells them apart.
d=$(suitedir twosuitespartial wm)
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuite name="OneTest" tests="9" skipped="0" failures="0" errors="0"></testsuite>'
    echo '<testsuite name="TwoTest" tests="6" skipped="0" failures="0" errors="0"></testsuite>'
} >"$d/TEST-a.xml"
printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="KilledTest" tests="91" skipp' \
    >"$d/TEST-b.xml"
floors twosuitespartial 'wm 15'

# wrapper: a `<testsuites>` root element around the real one, which some JUnit writers emit and
# whose own `tests=` attribute is a total, not another suite. Double-counting it would inflate
# every column; the trailing space in the match is what prevents that, and nothing else says so.
d=$(suitedir wrapper wm)
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuites name="all" tests="999" skipped="999" failures="999" errors="999">'
    echo '<testsuite name="RealTest" tests="7" skipped="0" failures="0" errors="0">'
    echo '  <testcase name="a" classname="RealTest"><skipped/></testcase>'
    echo '</testsuite>'
    echo '</testsuites>'
} >"$d/TEST-a.xml"
floors wrapper 'wm 7'

# empty: the glob matches nothing at all. The floor is what turns that from a warning nobody has
# to act on into a red: "no module produced anything" is the strongest form of #97, not a milder
# one, and before the floor existed it was the only form the script exited 0 on.
mkdir -p "$(fx empty)"
floors empty 'wm 101'

# ---------------------------------------------------------------- #97: what should have run
#
# Everything above this line asks whether the script read what was in front of it. These ask
# whether what was in front of it was all of it — the question no control in the build, the
# workflow or this script was asking when one line in `wm/build.gradle.kts` took the total from
# 279 to 178 under `BUILD SUCCESSFUL`, `skipped="0"` and the reassurance printed in full.

# missingmodule: the measurement in #97, at fixture scale. `cli` reported; `wm` did not exist at
# all. Every column that is present is correct, every warning stays quiet, and the only trace is
# a smaller number than the tree says to expect.
d=$(suitedir missingmodule cli); suite "$d/TEST-a.xml" CliTest 55 0 0 0
floors missingmodule 'cli 55' 'wm 101'

# belowfloor: the same failure part-way. A module that ran one test out of a hundred is not
# distinguishable from a module that ran a hundred by anything except the count.
d=$(suitedir belowfloor wm); suite "$d/TEST-a.xml" WmTest 100 0 0 0
floors belowfloor 'wm 101'

# undeclared: results from a module the floor file says nothing about. Its tests are counted into
# the total, and nothing constrains them — so the total looks bigger while the guarantee gets
# smaller. This is what a new module looks like before anyone states what it should run.
d=$(suitedir undeclared wm);  suite "$d/TEST-a.xml" WmTest 101 0 0 0
d=$(suitedir undeclared cli); suite "$d/TEST-a.xml" CliTest 55 0 0 0
floors undeclared 'wm 101'

# nofloors: the comparison's own input, missing. Written as an absent file rather than an empty
# one because deleting it is the cheapest way to make every floor stop applying, and a fallback
# to "report only what was found" would make that deletion silent.
d=$(suitedir nofloors wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0

# badfloors: a line that does not parse. Skipping it would drop exactly one module's floor and
# leave the file looking like a floor file.
d=$(suitedir badfloors wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors badfloors 'wm one hundred and one'

# dupfloors: the same module twice. Last-one-wins would let a second line quietly lower the first.
d=$(suitedir dupfloors wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors dupfloors 'wm 101' 'wm 3'

# rostergap: the build has a module the floor file has never heard of. That module need not have
# produced anything for this to fire, which is the point — a module whose tests were never wired
# to `check` produces nothing and is invisible to every per-module comparison.
d=$(suitedir rostergap wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors rostergap 'wm 101'
modules_of rostergap 'wm,registry'

# rosterextra: the other direction. A floor for a module that is no longer in the build reads as
# coverage that is being enforced and is not.
d=$(suitedir rosterextra wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors rosterextra 'wm 101' 'bus 0'
modules_of rosterextra 'wm'

# rosterok: the roster agreeing with the floor file has to still print the reassurance, or
# "always complain about the roster" would pass every row above.
d=$(suitedir rosterok wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors rosterok 'wm 101'
modules_of rosterok 'wm'

# settingsgap / settingsok: the same two directions with no roster handed in — read out of
# `settings.gradle.kts`, which is what the CI step gets and what makes the property survive the
# Gradle task that would otherwise be its only source being disabled. Written in the file's real
# shape, comments and all, because a parse that only handles the tidy form is a parse that stops
# working on the day someone tidies differently.
d=$(suitedir settingsgap wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors settingsgap 'wm 101'
cat >"$WORK/fx/settingsgap/settings.gradle.kts" <<'EOF'
rootProject.name = "awakener"

include(":registry")
include(":wm")

// Owns the command-line entry points.
EOF

d=$(suitedir settingsok wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors settingsok 'wm 101'
printf 'rootProject.name = "awakener"\ninclude(":wm")\n' \
    >"$WORK/fx/settingsok/settings.gradle.kts"

# driftup: a module that has grown past its floor. Green, and said out loud, because a floor
# everything has grown past is exactly the state in which it stops describing anything and starts
# being a number nobody has read since it was written.
d=$(suitedir driftup wm); suite "$d/TEST-a.xml" WmTest 101 0 0 0
floors driftup 'wm 90'

# ------------------------------------------------------------------ PATH construction
#
# Built rather than inherited, so "no awk anywhere" means it and so the suite is a standing
# assertion about which utilities the script is allowed to shell out to: add one and this list
# has to grow or the matrix goes red. `bash` is on it because the script's shebang is
# `#!/usr/bin/env bash`, and `env` resolves that name through PATH.
BARE=$WORK/bin
mkdir -p "$BARE"
link_tool() {
    resolved=$(command -v "$1" 2>/dev/null) || return 1
    case $resolved in
        /*) ln -sf "$resolved" "$BARE/$1" ;;
        *) return 1 ;;
    esac
}
for tool in bash grep sort head cat tee; do
    link_tool "$tool" || die "no $tool available to build the harness with"
done

# The fixture-validity check for every `noawk` case below. Without it "the script reported no
# measurement" would be satisfied just as well by a PATH so bare that nothing worked, and the
# case would stop being about awk.
if PATH=$BARE command -v awk >/dev/null 2>&1; then
    die "the bare PATH still resolves awk; the no-awk cases would test nothing"
fi
PATH=$BARE command -v grep >/dev/null 2>&1 ||
    die "the bare PATH lost grep; the no-awk cases would fail for the wrong reason"

# ------------------------------------------------------------------ awk stubs and shims
#
# Every one of these touches its marker first. A stub that is shadowed, unexecutable, or simply
# never reached leaves no marker and its case fails, whatever the script printed.
mkstub() {
    stub_name=$1
    shift
    mkdir -p "$WORK/path/$stub_name"
    {
        echo '#!/bin/sh'
        printf ': > "%s/%s"\n' "$MARKERS" "$stub_name"
        printf '%s\n' "$@"
    } >"$WORK/path/$stub_name/awk"
    chmod +x "$WORK/path/$stub_name/awk"
    # The marker exists to catch a stub that never ran; this catches the one shape of that
    # which the marker cannot report, because a stub bash refuses to exec leaves no trace and
    # is not reached to be blamed. #76's own case 8 was exactly this.
    [ -x "$WORK/path/$stub_name/awk" ] || die "stub $stub_name is not executable"
}

# An awk that is present and broken — the third of #76's three paths.
mkstub broken 'echo "awk: fatal: this stub fails on purpose" >&2' 'exit 1'
# An awk that succeeds and answers with something that is not a number. This is the one case
# where the integer normaliser is the only thing standing between the script and the
# reassurance: the exit status is 0 and the TOTAL line is present, so every other guard is
# satisfied and only `num` notices.
mkstub garbage 'printf "TOTAL - - - - - -\n"' 'exit 0'

# The roster. Every entry that resolves runs the whole case list; the count of *distinct*
# binaries is asserted below, so an entry going missing cannot quietly shrink the coverage
# without saying so.
IMPLS=''
IMPL_COUNT=0
DISTINCT=''
DISTINCT_COUNT=0

# add_impl <label> <command> [applet]
add_impl() {
    label=$1
    cmd=$2
    extra=${3:-}
    if ! resolved=$(command -v "$cmd" 2>/dev/null); then
        note "  awk/(absent) $label — no $cmd on PATH"
        return 0
    fi
    case $resolved in
        /*) ;;
        *) note "  awk/(absent) $label — $cmd is not a file"; return 0 ;;
    esac
    # Present is not the same as usable: `busybox` is on PATH on any host that has it, and
    # whether that build carries the awk applet is a per-distribution decision. Ask it to be an
    # awk before believing it is one, and say so in the roster either way — an entry dropped
    # silently is a coverage claim nobody made and nobody can check.
    if [ "$("$resolved" $extra 'BEGIN { print "probe" }' 2>/dev/null)" != probe ]; then
        note "  awk/(present, not an awk) $label — $resolved $extra answered no probe"
        return 0
    fi
    real=$(readlink -f "$resolved" 2>/dev/null || printf '%s' "$resolved")
    # Running the same binary twice under two names buys nothing and overstates the breadth:
    # `ambient` is a symlink to `gawk` on kaladin, so the roster read as three entries while
    # covering two implementations, and 17 of the rows were a re-run. `ambient` is registered
    # first and so is never the entry dropped, which keeps the one the workflow step will
    # actually use in the report. Row count now tracks distinct coverage.
    case " $DISTINCT " in
        *" $real "*)
            note "  awk/(duplicate) $label -> $resolved $extra is $real, already on the roster"
            return 0
            ;;
    esac
    mkdir -p "$WORK/path/awk-$label"
    {
        echo '#!/bin/sh'
        printf ': > "%s/awk-%s"\n' "$MARKERS" "$label"
        printf 'exec "%s" %s "$@"\n' "$resolved" "$extra"
    } >"$WORK/path/awk-$label/awk"
    chmod +x "$WORK/path/awk-$label/awk"
    IMPLS="$IMPLS $label"
    IMPL_COUNT=$((IMPL_COUNT + 1))
    DISTINCT="$DISTINCT $real"
    DISTINCT_COUNT=$((DISTINCT_COUNT + 1))
    version=$("$resolved" $extra --version 2>/dev/null | head -1) || version=''
    [ -n "$version" ] || version=$("$resolved" $extra -W version 2>/dev/null | head -1) ||
        version=''
    note "  awk/$label -> $resolved $extra ($real)${version:+ — $version}"
}

note "test-summary-matrix against $SCRIPT"
note "  awk implementations:"
# `ambient` is whatever `awk` resolves to, which is what the workflow step itself will use. The
# rest are named, and naming them is the point: coverage that depends on where the `awk`
# alternative happens to point changes silently when a package is installed, and coverage of
# mawk in particular is what the parser's constraints are written against.
add_impl ambient awk
add_impl gawk gawk
add_impl mawk mawk
add_impl original original-awk
add_impl busybox busybox awk
note "  $IMPL_COUNT roster entries, $DISTINCT_COUNT distinct binaries:$DISTINCT"

[ "$IMPL_COUNT" -gt 0 ] || die "no awk at all; the parser cannot be exercised"
if [ "$DISTINCT_COUNT" -lt 2 ]; then
    die "only $DISTINCT_COUNT distinct awk binary available ($DISTINCT). Half of what this suite exists to establish is that the parser is portable across the awks it actually meets — mawk on the runner, gawk and busybox awk on kaladin — and one implementation cannot establish that. Install a second (gawk, mawk or busybox) rather than lowering this floor."
fi

# ------------------------------------------------------------------ the case runner
#
# check <label> <guards> <fixture> <pathmode> <envmode> <assertion…>
#
#   guards    space-separated mutant names this case must go red against. A trailing `!` on a
#             name additionally requires that mutant to print the reassurance sentence, which
#             is how a case says "this is the regression itself" rather than "something
#             changed". A case with no guard would be one nothing could break, so there is no
#             way to write one.
#   pathmode  `impl` (the implementation under test, asserted to have been called), `unused`
#             (the same, asserted *not* to have been called), `noawk`, `broken`, `garbage`.
#   envmode   `plain` or `summary` (sets GITHUB_STEP_SUMMARY and asserts it was written).
#   assertion `+text` must appear, `-text` must not, `=N` exit status (default 0).
#
# Marker assertions are added by pathmode rather than written per case, because a case that
# forgets one is exactly the case that silently tests nothing.
MODE=real
MUTANT=''
MUTANT_REASSURE=no
IMPL=''

# Every mutant this file knows how to build. A guard naming anything else is a typo, and a typo
# is not a harmless one: `partial!` written `partail!` matches no mutant, so the row is silently
# dropped from the counterfactual phase and the row that reproduces #83 stops having one. The
# suite stayed green through exactly that, at `78 rows, 0 failed`, with the row count as the
# only trace and nothing asserting it. Both directions are checked — every guard names a real
# mutant, and every mutant is named by at least one row — because a mutant nobody references is
# equally silent.
MUTANTS='num measured zerotests partial multiline skipguard pessimist nofiles emit pre76'
MUTANTS="$MUTANTS floormissing floorbelow floorundeclared floorfile floorroster floorsettings"
MUTANTS="$MUTANTS floordrift pre97"
GUARDS_SEEN=''

check() {
    label=$1; guards=$2; fixture=$3; pathmode=$4; envmode=$5
    shift 5

    want_marker=''
    forbid_marker=''
    case $pathmode in
        impl)    runpath="$WORK/path/awk-$IMPL:$BARE"; want_marker="awk-$IMPL" ;;
        # awk is on PATH and the row asserts it was *not* reached — the early exit really did
        # short-circuit rather than parsing an empty file list. There is no way to write a row
        # that simply declines to say which of the two happened.
        unused)  runpath="$WORK/path/awk-$IMPL:$BARE"; forbid_marker="awk-$IMPL" ;;
        noawk)   runpath="$BARE" ;;
        broken)  runpath="$WORK/path/broken:$BARE"; want_marker=broken ;;
        garbage) runpath="$WORK/path/garbage:$BARE"; want_marker=garbage ;;
        *) die "check: unknown pathmode '$pathmode' in row $label" ;;
    esac

    # In mutant mode only the cases that name this mutant are run, and their verdict is
    # inverted: the guard is removed, so the case has to fail.
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
                *) die "check: row $label names guard '$g', which is not a mutant. A guard that matches no mutant drops this row out of the counterfactual phase without failing anything." ;;
            esac
            case " $GUARDS_SEEN " in
                *" $g "*) ;;
                *) GUARDS_SEEN="$GUARDS_SEEN $g" ;;
            esac
        done
    fi

    rm -f "$MARKERS"/* 2>/dev/null || true
    summary=$WORK/summary-$$.md
    rm -f "$summary"

    # Both by fixture name, so a row cannot read another row's floors and cannot fall back to the
    # committed one — which would make every floor assertion depend on numbers that change every
    # time a module gains a test.
    fx_floors=$WORK/fx/$fixture.floors
    fx_modules=''
    if [ -f "$WORK/fx/$fixture.modules" ]; then
        fx_modules=$(cat "$WORK/fx/$fixture.modules")
    fi

    run_count=$((run_count + 1))
    set +e
    if [ "$envmode" = summary ]; then
        got=$(PATH="$runpath" GITHUB_STEP_SUMMARY="$summary" \
            AWAKENER_TEST_FLOORS="$fx_floors" AWAKENER_TEST_MODULES="$fx_modules" \
            "$RUN_SCRIPT" "$WORK/fx/$fixture" 2>&1 </dev/null)
    else
        got=$(env -u GITHUB_STEP_SUMMARY PATH="$runpath" \
            AWAKENER_TEST_FLOORS="$fx_floors" AWAKENER_TEST_MODULES="$fx_modules" \
            "$RUN_SCRIPT" "$WORK/fx/$fixture" 2>&1 </dev/null)
    fi
    rc=$?
    set -e
    no_leak "row $label"

    why=''
    # The script reports and does not gate, so every row expects 0 unless it says otherwise.
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

    if [ -z "$why" ] && [ -n "$want_marker" ] && [ ! -e "$MARKERS/$want_marker" ]; then
        why="the '$want_marker' stub left no marker, so it never ran and this row tested nothing"
    fi
    if [ -z "$why" ] && [ -n "$forbid_marker" ] && [ -e "$MARKERS/$forbid_marker" ]; then
        why="awk was invoked, and this row asserts the script returned before parsing anything"
    fi

    if [ -z "$why" ] && [ "$envmode" = summary ]; then
        if [ ! -s "$summary" ]; then
            why="GITHUB_STEP_SUMMARY was set and nothing was written to it"
        else
            for a in "$@"; do
                case $a in
                    +*)
                        if ! grep -Fq -- "${a#+}" "$summary"; then
                            why="the step summary file lacks '${a#+}'"
                            break
                        fi
                        ;;
                esac
            done
        fi
    fi

    if [ "$MODE" = mutant ]; then
        if [ -z "$why" ]; then
            note "FAIL $label [$MUTANT]: the guard was removed and the row still passed, so it does not test it"
            detail "$got"
            fail_count=$((fail_count + 1))
            return 0
        fi
        if [ "$MUTANT_REASSURE" = yes ]; then
            case " $guards " in
                *" $MUTANT! "*)
                    case $got in
                        *"$REASSURE"*) ;;
                        *)
                            note "FAIL $label [$MUTANT]: went red, but without the mutant printing the reassurance — the regression this row claims is not the one it reproduces"
                            detail "$got"
                            fail_count=$((fail_count + 1))
                            return 0
                            ;;
                    esac
                    ;;
            esac
        fi
        note "ok   $label [$MUTANT] red as required: $why"
        return 0
    fi

    if [ -n "$why" ]; then
        note "FAIL $label [$IMPL]: $why"
        detail "$got"
        fail_count=$((fail_count + 1))
    else
        note "ok   $label [$IMPL]"
    fi
}

# ------------------------------------------------------------------ the cases
cases() {
    check clean 'pessimist' clean impl plain \
        '+| `wm` | 2 | 101 | 0 | 0 | 0 | 101 |' \
        '+| **total** | **2** | **101** | **0** | **0** | **0** | **101** |' \
        "+$REASSURE" \
        "-$UNMEASURED" "-$LOWER" "-$NOTESTS" "-lower bound" "-$BELOWFLOOR"

    check multi-module 'pessimist' multi impl plain \
        '+| `cli` | 1 | 55 | 0 | 0 | 0 | 55 |' \
        '+| `registry` | 1 | 78 | 0 | 0 | 0 | 78 |' \
        '+| `wm` | 1 | 101 | 0 | 0 | 0 | 101 |' \
        '+| **total** | **3** | **234** | **0** | **0** | **0** | **234** |' \
        "+$REASSURE" "-$LOWER"

    check skipped-nonzero 'pessimist skipguard!' skips impl plain \
        '+| **total** | **1** | **101** | **3** | **0** | **0** | **101** |' \
        "+$SKIPS" "-$REASSURE"

    check failures-but-no-skips 'pessimist' failures impl plain \
        '+| **total** | **1** | **101** | **0** | **2** | **1** | **101** |' \
        "+$REASSURE"

    check multiline-tag 'multiline pre76!' multiline impl plain \
        '+| **total** | **1** | **101** | **0** | **0** | **0** | **101** |' \
        "+$REASSURE" "-$UNMEASURED"

    # The floor here is above what the fixture could report even if it parsed, and the row asserts
    # the floor stays quiet: a shortfall inferred from a measurement that failed would be a claim
    # about coverage drawn from no evidence, which is the shape of everything else in this file.
    check truncated-only 'measured pre76!' truncated impl plain \
        '+::warning title=Test counts unreadable' \
        '+| **total** | *unread* | *unread* | *unread* | *unread* | *unread* | **91** |' \
        "+$UNMEASURED" "-$REASSURE" "-$BELOWFLOOR" "-$NORESULTS"

    check zero-tests-counted 'zerotests! pre76!' zerotests impl plain \
        "+$NOTESTS" "-$REASSURE" "-$UNMEASURED"

    check no-awk-on-path 'measured pre76!' truncated noawk plain \
        "+$UNMEASURED" "-$REASSURE"

    check broken-awk 'measured pre76!' clean broken plain \
        '+::warning title=Test counts unreadable' \
        "+$UNMEASURED" "-$REASSURE"

    check non-numeric-awk 'num pre76!' clean garbage plain \
        "+$UNMEASURED" "-$REASSURE"

    # #83, and the output quoted in it.
    check partial-parse 'partial! pessimist' partial impl plain \
        '+::warning title=Test counts incomplete' \
        '+1 of 2 JUnit XML file(s) yielded no <testsuite> element' \
        '+| **total (lower bound)** | **1** | **10** | **0** | **0** | **0** | **10** |' \
        "+$LOWER" "-$REASSURE" "-$UNMEASURED" "-$BELOWFLOOR"

    check partial-parse-with-skips 'partial pessimist' partialskips impl plain \
        "+$LOWER" "+$SKIPS" "-$REASSURE"

    check two-suites-one-file 'pessimist' twosuites impl plain \
        '+| **total** | **2** | **15** | **0** | **0** | **0** | **15** |' \
        "+$REASSURE" "-$LOWER"

    check two-suites-plus-unread 'partial! pessimist' twosuitespartial impl plain \
        '+1 of 2 JUnit XML file(s) yielded no <testsuite> element' \
        '+| **total (lower bound)** | **2** | **15** | **0** | **0** | **0** | **15** |' \
        "+$LOWER" "-$REASSURE"

    check testsuites-wrapper 'pessimist' wrapper impl plain \
        '+| **total** | **1** | **7** | **0** | **0** | **0** | **7** |' \
        "+$REASSURE" "-$LOWER"

    # Not a warning any more. Before the floor this exited 0: no module produced anything, and the
    # strongest form of "the run verified nothing" was the one case the script declined to gate on.
    check no-xml-at-all 'nofiles floormissing' empty unused plain \
        '+::warning title=No JUnit XML' \
        "+$NOFILES" "+$BELOWFLOOR" "+$NORESULTS" "-$REASSURE" "-$UNMEASURED" '=1'

    check step-summary-written 'emit pessimist' clean impl summary \
        '+| **total** | **2** | **101** | **0** | **0** | **0** | **101** |' \
        "+$REASSURE"

    # ------------------------------------------------------------ #97: what should have run

    # The measurement in the issue: `cli` correct in every column, `wm` simply not there, and
    # before this guard nothing anywhere printed a character about it.
    check module-produced-nothing 'floormissing! pre97!' missingmodule impl plain \
        '+::error title=Test floor not met' \
        '+| `cli` | 1 | 55 | 0 | 0 | 0 | 55 |' \
        '+| `wm` | — | — | — | — | — | **101, and it reported nothing** |' \
        "+$BELOWFLOOR" "+$NORESULTS" "-$REASSURE" '=1'

    check module-below-its-floor 'floorbelow! pre97!' belowfloor impl plain \
        '+`wm` reported 100 tests, below the committed floor of 101' \
        "+$BELOWFLOOR" "-$REASSURE" '=1'

    check module-not-declared 'floorundeclared! pre97!' undeclared impl plain \
        "+$UNDECLARED" "-$REASSURE" '=1'

    check floor-file-absent 'floorfile! pre97!' nofloors impl plain \
        '+::error title=Test floor file unusable' \
        "+$BADFLOORS" "-$REASSURE" '=1'

    check floor-file-unparseable 'floorfile pre97!' badfloors impl plain \
        "+is not a test count for wm" \
        "+$BADFLOORS" "-$REASSURE" '=1'

    check floor-file-declares-twice 'floorfile pre97!' dupfloors impl plain \
        '+wm is declared twice' \
        "+$BADFLOORS" "-$REASSURE" '=1'

    check roster-has-module-floors-lack 'floorroster! pre97!' rostergap impl plain \
        "+the build includes 'registry'" \
        "+$BADFLOORS" "-$REASSURE" '=1'

    check roster-lacks-module-floors-have 'floorroster pre97!' rosterextra impl plain \
        "+declares 'bus', which is not a module in this build" \
        "+$BADFLOORS" "-$REASSURE" '=1'

    # The positive: a roster that agrees is not an occasion for anything to be said.
    check roster-agrees 'pessimist' rosterok impl plain \
        "+$REASSURE" "-$BADFLOORS" "-$BELOWFLOOR"

    check roster-read-from-settings 'floorsettings! floorroster pre97!' settingsgap impl plain \
        "+the build includes 'registry'" \
        "+$BADFLOORS" "-$REASSURE" '=1'

    check roster-from-settings-agrees 'pessimist' settingsok impl plain \
        "+$REASSURE" "-$BADFLOORS"

    check floor-behind-what-ran 'floordrift pessimist' driftup impl plain \
        '+Floors behind what ran: `wm` 90→101.' \
        "+$REASSURE" "-$BELOWFLOOR"
}

# ------------------------------------------------------------------ phase 1: the real script
RUN_SCRIPT=$SCRIPT
for impl in $IMPLS; do
    IMPL=$impl
    note ""
    note "# every row against the committed script, under awk/$impl"
    cases
done

# The other half of the guard-name check. A mutant nothing references is built, spliced,
# syntax-checked, probed — and then reddens nothing, which reads in the log exactly like a
# mutant whose rows all passed correctly.
for m in $MUTANTS; do
    case " $GUARDS_SEEN " in
        *" $m "*) ;;
        *) die "mutant '$m' is named by no row, so its phase asserts nothing" ;;
    esac
done

# ------------------------------------------------------------------ mutants
#
# Each is the committed script with one guard textually removed. The replacement is literal,
# and both halves are asserted: the anchor has to be present before, and gone after, so a
# mutant cannot silently become a copy of the original — which would make its whole phase pass
# by testing nothing. The result is syntax-checked for the same reason: a mutant that will not
# parse fails every row it touches and would read as a counterfactual.
splice() {
    # splice <file> <old> <new>
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

M_NUM_OLD="    '' | *[!0-9]*) printf '0' ;;"
M_NUM_NEW="    '' | *[!0-9]*) printf '%s' \"\${1:-}\" ;;"
M_MEASURED_OLD='elif [ "$awk_rc" -ne 0 ] || [ -z "$total" ] || [ "$tot_suites" -eq 0 ]; then'
M_MEASURED_NEW='elif [ 1 -eq 2 ]; then'
M_ZERO_OLD='elif [ "$tot_tests" -eq 0 ]; then'
M_ZERO_NEW='elif [ 1 -eq 2 ]; then'
M_PARTIAL_OLD='if [ "$measured" = yes ] && [ "$tot_read" -lt "${#files[@]}" ]; then'
M_PARTIAL_NEW='if [ 1 -eq 2 ]; then'
M_ML1_OLD='FNR == 1 { flush(cur); cur = FILENAME; buf = "" }'
M_ML1_NEW='FNR == 1 { cur = FILENAME }'
M_ML2_OLD='{ buf = buf $0 " " }'
M_ML2_NEW='{ buf = $0; flush(cur); buf = "" }'

mkmutant() {
    name=$1
    mkdir -p "$WORK/mutant"
    cp "$SCRIPT" "$WORK/mutant/$name.sh"
    chmod +x "$WORK/mutant/$name.sh"
}

apply() {
    case $2 in
        num)      splice "$WORK/mutant/$1.sh" "$M_NUM_OLD" "$M_NUM_NEW" ;;
        measured) splice "$WORK/mutant/$1.sh" "$M_MEASURED_OLD" "$M_MEASURED_NEW" ;;
        zerotests) splice "$WORK/mutant/$1.sh" "$M_ZERO_OLD" "$M_ZERO_NEW" ;;
        partial)  splice "$WORK/mutant/$1.sh" "$M_PARTIAL_OLD" "$M_PARTIAL_NEW" ;;
        multiline)
            splice "$WORK/mutant/$1.sh" "$M_ML1_OLD" "$M_ML1_NEW"
            splice "$WORK/mutant/$1.sh" "$M_ML2_OLD" "$M_ML2_NEW"
            # A three-line anchor, so it goes through a file rewrite rather than the
            # line-at-a-time splice above.
            grep -Fq 'flush(cur)' "$WORK/mutant/$1.sh" ||
                die "mutant multiline: the END flush is already gone"
            grep -v '^      flush(cur)$' "$WORK/mutant/$1.sh" >"$WORK/mutant/$1.tmp"
            mv "$WORK/mutant/$1.tmp" "$WORK/mutant/$1.sh"
            chmod +x "$WORK/mutant/$1.sh"
            if grep -q '^      flush(cur)$' "$WORK/mutant/$1.sh"; then
                die "mutant multiline: the END flush survived"
            fi
            ;;
        skipguard)
            splice "$WORK/mutant/$1.sh" \
                'if [ "$tot_skipped" -gt 0 ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        pessimist)
            splice "$WORK/mutant/$1.sh" 'measured=yes' 'measured=no'
            ;;
        nofiles)
            splice "$WORK/mutant/$1.sh" 'if [ "$nofiles" = yes ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        emit)
            splice "$WORK/mutant/$1.sh" 'tee -a "$GITHUB_STEP_SUMMARY"' 'cat'
            ;;
        # The four floor guards, one splice each. They are separate mutants rather than one,
        # because "a module reported nothing" and "a module reported less than it should" are
        # different comparisons and a single mutant would let either of them carry the other.
        floormissing)
            splice "$WORK/mutant/$1.sh" 'if [ "$min" -gt 0 ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        floorbelow)
            splice "$WORK/mutant/$1.sh" 'if [ "$te" -lt "$min" ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        floorundeclared)
            splice "$WORK/mutant/$1.sh" \
                'if ! floor_min_of "$m" >/dev/null; then' 'if [ 1 -eq 2 ]; then'
            ;;
        # Reporting and the exit status together, because they are what a reader and a CI check
        # respectively act on. The evaluation stays suppressed — `floors_error` is still set — so
        # this mutant is "the file is unusable and nothing says so", not "the file is fine".
        floorfile)
            splice "$WORK/mutant/$1.sh" 'if [ -n "$floors_error" ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        floorroster)
            splice "$WORK/mutant/$1.sh" \
                'if [ -z "$floors_error" ] && [ -n "$ROSTER" ]; then' 'if [ 1 -eq 2 ]; then'
            ;;
        floorsettings)
            splice "$WORK/mutant/$1.sh" \
                'if [ -z "$floors_error" ] && [ -z "$ROSTER" ] && [ -f settings.gradle.kts ]; then' \
                'if [ 1 -eq 2 ]; then'
            ;;
        floordrift)
            splice "$WORK/mutant/$1.sh" 'elif [ "$te" -gt "$min" ]; then' 'elif [ 1 -eq 2 ]; then'
            ;;
        *) die "apply: unknown mutation '$2'" ;;
    esac
}

# name -> mutations, and whether the mutant is expected to print the reassurance for the rows
# tagged with a `!`.
run_mutant() {
    MUTANT=$1
    MUTANT_REASSURE=$2
    shift 2
    mkmutant "$MUTANT"
    for m in "$@"; do apply "$MUTANT" "$m"; done
    bash -n "$WORK/mutant/$MUTANT.sh" || die "mutant $MUTANT does not parse"

    # A mutant that cannot run goes red on every row for a reason that has nothing to do with
    # the guard, and "red" is the whole verdict this phase reads — so an unrunnable mutant
    # turns the entire counterfactual into theatre while reporting a clean pass. That is not
    # hypothetical: the first draft of this file lost the execute bit in `splice`, and every
    # row below came back "red as required" on `exit 126`. Removing a guard must not stop the
    # script working, so the mutant is required to run the clean fixture to completion first.
    set +e
    env -u GITHUB_STEP_SUMMARY PATH="$WORK/path/awk-$IMPL:$BARE" \
        AWAKENER_TEST_FLOORS="$WORK/fx/clean.floors" AWAKENER_TEST_MODULES='' \
        "$WORK/mutant/$MUTANT.sh" "$WORK/fx/clean" \
        >"$WORK/mutant/$MUTANT.probe" 2>&1 </dev/null
    probe_rc=$?
    set -e
    no_leak "the $MUTANT mutant's validity probe"
    [ "$probe_rc" -eq 0 ] ||
        die "mutant $MUTANT exits $probe_rc on the clean fixture, so every row it reddens would be red for the wrong reason: $(head -3 "$WORK/mutant/$MUTANT.probe")"
    grep -q '^### Test counts' "$WORK/mutant/$MUTANT.probe" ||
        die "mutant $MUTANT produced no report at all on the clean fixture"

    RUN_SCRIPT=$WORK/mutant/$MUTANT.sh
    note ""
    note "# counterfactual: the rows that name '$MUTANT' must go red without it"
    before=$run_count
    cases
    # Belt to the roster check's braces: that one catches a guard naming no mutant, this one
    # catches a mutant whose rows all sat out for any other reason.
    [ "$run_count" -gt "$before" ] || die "mutant $MUTANT ran no rows at all"
}

# Mutants test the script's logic rather than the parser's portability, so one implementation
# is enough for this phase — the first on the roster.
MODE=mutant
IMPL=${IMPLS# }
IMPL=${IMPL%% *}

run_mutant num       yes num
run_mutant measured  no  measured
run_mutant zerotests yes zerotests
run_mutant partial   yes partial
run_mutant multiline no  multiline
run_mutant skipguard yes skipguard
run_mutant pessimist no  pessimist
run_mutant nofiles   no  nofiles
run_mutant emit      no  emit
# The shape the script had when #76 was filed: no integer normalisation, no no-measurement
# branch, no zero-tests branch, a per-line match for the suite tag, and — since #83's guard
# did not exist either — no shortfall check. Every row tagged `pre76!` prints the reassurance
# against it, which is the defect reproduced rather than described.
#
# The floor guards come off too, and that is not padding: several of these fixtures declare a
# floor their mutated parse cannot meet, so the floor would catch #76's defect for #97's reason
# and the row would go red without reproducing anything. An era mutant has to be the whole era.
run_mutant pre76 yes num measured zerotests multiline partial \
    floormissing floorbelow floorundeclared floorfile floorroster floorsettings floordrift
run_mutant floormissing    yes floormissing
run_mutant floorbelow      yes floorbelow
run_mutant floorundeclared yes floorundeclared
run_mutant floorfile       yes floorfile
run_mutant floorroster     yes floorroster
run_mutant floorsettings   yes floorsettings
run_mutant floordrift      no  floordrift
# The shape the script had when #97 was filed: it read the XML faithfully and compared it to
# nothing. Every row tagged `pre97!` prints the reassurance against it — including the ones where
# a whole module is missing, which is the measurement in the issue rather than a description of it.
run_mutant pre97 yes floormissing floorbelow floorundeclared floorfile floorroster \
    floorsettings floordrift

# ------------------------------------------------ the floor file's default location
#
# Every row above hands the script an explicit `AWAKENER_TEST_FLOORS`, which is what lets fixtures
# carry their own floors — and leaves the default, the only path the CI step and the Gradle task
# use, asserted by nothing. A guard whose input is resolved wrongly is a guard that is not there,
# and it would fail open: no file found, and before this pair, no file found meant nothing to
# compare against. So: the script beside a floor file, the variable unset, once green and once red.
MODE=real
RUN_SCRIPT=$SCRIPT
note ""
note "# the floor file resolved from the script's own directory, with the variable unset"
DEFDIR=$WORK/default
mkdir -p "$DEFDIR"
cp "$SCRIPT" "$DEFDIR/test-summary.sh"
chmod +x "$DEFDIR/test-summary.sh"
printf 'wm 101\n' >"$DEFDIR/test-floors"

default_row() {
    label=$1
    want_exit=$2
    want_text=$3
    forbid_text=$4
    run_count=$((run_count + 1))
    set +e
    got=$(env -u GITHUB_STEP_SUMMARY -u AWAKENER_TEST_FLOORS -u AWAKENER_TEST_MODULES \
        PATH="$WORK/path/awk-$IMPL:$BARE" \
        "$DEFDIR/test-summary.sh" "$WORK/fx/clean" 2>&1 </dev/null)
    rc=$?
    set -e
    no_leak "the $label row"
    why=''
    [ "$rc" = "$want_exit" ] || why="exit $rc, wanted $want_exit"
    if [ -z "$why" ]; then
        case $got in
            *"$want_text"*) ;;
            *) why="output lacks '$want_text'" ;;
        esac
    fi
    if [ -z "$why" ] && [ -n "$forbid_text" ]; then
        case $got in
            *"$forbid_text"*) why="output contains '$forbid_text' and must not" ;;
        esac
    fi
    if [ -n "$why" ]; then
        note "FAIL $label [$IMPL]: $why"
        detail "$got"
        fail_count=$((fail_count + 1))
    else
        note "ok   $label [$IMPL]"
    fi
}

default_row default-floors-found 0 "$REASSURE" "$BADFLOORS"
rm -f "$DEFDIR/test-floors"
default_row default-floors-absent 1 "$BADFLOORS" "$REASSURE"

no_leak "the suite, over the whole run"

note ""
note "test-summary-matrix: $run_count rows, $fail_count failed"
note "  awk coverage: $IMPL_COUNT distinct implementations —$IMPLS"
if [ -n "$AMBIENT_SUMMARY" ]; then
    # Recorded rather than inferred. This line is what lets a later reader confirm from the log
    # that the variable really was set during the step this ran in, instead of reasoning about
    # whether GitHub sets it — the question that turned out to matter, and the one nothing in
    # the log could previously answer.
    note "  an ambient GITHUB_STEP_SUMMARY was set ($AMBIENT_SUMMARY) and is unchanged"
else
    note "  no ambient GITHUB_STEP_SUMMARY was set; the canary covered the leak path anyway"
fi
[ "$fail_count" -eq 0 ] || exit 1
