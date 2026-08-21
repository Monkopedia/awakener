#!/bin/sh
# The staleness check's own suite. Run by `gradlew stalenessMatrixTest`, which `check` depends on.
#
#   staleness-matrix.sh <script-under-test> <work-dir> <report>
#
# `.github/scripts/check-staleness.sh` answers the question CLAUDE.md tells every implementer and
# every reviewer to ask before believing a number, and the version it replaces answered it wrong
# in one direction: **every way of failing to reach an answer printed `STALE`** (#123). So the
# property this suite exists to hold is not "does it say current when current" — that half was
# never broken — it is that *could not check* has its own word and its own exit status and stays
# distinguishable from *checked, and you are behind*.
#
# Same three properties as `test-summary-matrix.sh`, for the same reasons:
#
#  1. **Every row has a counterfactual.** Each row names one or more mutants — the committed
#     script with one guard spliced out — and the mutant phase asserts the row goes red against
#     each. A row that survives its own guard's removal is not testing it. `pre123` is the era
#     mutant: it is the committed script with `unknown` collapsed to `printf STALE; exit 1`, which
#     is exactly what the old one-liner did with every non-answer. Rows tagged `pre123!` assert
#     that it prints `STALE` — the defect reproduced rather than described.
#  2. **Fixtures are real git repositories**, built here, with real remotes on local paths. The
#     fetch-failure fixture fails by pointing at a path that does not exist, which is honest,
#     offline, and instant — no network flake in a suite about instruments that lie when they
#     cannot reach something.
#  3. **Three exit statuses, because a suite that did not run is not a suite that passed** (#89):
#
#       0  every declared mutant ran and every row behaved
#       1  a row failed
#       2  the suite could not vouch — it never reached the state where a row verdict means
#          anything (a mutant that will not build, an anchor that has moved, a declared mutant
#          that never ran, fixture setup that failed)
#
#     And the mutant roster is a single table that is *also* the invocation list, so a mutant that
#     is declared and never run is unrepresentable rather than merely detected. That is #89's fix
#     carried into this file at birth rather than discovered in it later.
#
# `AWAKENER_MATRIX_MIN_MUTANTS` (default `all`) is how much of the counterfactual phase has to have
# executed for a 0 — the same switch, spelled the same way, as the other matrix suite.
#
# Written for POSIX sh.
set -eu

SCRIPT=${1:-}
WORK=${2:-}
REPORT=${3:-}

usage_die() {
    echo "staleness-matrix: CANNOT VOUCH: $1" >&2
    exit 2
}

[ -n "$SCRIPT" ] && [ -n "$WORK" ] && [ -n "$REPORT" ] ||
    usage_die "usage: staleness-matrix.sh <script> <work-dir> <report>"
[ -f "$SCRIPT" ] || usage_die "no script at $SCRIPT"
SCRIPT=$(CDPATH= cd -P -- "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")

command -v git >/dev/null 2>&1 || usage_die "no git; this suite is entirely about git repositories"

rm -rf "$WORK"
mkdir -p "$WORK"
WORK=$(CDPATH= cd -P -- "$WORK" && pwd)
FX=$WORK/fx
mkdir -p "$FX"

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

# Exit 2. See the header: "the suite could not run" and "a row failed" are different facts and a
# caller must not have to read prose to tell them apart.
die() {
    note "staleness-matrix: CANNOT VOUCH: $1"
    exit 2
}

# ------------------------------------------------------------------ fixture construction
#
# Every git invocation in this file runs with global and system config neutralised and an explicit
# identity, so a developer's `~/.gitconfig` — a default branch name, a pager, a hook path, an
# `insteadOf` rewrite — cannot change what a row measures. A fixture built differently on two
# hosts is a fixture that proves something different on each.
GIT_CONFIG_GLOBAL=/dev/null
GIT_CONFIG_SYSTEM=/dev/null
GIT_AUTHOR_NAME=matrix
GIT_AUTHOR_EMAIL=matrix@example.invalid
GIT_COMMITTER_NAME=matrix
GIT_COMMITTER_EMAIL=matrix@example.invalid
export GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM
export GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL
unset GIT_DIR GIT_WORK_TREE 2>/dev/null || true

