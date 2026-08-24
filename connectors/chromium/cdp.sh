#!/bin/sh
# Rendered by awakener for surface {surface_id}, connector {connector}. Overwritten on every
# invocation — edit {connector_dir}/cdp.sh, not this copy.
#
# Chrome DevTools Protocol discovery over the HTTP endpoints, which is all this does. Driving a
# page needs the WebSocket, and that is `:chrome`'s job when `:chrome` exists. Identifying *which*
# target is this window is the part that has to be right first, and it is the part below.
#
# The pid is substituted inline below because it is numeric. The title deliberately is NOT: a
# window title is chosen by the program that owns the window, so pasting one into a shell script
# is an injection. It is read from surface.json at runtime, where it is JSON-escaped.
#
# (Written without the placeholders spelled out, because this comment is itself rendered — naming
# them here would have substituted this window's pid and title into the sentence explaining why
# one of them must not be substituted. It did, on the first live run.)
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
facts="$here/.awakener/surface.json"
pid={pid}

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "cdp.sh: needs '$1' and it is not on PATH" >&2
        exit 70
    }
}

refuse_no_port() {
    cat >&2 <<EOF
cdp.sh: pid $pid is not exposing a CDP endpoint that this window can be reached on.
  $1

  awakener did not spawn this browser, and there is no way to attach a debugging channel to a
  Chromium that is already running: --remote-debugging-pipe passes CDP over inherited file
  descriptors, which requires being the parent, and --remote-debugging-port is only read at
  startup. See docs/findings/2026-08-23-chromium-cdp-pipe.md.

  This connector does NOT attach to an arbitrary listening port. Tell the user the window has to
  be relaunched under a dedicated debugging profile, and let them decide:

    chromium --user-data-dir=~/.local/share/awakener/chromium-profile \\
             --remote-debugging-port=9222 --app=<url>

  Never point this at their everyday profile: anything that can reach that port can drive a
  logged-in browser.
EOF
    exit 1
}

# The port THIS window's browser is actually on, established from the browser itself.
#
# Two sources, and both are needed — measured on Chromium 151.0.7922.173, not assumed:
#
#  - **`DevToolsActivePort` in the profile.** Written by the browser *after* it has bound, so it
#    names the port it is on rather than the port it was asked for. It is also the **only**
#    answer for `--remote-debugging-port=0`, which is the shape a supervisor should prefer:
#    launched with `=0` the file appeared holding `39247` and the browser websocket path.
#  - **`--remote-debugging-port=` on the command line.** Chromium 151 writes **no**
#    `DevToolsActivePort` at all when given an explicit port — checked directly against a live
#    browser that was demonstrably serving CDP on 9333 — so a check that consulted only the file
#    would refuse every explicitly-ported browser there is.
#
# The profile comes from `--user-data-dir=` on the same pid, which is the pid the compositor
# reports for *this* window. That is what makes this an ownership proof rather than a guess: a
# listening 9222 that this pid did not open belongs to some other browser, and driving it would be
# worse than doing nothing. A browser with no `--user-data-dir` at all is the everyday profile and
# is refused outright.
#
# `AWAKENER_CDP_PORT` overrides, and is cross-checked rather than trusted: if it disagrees with
# what this window's browser reports, that is a refusal and not a choice.
#
# **/proc/<pid>/cmdline is NOT reliably NUL-separated here.** Chromium rewrites its own argv, so
# it comes back as one space-separated line — measured, after a per-argument `grep -x` against it
# found nothing on a browser launched with exactly the flag it was looking for. `tr` normalises
# both shapes, and the parsing below strips prefixes and suffixes rather than splitting words, so
# a profile path with a space in it survives.
discover_port() {
    [ -r "/proc/$pid/cmdline" ] ||
        refuse_no_port "There is no /proc/$pid — the window's process is gone."
    cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    case "$cmdline" in
        *--user-data-dir=*) : ;;
        *) refuse_no_port "It was launched with no --user-data-dir, so it is running on the
  user's everyday profile. This connector will not touch that." ;;
    esac
    profile=${cmdline#*--user-data-dir=}
    profile=${profile%% --*}

    requested=""
    case "$cmdline" in
        *--remote-debugging-port=*)
            requested=${cmdline#*--remote-debugging-port=}
            requested=${requested%% *}
            ;;
    esac

    bound=""
    if [ -r "$profile/DevToolsActivePort" ]; then
        bound=$(head -1 "$profile/DevToolsActivePort")
    fi

    # The file wins where there is one: it is what the browser bound, and the command line is
    # only what it was asked for.
    port=${bound:-$requested}
    case "$port" in
        '' | *[!0-9]* | 0)
            refuse_no_port "Its profile ($profile) holds no usable DevToolsActivePort and its
  command line names no --remote-debugging-port, so it is not serving CDP at all."
            ;;
    esac
    if [ -n "${AWAKENER_CDP_PORT:-}" ] && [ "$AWAKENER_CDP_PORT" != "$port" ]; then
        refuse_no_port "AWAKENER_CDP_PORT is $AWAKENER_CDP_PORT and this window's browser is on
  $port. Refusing rather than picking one: the other port is some other browser."
    fi
}

case "${1:-help}" in
    owner)
        discover_port
        echo "pid $pid is listening for CDP on $port (profile $profile)"
        ;;

    version)
        need curl
        discover_port
        curl -sS --max-time 5 "http://127.0.0.1:$port/json/version"
        ;;

    targets)
        need curl
        discover_port
        curl -sS --max-time 5 "http://127.0.0.1:$port/json/list"
        ;;

    match)
        # The join, and the whole reason this file exists. pid first — established by
        # discover_port, since every target on this port belongs to that browser — then title
        # within it.
        need curl
        need jq
        discover_port
        title=$(jq -r '.title // empty' "$facts")
        [ -n "$title" ] || {
            echo "cdp.sh: surface.json carries no title, so there is nothing to match on" >&2
            exit 3
        }
        targets=$(curl -sS --max-time 5 "http://127.0.0.1:$port/json/list")
        hits=$(printf '%s' "$targets" |
            jq --arg t "$title" '[.[] | select(.type == "page" and .title == $t)]')
        count=$(printf '%s' "$hits" | jq 'length')
        case "$count" in
            1)
                printf '%s' "$hits" | jq -r '.[0] | "\(.id)\t\(.url)\t\(.webSocketDebuggerUrl)"'
                ;;
            0)
                echo "cdp.sh: no page target titled '$title' on port $port." >&2
                echo "  The title may have changed since awakener acted. STOP and ask the user" >&2
                echo "  which page they mean; do not pick one." >&2
                exit 3
                ;;
            *)
                echo "cdp.sh: $count page targets share the title '$title'." >&2
                echo "  pid and title together do not identify this window. STOP and ask the" >&2
                echo "  user which one; do not pick the first." >&2
                printf '%s' "$hits" | jq -r '.[] | "  \(.id)\t\(.url)"' >&2
                exit 4
                ;;
        esac
        ;;

    *)
        echo "usage: ./cdp.sh [owner | version | targets | match]" >&2
        echo "  match is the one that matters: it resolves this window to exactly one CDP" >&2
        echo "  target, or refuses. Exit 3 = none, 4 = ambiguous, 70 = missing tool." >&2
        exit 2
        ;;
esac
