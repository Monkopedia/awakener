#!/bin/sh
# Rendered by awakener for surface {surface_id}, connector {connector}. Overwritten on every
# invocation — edit {connector_dir}/cdp.sh, not this copy.
#
# Chrome DevTools Protocol discovery over the HTTP endpoints, which is all this does. Driving a
# page needs the WebSocket, and that is `:chrome`'s job when `:chrome` exists. Identifying *which*
# target is this window is the part that has to be right first, and it is the part below.
#
# {pid} is substituted inline because it is numeric. {title} deliberately is NOT: a window title
# is chosen by the program that owns the window, so pasting one into a shell script is an
# injection. It is read from surface.json at runtime, where it is JSON-escaped.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
facts="$here/.awakener/surface.json"
pid={pid}
port="${AWAKENER_CDP_PORT:-9222}"

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "cdp.sh: needs '$1' and it is not on PATH" >&2
        exit 70
    }
}

# Does the process behind this window actually expose a debugging port, and the one we are about
# to talk to? Reading /proc/<pid>/cmdline needs no privilege and no extra tool, and it answers the
# question the port alone cannot: a listening 9222 might belong to any browser on this machine.
owner() {
    [ -r "/proc/$pid/cmdline" ] || {
        echo "cdp.sh: no /proc/$pid — the window's process is gone" >&2
        return 1
    }
    if tr '\0' '\n' < "/proc/$pid/cmdline" | grep -qx -- "--remote-debugging-port=$port"; then
        return 0
    fi
    cat >&2 <<EOF
cdp.sh: pid $pid was not launched with --remote-debugging-port=$port.

  awakener did not spawn this browser, and there is no way to attach a debugging channel to a
  Chromium that is already running: --remote-debugging-pipe passes CDP over inherited file
  descriptors, which requires being the parent, and --remote-debugging-port is only read at
  startup. See docs/findings/2026-08-23-chromium-cdp-pipe.md.

  This connector does NOT attach to an arbitrary listening port. Tell the user that the window
  has to be relaunched under a dedicated debugging profile, and let them decide:

    chromium --user-data-dir=~/.local/share/awakener/chromium-profile \\
             --remote-debugging-port=$port --app=<url>

  Never point this at their everyday profile: anything that can reach that port can drive a
  logged-in browser.
EOF
    return 1
}

case "${1:-help}" in
    owner)
        owner
        echo "pid $pid is listening for CDP on $port"
        ;;

    version)
        need curl
        owner
        curl -sS --max-time 5 "http://127.0.0.1:$port/json/version"
        ;;

    targets)
        need curl
        owner
        curl -sS --max-time 5 "http://127.0.0.1:$port/json/list"
        ;;

    match)
        # The join, and the whole reason this file exists. pid first — established by `owner`,
        # since every target on this port belongs to that process — then title within it.
        need curl
        need jq
        owner
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