# `$WORK` is a build directory *inside* this repository when Gradle runs the suite, and git's
# discovery walks upwards — so without a ceiling the "not a git repository" fixture would find
# awakener's own `.git` and the row would quietly become a different test. Exported for the rows
# too, since it is the fixture's property rather than the harness's.
GIT_CEILING_DIRECTORIES=$WORK
export GIT_CEILING_DIRECTORIES

g() {
    where=$1
    shift
    ( cd "$where" && git "$@" ) >/dev/null 2>&1 || die "fixture setup: git $* failed in $where"
}

# A bare remote with one commit on `main`, and a work tree pointed at it whose HEAD is that commit.
mkpair() {
    name=$1
    git init --quiet --bare -b main "$FX/$name.git" >/dev/null 2>&1 ||
        die "fixture setup: could not init bare remote for $name"
    git init --quiet -b main "$FX/$name" >/dev/null 2>&1 ||
        die "fixture setup: could not init work tree for $name"
    g "$FX/$name" commit --quiet --allow-empty -m base
    g "$FX/$name" remote add origin "$FX/$name.git"
    g "$FX/$name" push --quiet -u origin main
}

# current: the work tree is ahead of the remote, so the remote tip is an ancestor of HEAD.
mkpair current
g "$FX/current" commit --quiet --allow-empty -m local-work

# stale: the remote has moved on and the work tree has not. The script's own fetch is what makes
# this visible, which is the point — the fixture is only stale once something confronts it.
mkpair stale
git clone --quiet "$FX/stale.git" "$FX/stale.push" >/dev/null 2>&1 ||
    die "fixture setup: could not clone stale.push"
g "$FX/stale.push" commit --quiet --allow-empty -m moved-on
g "$FX/stale.push" push --quiet origin main

# detached: answerable, and asserted to be answered rather than reported UNKNOWN.
mkpair detached
g "$FX/detached" commit --quiet --allow-empty -m local-work
g "$FX/detached" checkout --detach

# nofetch: the remote is configured and its path does not exist, so `git fetch` fails outright.
mkpair nofetch
g "$FX/nofetch" remote set-url origin "$FX/there-is-no-such-remote.git"

# noremote: a repository with commits and no remote at all.
git init --quiet -b main "$FX/noremote" >/dev/null 2>&1 ||
    die "fixture setup: could not init noremote"
g "$FX/noremote" commit --quiet --allow-empty -m base

# unborn: a real repository, a reachable remote, and a HEAD that resolves to nothing. The fetch
# succeeds here, so this row is about the head-resolution guard and not about the fetch.
git init --quiet -b main "$FX/unborn" >/dev/null 2>&1 ||
    die "fixture setup: could not init unborn"
g "$FX/unborn" remote add origin "$FX/current.git"

# notarepo: a plain directory. See GIT_CEILING_DIRECTORIES above for why it stays plain.
mkdir -p "$FX/notarepo"

# The fixtures have to be confronted rather than trusted: a row that expects UNKNOWN passes just as
# well against a fixture that was never built. Each assertion below is about the property the row
# depends on, checked with git rather than by looking at the directory listing.
fixture_has() {
    where=$FX/$1
    shift
    ( cd "$where" && git "$@" ) >/dev/null 2>&1
}
if ! fixture_has current rev-parse --verify --quiet 'origin/main^{commit}'; then
    die "fixture current has no origin/main"
fi
if ! fixture_has stale rev-parse --verify --quiet 'HEAD^{commit}'; then
    die "fixture stale has no HEAD"
fi
if fixture_has unborn rev-parse --verify --quiet 'HEAD^{commit}'; then
    die "fixture unborn has a resolvable HEAD, so its row would test the wrong guard"
fi
if [ -e "$FX/there-is-no-such-remote.git" ]; then
    die "the nofetch fixture's remote path exists, so its fetch would succeed"
fi
if fixture_has notarepo rev-parse --git-dir; then
    die "fixture notarepo resolves a git dir; the ceiling is not holding and the row tests nothing"
fi

