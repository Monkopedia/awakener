#!/bin/sh
# Does this branch's HEAD still describe the tree that will merge?
#
#   check-staleness.sh [-C <dir>] [--remote <name>] [--base <ref>] [--head <ref>]
#
# Exactly one line on stdout, and one of three exit statuses:
#
#   current            0   the base ref is an ancestor of HEAD; counts measured here survive the merge
#   STALE              1   it is not; rebase or merge and re-run before reporting any number
#   UNKNOWN: <reason>  2   the check could not be made, and nothing above is being claimed
#
# **The third line is the whole point (#123).** CLAUDE.md carried this as a one-liner:
#
#     git fetch origin && git merge-base --is-ancestor origin/main HEAD && echo current || echo STALE
#
# `||` binds to the whole `&&` list, so *every* way of failing to reach an answer printed STALE. A
# failed fetch printed STALE. No such remote printed STALE. Running outside a repository printed
# STALE — and, as observed on #122, a `cd` that failed in front of it printed STALE with the
# ancestry test never evaluated at all. The idiom errs safe in that it cannot print a false
# `current`, but "I checked and you are behind" and "I could not check" want opposite responses:
# the first says rebase, the second says fix the environment and check again. An agent that
# rebases on a false STALE has spent a build *and* now believes it has verified currency, which is
# the repo's own defect class — an instrument reporting a conclusion it did not reach.
#
# So every path that does not end in an ancestry answer ends in `unknown`, including the ancestry
# call's own error status: `git merge-base --is-ancestor` returns 0 for yes and 1 for no, and
# anything else is neither, so it is reported as neither rather than folded into the `no`.
#
# **A detached HEAD is answered, not reported UNKNOWN**, and that is deliberate rather than an
# omission. Detachment is a property of the ref, not of the question: if `HEAD` resolves to a
# commit then "is the base an ancestor of it" has a true answer, and calling that UNKNOWN would be
# the same defect pointed the other way — refusing a conclusion that was in fact reached. What is
# genuinely unanswerable is a `HEAD` that resolves to nothing, which is the unborn-branch case
# below and is reported.
#
# **`-C` exists so this is never chained behind a `cd`.** Both observed instances of the defect
# came from `cd <path> && <the idiom>`, where the `cd` failed and `&&` short-circuited. A
# directory that cannot be entered is a reason the check could not be made, so the option is here
# and reports it as one.
#
# There is deliberately no way to skip the fetch. It would be convenient in CI, where the checkout
# already fetched, and it is the one option that could produce a **false `current`** — an ancestry
# answer about whatever the local remote-tracking ref happened to say. The idiom's single virtue
# was that its dangerous verdict was unreachable, and this keeps it.
#
# Switches, env vars rather than `Flags` objects because nothing here is on a JVM classpath and an
# agent runs this from a shell prompt. Defaults are the behaviour that would otherwise be
# hard-coded.
#
#   AWAKENER_STALENESS_REMOTE   (default origin)  remote to fetch and resolve the base against
#   AWAKENER_STALENESS_BASE     (default main)    branch on that remote whose tip must be reachable
#   AWAKENER_STALENESS_HEAD     (default HEAD)    the ref being judged
#   AWAKENER_STALENESS_UNKNOWN  (default fail)    `fail` -> UNKNOWN exits 2; `warn` -> UNKNOWN
#                                                 prints the same line and exits 0, for a caller
#                                                 that wants the report without the gate. The
#                                                 default is `fail` because a caller that cannot
#                                                 tell "checked" from "could not check" is the
#                                                 defect; `warn` never prints `current`, so it
#                                                 downgrades the gate and never the claim.
#
# Written for POSIX sh, no `set -e`: every failure here is a result to report, not a reason to
# fall over silently.
set -u

REMOTE=${AWAKENER_STALENESS_REMOTE:-origin}
BASE=${AWAKENER_STALENESS_BASE:-main}
HEAD_REF=${AWAKENER_STALENESS_HEAD:-HEAD}
UNKNOWN_MODE=${AWAKENER_STALENESS_UNKNOWN:-fail}
DIR=''

usage() {
    sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'
}

# Printed on stdout, because stdout is what a caller reads back and the reason is part of the
# result. A bad UNKNOWN_MODE exits 2 whatever the mode says, since an invalid switch value must not
# be able to suppress the report of itself.
unknown() {
    printf 'UNKNOWN: %s\n' "$1"
    if [ "$UNKNOWN_MODE" = warn ]; then
        exit 0
    fi
    exit 2  # the status that means "could not check"
}

case $UNKNOWN_MODE in
    fail|warn) ;;
    *)
        printf 'UNKNOWN: AWAKENER_STALENESS_UNKNOWN must be "fail" or "warn", got "%s"\n' \
            "$UNKNOWN_MODE"
        exit 2
        ;;
esac

while [ $# -gt 0 ]; do
    case $1 in
        -C|--base|--head|--remote)
            if [ $# -lt 2 ]; then
                unknown "$1 needs a value"
            fi
            case $1 in
                -C)       DIR=$2 ;;
                --base)   BASE=$2 ;;
                --head)   HEAD_REF=$2 ;;
                --remote) REMOTE=$2 ;;
            esac
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) unknown "unrecognised argument '$1'" ;;
    esac
done

command -v git >/dev/null 2>&1 || unknown "git is not on PATH"

if [ -n "$DIR" ]; then
    cd "$DIR" 2>/dev/null || unknown "cannot enter directory '$DIR'"
fi

git rev-parse --git-dir >/dev/null 2>&1 || unknown "not inside a git repository (cwd $(pwd))"

git remote get-url "$REMOTE" >/dev/null 2>&1 || unknown "no remote named '$REMOTE' is configured"

fetch_err=$(git fetch "$REMOTE" 2>&1 >/dev/null)
fetch_rc=$?
if [ "$fetch_rc" -ne 0 ]; then
    unknown "git fetch $REMOTE exited $fetch_rc: $(printf '%s' "$fetch_err" | tr '\n' ' ')"
fi

git rev-parse --verify --quiet "$HEAD_REF^{commit}" >/dev/null 2>&1 ||
    unknown "'$HEAD_REF' does not resolve to a commit (unborn branch?)"
git rev-parse --verify --quiet "$REMOTE/$BASE^{commit}" >/dev/null 2>&1 ||
    unknown "'$REMOTE/$BASE' does not resolve to a commit, even after the fetch succeeded"

git merge-base --is-ancestor "$REMOTE/$BASE" "$HEAD_REF" >/dev/null 2>&1
ancestry_rc=$?
case $ancestry_rc in
    0) printf 'current\n'; exit 0 ;;
    1) printf 'STALE\n'; exit 1 ;;
    # Neither yes nor no. The old idiom folded this into `no`, which is the defect in miniature.
    *) unknown "git merge-base --is-ancestor exited $ancestry_rc, which is neither yes nor no" ;;
esac
