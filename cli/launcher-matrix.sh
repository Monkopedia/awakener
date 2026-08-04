#!/bin/sh
# The launcher's test suite. Run by `gradlew :cli:launcherTest`, which `check` depends on.
#
# The launcher generated in `build.gradle.kts` is what a compositor `exec`s on every hotkey
# press, and it was the one artifact here with no automated coverage: three defects in three
# review rounds, every one found by a human running it by hand. This exercises the *shipped*
# scripts under `build/install/awakener/bin` rather than the template that produced them,
# because two of those three defects were about how the generated file behaves rather than
# about how it reads.
#
#   launcher-matrix.sh <install-dir> <good-java> <work-dir> <report>
#
# <good-java> is a real JDK 21+ (the build's own toolchain). Everything older, everything that
# is not a JVM at all, and everything absent is stubbed: the launcher decides from `java
# -version` text, so a stub is a faithful stand-in and the matrix needs no second JDK installed
# on the host or on CI. The stubs forward every argv but `-version` to the real JVM, so a stub
# that *passes* the check still runs the program and the row is not vacuous.
set -eu

INSTALL=$(CDPATH= cd -P -- "$1" && pwd)
GOOD_JAVA=$2
WORK=$3
REPORT=$4

rm -rf "$WORK"
mkdir -p "$WORK"
WORK=$(CDPATH= cd -P -- "$WORK" && pwd)

run_count=0
fail_count=0
case $REPORT in */*) mkdir -p "${REPORT%/*}" ;; esac
: >"$REPORT"

note() {
    printf '%s\n' "$1" | tee -a "$REPORT"
}

detail() {
    printf '%s\n' "$1" | head -5 | sed 's/^/      | /' | tee -a "$REPORT" >/dev/null
}

die() {
    note "launcher-matrix: $1"
    exit 1
}

# ------------------------------------------------------------------ stub JVMs
#
# `-version` answers with a recorded banner; every other argv is the real JVM.
mkjvm() {
    dir=$WORK/jvm/$1
    mkdir -p "$dir/bin"
    {
        echo '#!/bin/sh'
        echo "REAL='$GOOD_JAVA'"
        echo "BANNER='$2'"
        cat <<'EOF'
for arg in "$@"; do
    if [ "$arg" = "-version" ]; then
        printf '%s\n' "$BANNER" >&2
        exit 0
    fi
done
exec "$REAL" "$@"
EOF
    } >"$dir/bin/java"
    chmod +x "$dir/bin/java"
}

mkjvm java8 'openjdk version "1.8.0_502"
OpenJDK Runtime Environment (build 1.8.0_502-b07)'
mkjvm java11 'openjdk version "11.0.22" 2024-01-16'
mkjvm java17 'openjdk version "17.0.9" 2023-10-17'
mkjvm java21 'openjdk version "21.0.12" 2026-07-21'
mkjvm java26 'openjdk version "26" 2026-03-17'
# The shape an ambient JAVA_TOOL_OPTIONS gives a real JVM. The JDK below does this for itself
# and the matrix asserts that it does, but pinning the shape here keeps the row meaningful if a
# future JDK ever stops.
mkjvm java21banner 'Picked up JAVA_TOOL_OPTIONS: -Xmx512m
openjdk version "21.0.12" 2026-07-21'

mkdir -p "$WORK/jvm/notjvm/bin" "$WORK/jvm/chatty/bin"
printf '#!/bin/sh\nexit 0\n' >"$WORK/jvm/notjvm/bin/java"
printf '#!/bin/sh\necho hello\nexit 0\n' >"$WORK/jvm/chatty/bin/java"
chmod +x "$WORK/jvm/notjvm/bin/java" "$WORK/jvm/chatty/bin/java"

# ------------------------------------------------------------------ install trees
#
# The path the launchers bake in, read back out of a shipped script rather than passed in, so a
# change to how it is embedded fails here instead of quietly disabling the mutated trees below.
BUILD_JAVA=$(sed -n "s/^elif \[ -x '\(.*\)' \]; then\$/\1/p" "$INSTALL/bin/awakener-config")
[ -n "$BUILD_JAVA" ] || die "could not read the baked-in build JDK out of the shipped launcher"

# `real` is the shipped tree. The other two exist because the build-JDK and PATH branches are
# unreachable while the baked-in path resolves, which on a working host it always does. Each
# mutated tree asserts the substitution landed *and* that the original path did not survive, so
# a row cannot silently end up testing the branch above the one it names.
mktree() {
    dir=$WORK/tree/$1
    mkdir -p "$dir/bin"
    ln -s "$INSTALL/lib" "$dir/lib"
    for script in "$INSTALL"/bin/*; do
        base=${script##*/}
        if [ -z "$2" ]; then
            cp "$script" "$dir/bin/$base"
        else
            sed "s|$BUILD_JAVA|$2|g" "$script" >"$dir/bin/$base"
            grep -q "$2" "$dir/bin/$base" || die "tree $1: substitution did not land in $base"
            if grep -q "$BUILD_JAVA" "$dir/bin/$base"; then
                die "tree $1: the real build JDK survived in $base"
            fi
        fi
        chmod +x "$dir/bin/$base"
    done
}