# ------------------------------------------------------------------ rows
#
# A row is: a label, the guards it holds, the arguments and environment it runs under, the exit
# status it expects, and a substring its single line of stdout must contain. Naming no guard is
# rejected — a row nothing can break is a row that proves nothing.
MODE=real
MUTANT=''
MUTANT_REASSURE=no
RUN_SCRIPT=$SCRIPT
GUARDS_SEEN=''
ROW_ENV=''
# The three per-row modifiers, all set immediately before a `check` and all cleared by it, so a row
# cannot inherit the one above it.
#
#   ROW_ENV    env assignments the row runs under
#   ROW_CWD    directory the row runs *in*, for the default-resolution row that passes no -C.
#              This exists because the alternative was a second copy of `check` (#138): the
#              default-cwd row used to be a hand-rolled `check_default_cwd()` whose verdict logic
#              had been transcribed, and the transcription lost two things — the `detail` line on
#              failure, and the real-mode loop that dies when a row names a guard matching no
#              mutant. #89's shape again, latent only because `ancestryflip` happened to be named
#              by other rows too. One variable is cheaper than one copy and cannot drift from it.
#   ROW_LINES  `1` (the default) asserts the row's output is exactly one line, which is what a
#              caller reading this with `$(…)` depends on. `any` is for the `-h` row, whose whole
#              point is that it prints nine.
ROW_CWD=''
ROW_LINES=1

check() {
    label=$1; guards=$2; want_exit=$3; want_text=$4
    shift 4

    if [ "$MODE" = mutant ]; then
        case " $guards " in
            *" $MUTANT "* | *" $MUTANT! "*) ;;
            *) ROW_ENV=''; ROW_CWD=''; ROW_LINES=1; return 0 ;;
        esac
    else
        [ -n "$guards" ] || die "row $label names no guard"
        for gd in $guards; do
            gd=${gd%!}
            case " $MUTANTS " in
                *" $gd "*) ;;
                *) die "row $label names guard '$gd', which is not a mutant. A guard that matches no mutant drops this row out of the counterfactual phase without failing anything." ;;
            esac
            case " $GUARDS_SEEN " in
                *" $gd "*) ;;
                *) GUARDS_SEEN="$GUARDS_SEEN $gd" ;;
            esac
        done
    fi

    run_count=$((run_count + 1))
    row_lines=$ROW_LINES
    set +e
    # `SCRIPT` is absolutised at the top and every `$FX` argument is absolute, so running the row
    # from somewhere other than here changes only what the script under test sees as its cwd.
    got=$(cd "${ROW_CWD:-.}" && env $ROW_ENV "$RUN_SCRIPT" "$@" 2>&1 </dev/null)
    rc=$?
    set -e
    ROW_ENV=''
    ROW_CWD=''
    ROW_LINES=1

    why=''
    if [ "$MODE" = mutant ]; then
        # Inverted: the guard is gone, so the row has to fail. "Red" is the whole verdict, so it
        # is worth saying which half went red in the log.
        if [ "$rc" = "$want_exit" ]; then
            case $got in
                *"$want_text"*) why="the guard was removed and the row still passed, so it does not test it" ;;
            esac
        fi
        # `!` says this row reproduces the defect rather than merely noticing its absence: under an
        # era mutant it must print exactly the verdict the old idiom printed.
        case " $guards " in
            *" $MUTANT! "*)
                if [ "$MUTANT_REASSURE" = yes ] && [ "$got" != STALE ]; then
                    why="expected the mutant to print exactly 'STALE' for this row, got '$got'"
                fi
                ;;
        esac
    else
        [ "$rc" = "$want_exit" ] || why="exit $rc, wanted $want_exit"
        if [ -z "$why" ]; then
            case $got in
                *"$want_text"*) ;;
                *) why="output lacks '$want_text'" ;;
            esac
        fi
        # One line, always — for every row that is a verdict. A caller reads a verdict with `$(…)`
        # and compares it to a word. `-h` is the one row that is not a verdict, and it says so.
        if [ -z "$why" ] && [ "$row_lines" = 1 ] &&
            [ "$(printf '%s\n' "$got" | wc -l)" -ne 1 ]; then
            why="output is not exactly one line"
        fi
    fi

    if [ -n "$why" ]; then
        note "FAIL $label${MUTANT:+ [$MUTANT]}: $why"
        detail "$got (exit $rc)"
        fail_count=$((fail_count + 1))
    else
        note "ok   $label${MUTANT:+ [$MUTANT]}"
    fi
}

