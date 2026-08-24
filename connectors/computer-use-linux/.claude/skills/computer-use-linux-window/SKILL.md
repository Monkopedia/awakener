---
name: computer-use-linux-window
description: Use when asked to reach the bound window through AT-SPI or through the computer-use-linux MCP server. Explains why no tool is wired, what a safe proxy would have to do, and what not to reach for instead. Provisional.
---

# AT-SPI reach for this window — audited, and not wired

**There is no MCP server declared by this connector.** If you were expecting one, this page is
the answer. Awakener bound you to a window and wrote down what it knows about it; it did not give
you a way to drive that window, and the reason is a design constraint rather than a missing
install.

**Do not install `agent-sh/computer-use-linux` to make this work.** Do not substitute another
desktop-driving tool. Report what you were asked to do and stop.

## What you were told about the window

`.awakener/surface.json`, in your working directory:

| field | value here |
| --- | --- |
| `pid` | `{pid}` |
| `title` | (read it from the file) |
| `app_id` | `{app_id}` |
| `surface_id` | `{surface_id}` |

**Read `title` and `app_id` from the file, not from this page.** Both are chosen by the program
that owns the window, so neither may be pasted into a shell command; in the file they are
JSON-escaped.

## Why the obvious wiring is the wrong wiring

From the 2026-08-23 audit of `agent-sh/computer-use-linux` (Rust, MIT), every finding
demonstrated live rather than read off documentation:

- **It cannot be scoped to one window.** Not a missing flag — absent from the data model.
  `get_app_state(pid)` against two Chromium windows sharing one pid returned a single 472-node
  tree containing **both** frames. `select_roots()` filters AT-SPI *application* roots, and frames
  are children of those, so there is no level at which a frame filter could apply.
- **The acting tools take no window parameter at all.** `perform_action`, `set_value` and `drag`
  resolve against a server-wide `last_nodes` cache. Demonstrated: an agent that had only ever
  looked at one window successfully clicked a button in another. `element_identifier` is passed
  to the a11y bus **unvalidated**, which makes it a capability to act on any AT-SPI object in any
  application in the session.
- **Its window backend does not work on sway.** It reads `I3SOCK` and globs i3's socket path
  (sway uses `SWAYSOCK`), and its parser starts from the **X11 window id**, which is null for
  every native Wayland view — so `foot` and every other Wayland client is silently dropped.
- **`doctor` is not an acceptance test for it.** Pointed at sway's socket it reports
  `i3: ok, can_list_windows: true` while the very next call returns `"i3 returned no windows"`:
  the probe only checks that stdout parses as *some* JSON object and never runs the real parser.
  If you ever test this thing, test with `list_windows`.

Awakener does not want its windowing anyway — `:wm` already owns sway. The half that is wanted is
AT-SPI, and that is precisely the half with no scoping.

## What a wired version would have to be

A **proxy**, never a pass-through. Recorded here so the next person does not have to re-derive
it:

1. **One server process per Lifeless.** The `last_nodes` cache is server-wide and unkeyed, so two
   agents against one server trample each other's element indices.
2. **Hand the agent only its own subtree.** The tree comes back with `parent_index` and frames sit
   at depth 1, so matching this window's title to a frame and taking its descendants is
   straightforward — reindexed, so an identifier outside it is not even expressible.
3. **Never expose `perform_action`, `set_value` or selector-based `click` directly.** Accept only
   `element_identifier` values awakener has itself confirmed lie inside that subtree.
4. **Never call `setup_window_targeting`, and never install the bundled GNOME Shell extension.**
   It owns a session-bus name with no sender check, which exposes every window's title, app_id,
   pid and geometry — and focus, move and resize — to any local process.
5. **Keystrokes are a race, and the race must be named rather than hidden.** Focus the window
   through `:wm`, then `type_text` with no window target. Any focus change between the two lands
   the keystrokes somewhere else.

Integration would be a binary over stdio — `computer-use-linux mcp` speaks JSON-RPC on
stdin/stdout — so no Rust linking and no cargo in awakener's build. The audit's own closing point
is worth keeping in view: a few hundred lines of Kotlin AT-SPI over D-Bus is a real alternative in
a project that already hand-rolls i3 IPC.

## The join, for whichever of those arrives

AT-SPI models the desktop as **`application → frame`**. Awakener can tell you the pid, and the pid
is the *application*. Two separate processes gave two pids and matched their a11y applications
exactly; one process with several windows will not — all eight windows of one Chromium instance
share a pid.

1. Find the a11y application whose pid is `{pid}`.
2. Enumerate its frames.
3. Match the frame whose title equals the `title` in `surface.json`.

> **If zero or more than one frame remains, stop and tell the user.** Do not pick the first, do
> not pick the focused one, do not fall back to the application node and act on "the window".

Awakener supplies no geometry, so extents cannot be used as a tie-break. That is a known gap, not
a thing to work around by guessing. Acting on the wrong window is the one failure the user cannot
detect afterwards, and this is the connector that would *actuate* it: a wrong join here clicks in
somebody else's window.