mktree real ''
mktree nobuild "$WORK/jvm/absent/bin/java"
mktree badbuild "$WORK/jvm/java8/bin/java"

REAL=$WORK/tree/real/bin
NOBUILD=$WORK/tree/nobuild/bin
BADBUILD=$WORK/tree/badbuild/bin

# ------------------------------------------------------------------ the harness
#
# HOME and every XDG root point inside the work dir: `awakener-registry` opens a bindings file
# and `SpanreedCli` reads `~/.claude/spanreed`, and a test of a shell script has no business
# reaching either.
HOME=$WORK/home
XDG_CONFIG_HOME=$HOME/.config
XDG_STATE_HOME=$HOME/.local/state
XDG_DATA_HOME=$HOME/.local/share
export HOME XDG_CONFIG_HOME XDG_STATE_HOME XDG_DATA_HOME
mkdir -p "$XDG_CONFIG_HOME" "$XDG_STATE_HOME" "$XDG_DATA_HOME"

# PATH is built rather than inherited, so a row that means "no java anywhere" means it. It holds
# exactly the utilities the launcher shells out to — which makes it a standing assertion about
# what the launcher is allowed to depend on: add one and the matrix goes red until it is listed.
BARE_PATH=$WORK/bin
mkdir -p "$BARE_PATH"
# `command -v` answers with a bare name for a shell builtin — `printf` is one in dash, bash and
# busybox — and linking that to itself makes a cycle, which is fatal to anything that later walks
# the build directory. A builtin needs no entry here: every shell that could run the launcher
# already has it.
link_tool() {
    resolved=$(command -v "$1" 2>/dev/null) || return 1
    case $resolved in
        /*) ln -s "$resolved" "$BARE_PATH/$1" ;;
        *) ;;
    esac
}
for tool in ls sed head cut printf; do
    link_tool "$tool" || die "no $tool available to build the harness with"
done
for shell in dash busybox; do
    link_tool "$shell" || continue
done

GOOD_HOME=${GOOD_JAVA%/bin/java}
[ -x "$GOOD_HOME/bin/java" ] || die "good java '$GOOD_JAVA' is not <home>/bin/java"

# check <label> <expected exit> <expected output substring> -- <env assignments> -- <argv>
#
# The substring is matched against stdout and stderr together, and it is not optional: an exit
# code alone does not distinguish "blocked, naming the branch the user has to fix" from
# "blocked". A substring written `!text` requires its *absence* instead. Env assignments then
# argv is `env`'s own calling convention, so the two lists are handed straight to it once the
# markers are dropped.
check() {
    label=$1
    want_exit=$2
    want_text=$3
    shift 3
    [ "$1" = "--" ] || die "check: malformed row '$label'"
    shift

    remaining=$#
    while [ "$remaining" -gt 0 ]; do
        arg=$1
        shift
        remaining=$((remaining - 1))
        [ "$arg" = "--" ] || set -- "$@" "$arg"
    done

    run_count=$((run_count + 1))
    set +e
    got=$(
        env -u AWAKENER_JAVA -u JAVA_HOME -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS \
            "PATH=$BARE_PATH" "HOME=$HOME" \
            "XDG_CONFIG_HOME=$XDG_CONFIG_HOME" "XDG_STATE_HOME=$XDG_STATE_HOME" \
            "XDG_DATA_HOME=$XDG_DATA_HOME" \
            "$@" 2>&1
    )
    rc=$?
    set -e

    if [ "$rc" != "$want_exit" ]; then
        note "FAIL $label: exit $rc, wanted $want_exit"
        detail "$got"
        fail_count=$((fail_count + 1))
        return 0
    fi
    case $want_text in
        '!'*)
            case $got in
                *"${want_text#!}"*)
                    note "FAIL $label: exit $rc as wanted, but the output contains '${want_text#!}'"
                    detail "$got"
                    fail_count=$((fail_count + 1))
                    ;;
                *) note "ok   $label" ;;
            esac
            ;;
        *)
            case $got in
                *"$want_text"*) note "ok   $label" ;;
                *)
                    note "FAIL $label: exit $rc as wanted, but the output lacks '$want_text'"
                    detail "$got"
                    fail_count=$((fail_count + 1))
                    ;;
            esac
            ;;
    esac
}

# The regression the JAVA_TOOL_OPTIONS rows exist for is only reachable if the real JVM does
# print the banner. If a future JDK stops, those rows would pass while testing nothing, and
# silently vacuous coverage is worse than none.
banner_line=$(JAVA_TOOL_OPTIONS=-Xmx512m "$GOOD_JAVA" -version 2>&1 | head -1)
case $banner_line in
    "Picked up "*) ;;
    *) die "expected '$GOOD_JAVA' to print a 'Picked up' banner under JAVA_TOOL_OPTIONS, got: $banner_line" ;;
esac

note "launcher-matrix against $INSTALL"
note "  good JDK:           $GOOD_JAVA"
note "  baked-in build JDK: $BUILD_JAVA"

# --- a good JDK is accepted on every branch, and the program actually runs -------------------
note ""
note "# a good JDK runs, on all four branches"
check "good/AWAKENER_JAVA" 0 "config.flags.discovery" \
    -- "AWAKENER_JAVA=$GOOD_JAVA" -- "$REAL/awakener-config" list
check "good/JAVA_HOME" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$REAL/awakener-config" list
check "good/buildJDK" 0 "config.flags.discovery" \
    -- -- "$REAL/awakener-config" list
check "good/PATH" 0 "config.flags.discovery" \
    -- "PATH=$GOOD_HOME/bin:$BARE_PATH" -- "$NOBUILD/awakener-config" list

# --- round 3: an ambient JAVA_TOOL_OPTIONS must not reject a good JDK ------------------------
note ""
note "# an ambient JAVA_TOOL_OPTIONS / _JAVA_OPTIONS does not reject a good JDK (round 3)"
check "banner/JAVA_HOME" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" "JAVA_TOOL_OPTIONS=-Xmx512m" -- "$REAL/awakener-config" list
check "banner/AWAKENER_JAVA via _JAVA_OPTIONS" 0 "config.flags.discovery" \
    -- "AWAKENER_JAVA=$GOOD_JAVA" "_JAVA_OPTIONS=-Xmx512m" -- "$REAL/awakener-config" list
check "banner/buildJDK" 0 "config.flags.discovery" \
    -- "JAVA_TOOL_OPTIONS=-Xmx512m" -- "$REAL/awakener-config" list
check "banner/PATH" 0 "config.flags.discovery" \
    -- "PATH=$GOOD_HOME/bin:$BARE_PATH" "JAVA_TOOL_OPTIONS=-Xmx512m" \
    -- "$NOBUILD/awakener-config" list
check "banner/pinned banner shape" 0 "config.flags.discovery" \
    -- "AWAKENER_JAVA=$WORK/jvm/java21banner/bin/java" -- "$REAL/awakener-config" list
check "banner/an old JDK behind a banner still blocks" 70 "is 1.8.0_502" \
    -- "JAVA_HOME=$WORK/jvm/java8" "JAVA_TOOL_OPTIONS=-Xmx512m" -- "$REAL/awakener-config" list

# --- round 2: the check runs on every branch, not only on PATH -------------------------------
note ""
note "# an old JDK is blocked on all four branches, and the message names the branch (round 2)"
check "old/AWAKENER_JAVA" 70 "AWAKENER_JAVA=$WORK/jvm/java8/bin/java is 1.8.0_502" \
    -- "AWAKENER_JAVA=$WORK/jvm/java8/bin/java" -- "$REAL/awakener-config" list
check "old/JAVA_HOME" 70 "JAVA_HOME=$WORK/jvm/java8 is 1.8.0_502" \
    -- "JAVA_HOME=$WORK/jvm/java8" -- "$REAL/awakener-config" list
check "old/buildJDK" 70 "the JDK this was built with is 1.8.0_502" \
    -- -- "$BADBUILD/awakener-config" list
check "old/PATH" 70 "the java on PATH is 1.8.0_502" \
    -- "PATH=$WORK/jvm/java8/bin:$BARE_PATH" -- "$NOBUILD/awakener-config" list
check "old/11" 70 "is 11.0.22" \
    -- "AWAKENER_JAVA=$WORK/jvm/java11/bin/java" -- "$REAL/awakener-config" list
check "old/17" 70 "is 17.0.9" \
    -- "AWAKENER_JAVA=$WORK/jvm/java17/bin/java" -- "$REAL/awakener-config" list
check "boundary/21 runs" 0 "config.flags.discovery" \
    -- "AWAKENER_JAVA=$WORK/jvm/java21/bin/java" -- "$REAL/awakener-config" list
check "boundary/26 runs" 0 "config.flags.discovery" \
    -- "AWAKENER_JAVA=$WORK/jvm/java26/bin/java" -- "$REAL/awakener-config" list

# --- round 2: not a JVM at all, and not there at all -----------------------------------------
note ""
note "# a non-JVM and an absent path are blocked rather than silently doing nothing (round 2)"
check "notjvm/AWAKENER_JAVA=/bin/true" 70 "not a JVM" \
    -- "AWAKENER_JAVA=/bin/true" -- "$REAL/awakener-config" list
check "notjvm/silent stub" 70 "not a JVM" \
    -- "AWAKENER_JAVA=$WORK/jvm/notjvm/bin/java" -- "$REAL/awakener-config" list
check "notjvm/chatty stub" 70 "not a JVM" \
    -- "AWAKENER_JAVA=$WORK/jvm/chatty/bin/java" -- "$REAL/awakener-config" list
check "notjvm/JAVA_HOME" 70 "not a JVM" \
    -- "JAVA_HOME=$WORK/jvm/notjvm" -- "$REAL/awakener-config" list
check "notjvm/PATH" 70 "not a JVM" \
    -- "PATH=$WORK/jvm/notjvm/bin:$BARE_PATH" -- "$NOBUILD/awakener-config" list
check "absent/AWAKENER_JAVA" 70 "not a JVM" \
    -- "AWAKENER_JAVA=$WORK/jvm/absent/bin/java" -- "$REAL/awakener-config" list
check "absent/JAVA_HOME" 70 "not a JVM" \
    -- "JAVA_HOME=$WORK/jvm/absent" -- "$REAL/awakener-config" list
check "absent/no java anywhere" 70 "not a JVM" \
    -- -- "$NOBUILD/awakener-config" list

# --- round 2: invocation through a symlink ---------------------------------------------------
note ""
note "# invocation through a symlink still finds lib/ (round 2)"
mkdir -p "$WORK/links/a" "$WORK/links/b" "$WORK/links/with space" "$WORK/links/onpath"
ln -s "$REAL/awakener-config" "$WORK/links/absolute"
ln -s "$WORK/links/absolute" "$WORK/links/a/chained"
ln -s "../a/chained" "$WORK/links/b/relative"
ln -s "$WORK/links/b/relative" "$WORK/links/with space/spaced"
ln -s "../b/relative" "$WORK/links/onpath/awakener-config"
ln -s "$REAL" "$WORK/links/bindir"

check "symlink/absolute" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$WORK/links/absolute" list
check "symlink/chained" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$WORK/links/a/chained" list
check "symlink/relative across directories" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$WORK/links/b/relative" list
check "symlink/space in the link's directory" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$WORK/links/with space/spaced" list
check "symlink/found on PATH, through a chain" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" "PATH=$WORK/links/onpath:$BARE_PATH" -- awakener-config list
# The bin *directory* is the link here, so the [ -h ] loop never fires: this is `cd -P`'s row.
check "symlink/bin directory" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$WORK/links/bindir/awakener-config" list
# The fixes have to compose: resolving through a link must not lose the version check.
check "symlink/+ an old JDK still blocks" 70 "JAVA_HOME=$WORK/jvm/java8 is 1.8.0_502" \
    -- "JAVA_HOME=$WORK/jvm/java8" -- "$WORK/links/b/relative" list
check "symlink/+ a non-JVM still blocks" 70 "not a JVM" \
    -- "AWAKENER_JAVA=/bin/true" -- "$WORK/links/bindir/awakener-config" list

# --- every entry point ships, names itself, and finds its own main class ---------------------
note ""
note "# all three launchers reach their own main class, and name themselves when they block"
check "entry/awakener-config runs" 0 "config.flags.discovery" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$REAL/awakener-config" list
check "entry/awakener-config blocks" 70 "awakener-config: needs Java 21 or newer" \
    -- "JAVA_HOME=$WORK/jvm/java8" -- "$REAL/awakener-config" list
check "entry/awakener-invoke runs" 2 "usage: awakener-invoke" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$REAL/awakener-invoke" not-a-command
check "entry/awakener-invoke blocks" 70 "awakener-invoke: needs Java 21 or newer" \
    -- "JAVA_HOME=$WORK/jvm/java8" -- "$REAL/awakener-invoke" list
check "entry/awakener-registry runs" 0 "no surfaces bound" \
    -- "JAVA_HOME=$GOOD_HOME" -- "$REAL/awakener-registry" list
check "entry/awakener-registry blocks" 70 "awakener-registry: needs Java 21 or newer" \
    -- "JAVA_HOME=$WORK/jvm/java8" -- "$REAL/awakener-registry" list

# --- what the classpath is for: every entry point sees every module's flags (#45) ------------
#
# The launchers exist so all three `main`s run off `:cli`'s classpath, which is what lets flag
# discovery see the other modules. A process is the only place that can be checked: the flag
# registry is global and eager, so a JVM test that ran discovery once cannot then observe an
# entry point that skips it.
note ""
note "# every entry point honours another module's flags rather than calling them unknown (#45)"
mkdir -p "$WORK/cfg/awakener"
cat >"$WORK/cfg/awakener/config.json" <<'EOF'
{
  "wm.dock.size_ppt": 55,
  "config.flags.discovery": "CLASSPATH",
  "registry.agent.name_prefix": "matrix-"
}
EOF
check "flags/awakener-config applies them" 0 "wm.dock.size_ppt" \
    -- "JAVA_HOME=$GOOD_HOME" "XDG_CONFIG_HOME=$WORK/cfg" -- "$REAL/awakener-config" list
check "flags/awakener-config does not call them unknown" 0 '!no flag declares this key' \
    -- "JAVA_HOME=$GOOD_HOME" "XDG_CONFIG_HOME=$WORK/cfg" -- "$REAL/awakener-config" list
check "flags/awakener-registry does not call them unknown" 0 '!no flag declares this key' \
    -- "JAVA_HOME=$GOOD_HOME" "XDG_CONFIG_HOME=$WORK/cfg" -- "$REAL/awakener-registry" list
check "flags/awakener-invoke does not call them unknown" 2 '!no flag declares this key' \
    -- "JAVA_HOME=$GOOD_HOME" "XDG_CONFIG_HOME=$WORK/cfg" -- "$REAL/awakener-invoke" not-a-command
# And the reporting is real rather than absent: a key nothing declares still gets named.
mkdir -p "$WORK/cfg-bad/awakener"
printf '{"wm.dock.nonexistent": 1}\n' >"$WORK/cfg-bad/awakener/config.json"
check "flags/awakener-registry names a key nothing declares" 0 "wm.dock.nonexistent" \
    -- "JAVA_HOME=$GOOD_HOME" "XDG_CONFIG_HOME=$WORK/cfg-bad" -- "$REAL/awakener-registry" list

# --- under other POSIX shells ----------------------------------------------------------------
#
# /bin/sh is bash on both hosts, and the launcher leans on parameter expansions bash is lenient
# about. Re-running a few rows under a genuinely small shell is what makes the shebang honest.
note ""
note "# the same rows under other POSIX shells"
for alt in dash "busybox sh"; do
    if [ ! -e "$BARE_PATH/${alt%% *}" ]; then
        note "skip $alt (not installed)"
        continue
    fi
    check "$alt/good" 0 "config.flags.discovery" \
        -- "JAVA_HOME=$GOOD_HOME" -- $alt "$REAL/awakener-config" list
    check "$alt/symlink" 0 "config.flags.discovery" \
        -- "JAVA_HOME=$GOOD_HOME" -- $alt "$WORK/links/b/relative" list
    check "$alt/an old JDK blocks" 70 "is 1.8.0_502" \
        -- "JAVA_HOME=$WORK/jvm/java8" -- $alt "$REAL/awakener-config" list
    check "$alt/banner" 0 "config.flags.discovery" \
        -- "JAVA_HOME=$GOOD_HOME" "JAVA_TOOL_OPTIONS=-Xmx512m" \
        -- $alt "$REAL/awakener-config" list
done

note ""
note "launcher-matrix: $run_count rows, $fail_count failed"
[ "$fail_count" -eq 0 ] || exit 1