rows() {
    # --- answered, and the answer is yes
    check ancestry-current  'ancestryflip' 0 current -C "$FX/current"
    check detached-answered 'ancestryflip' 0 current -C "$FX/detached"
    # The default resolution — no -C, cwd is the repo — is the only shape an agent types by hand,
    # and every other row here passes -C. Left unasserted it would be the one path nothing covers.
    ROW_CWD="$FX/current"
    check default-cwd 'ancestryflip' 0 current

    # --- answered, and the answer is no
    check ancestry-stale 'staleverdict' 1 STALE -C "$FX/stale"
    ROW_ENV='AWAKENER_STALENESS_BASE=main'
    check base-from-env 'staleverdict' 1 STALE -C "$FX/stale"

    # --- not answered, and saying so is the whole fix
    check fetch-failed 'pre123! verdictword! unknownexit' 2 \
        'UNKNOWN: git fetch origin exited' -C "$FX/nofetch"
    check no-remote 'pre123! verdictword! unknownexit' 2 \
        "UNKNOWN: no remote named 'origin'" -C "$FX/noremote"
    check not-a-repo 'pre123! verdictword! unknownexit' 2 \
        'UNKNOWN: not inside a git repository' -C "$FX/notarepo"
    # The shape both observed instances took: `cd <path> && <the idiom>`, the cd failing, `&&`
    # short-circuiting, and STALE printed with ancestry never evaluated.
    check cd-failed 'pre123! verdictword! unknownexit' 2 \
        'UNKNOWN: cannot enter directory' -C "$FX/no-such-directory"
    check unborn-head 'pre123! verdictword! unknownexit refguards!' 2 \
        "UNKNOWN: 'HEAD' does not resolve" -C "$FX/unborn"
    check base-missing 'pre123! verdictword! unknownexit refguards!' 2 \
        "UNKNOWN: 'origin/nonesuch' does not resolve" -C "$FX/current" --base nonesuch
    check bad-argument 'pre123 verdictword unknownexit' 2 \
        "UNKNOWN: unrecognised argument" -C "$FX/current" --wat

    # --- the switch: UNKNOWN as a report rather than a gate
    ROW_ENV='AWAKENER_STALENESS_UNKNOWN=warn'
    check warn-mode-reports 'warnmode' 0 'UNKNOWN: git fetch origin exited' -C "$FX/nofetch"
    # And an unusable switch value cannot suppress the report of itself, whatever it is set to.
    ROW_ENV='AWAKENER_STALENESS_UNKNOWN=maybe'
    check bad-switch-value 'modevalidate' 2 \
        'UNKNOWN: AWAKENER_STALENESS_UNKNOWN must be' -C "$FX/current"

    # --- the help text, because that is where the three-status split is learnt
    #
    # `-h` prints a range of the script's own header, and the range was one line short of the three
    # statuses it had just promised (#138): it ended on "Exactly one line on stdout, and one of
    # three exit statuses:" and stopped. Nothing here covered `-h` at all, so the whole suite was
    # green with the defect in place — the same silence #89 is about, one file over. The row
    # asserts the *third* status is present, since it is the last line of the range and the two
    # above it cannot be reached without it, and `usagerange` is the range put back the way it was.
    ROW_LINES=any
    check usage-lists-statuses 'usagerange' 0 \
        'UNKNOWN: <reason>  2   the check could not be made' -h
}

# ------------------------------------------------------------------ the mutant table
#
# Declaring a mutant and running it are the same act — see #89, and the identical note in
# `test-summary-matrix.sh`. `MUTANTS` is derived from this table and the mutant phase is a loop
# over it, so a name that is declared, named by rows, and never run cannot be written.
#
# Format, one record per line: `name|reassure|mutation [mutation …]`. `reassure` is whether rows
# tagged `name!` must see the mutant print exactly `STALE` — the old idiom's verdict, and the thing
# #123 is about.
MUTANT_TABLE='
# The era mutant. `unknown` collapsed to STALE/1 is precisely what the one-liner in CLAUDE.md did
# with every path that failed to reach an answer, so the rows tagged pre123! reproduce the defect
# rather than describing it.
pre123|yes|verdictword unknownexit

# The two halves of it, separately, because "the word is wrong" and "the exit status is wrong" are
# different failures and a caller may only be reading one of them.
verdictword|yes|verdictword
unknownexit|no|unknownexit

# The ref guards plus the ancestry-status guard. Removing any one alone still lands on another
# honest UNKNOWN — the layering is deliberate — so the counterfactual has to take all three, which
# is what makes an unresolvable ref come back as STALE.
refguards|yes|headcheck basecheck ancestrystatus

warnmode|no|warnmode
modevalidate|no|modevalidate
ancestryflip|no|ancestryflip
staleverdict|no|staleverdict

# `-h` printing a range of the header that stops one line above the three exit statuses. Not a
# guard that was removed but a number that was wrong, so the mutation is the wrong number put back.
usagerange|no|usagerange
'

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
note "# every row against the committed script"
rows

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
        verdictword)
            splice "$f" "printf 'UNKNOWN: %s\\n' \"\$1\"" "printf 'STALE\\n'" ;;
        unknownexit)
            splice "$f" 'exit 2  # the status that means "could not check"' 'exit 1' ;;
        headcheck)
            splice "$f" 'git rev-parse --verify --quiet "$HEAD_REF^{commit}" >/dev/null 2>&1 ||' \
                'true ||' ;;
        basecheck)
            splice "$f" 'git rev-parse --verify --quiet "$REMOTE/$BASE^{commit}" >/dev/null 2>&1 ||' \
                'true ||' ;;
        ancestrystatus)
            splice "$f" '*) unknown "git merge-base --is-ancestor exited $ancestry_rc' \
                "*) printf 'STALE\\n'; exit 1 ;; #" ;;
        warnmode)
            splice "$f" 'if [ "$UNKNOWN_MODE" = warn ]; then' 'if [ 1 -eq 2 ]; then' ;;
        modevalidate)
            splice "$f" '    fail|warn) ;;' '    fail|warn|*) ;;' ;;
        ancestryflip)
            splice "$f" "0) printf 'current\\n'; exit 0 ;;" "0) printf 'STALE\\n'; exit 1 ;;" ;;
        staleverdict)
            splice "$f" "1) printf 'STALE\\n'; exit 1 ;;" "1) printf 'current\\n'; exit 0 ;;" ;;
        usagerange)
            splice "$f" "sed -n '2,10p' \"\$0\"" "sed -n '2,6p' \"\$0\"" ;;
        *) die "apply: unknown mutation '$2'" ;;
    esac
}

run_mutant() {
    MUTANT=$1
    MUTANT_REASSURE=$2
    shift 2
    mkdir -p "$WORK/mutant"
    cp "$SCRIPT" "$WORK/mutant/$MUTANT.sh"
    chmod +x "$WORK/mutant/$MUTANT.sh"
    for mm in "$@"; do apply "$MUTANT" "$mm"; done
    sh -n "$WORK/mutant/$MUTANT.sh" || die "mutant $MUTANT does not parse"

    # A mutant that cannot run goes red on every row for a reason that has nothing to do with the
    # guard, and "red" is the whole verdict this phase reads. So each mutant must still be a
    # working script: one line of output, one of the three verdicts, and one of the three statuses.
    # Deliberately not "must print `current`" — `ancestryflip` legitimately does not, and a probe
    # that hard-codes one verdict would rule out the mutants that change which verdict is right.
    set +e
    probe=$("$WORK/mutant/$MUTANT.sh" -C "$FX/current" 2>&1 </dev/null)
    probe_rc=$?
    set -e
    case $probe_rc in
        0|1|2) ;;
        *) die "mutant $MUTANT exits $probe_rc on the current fixture, so every row it reddens would be red for the wrong reason: $probe" ;;
    esac
    case $probe in
        current|STALE|'UNKNOWN: '*) ;;
        *) die "mutant $MUTANT printed '$probe' on the current fixture, which is none of the three verdicts" ;;
    esac

    RUN_SCRIPT=$WORK/mutant/$MUTANT.sh
    note ""
    note "# counterfactual: the rows that name '$MUTANT' must go red without it"
    before=$run_count
    rows
    [ "$run_count" -gt "$before" ] || die "mutant $MUTANT ran no rows at all"
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

note ""
note "staleness-matrix: $run_count rows, $fail_count failed"
note "  mutants: $mutants_ran of $MUTANTS_DECLARED declared ran (required: $MIN_MUTANTS)"
note "  git: $(git --version)"
[ "$fail_count" -eq 0 ] || exit 1
