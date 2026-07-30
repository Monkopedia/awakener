# Probe: dock-as-tree-sibling under sway (Test 2, structural half)

**Date:** 2026-07-30 · **sway:** 1.12 · **host:** kaladin (headless) · **status:** core claim
confirmed, three hazards found

Run against a real sway on the headless wlroots backend — no display, no seat, driven
entirely over IPC. Everything below is structural (`get_tree` JSON), so it needs no GPU and
no change to a running desktop session:

```sh
export XDG_RUNTIME_DIR=/run/user/1000
export SWAYSOCK=/tmp/awakener-probe/sway-ipc.sock     # sway honors SWAYSOCK for creation
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 \
  sway -c probe.conf                                  # probe.conf: xwayland disable
```

This is the recommended shape for all structural probes: reproducible, unattended, and
runnable from a machine with no display stack at all.

## Confirmed: the dock is a tree sibling *inside* the tab

With workspace layout `tabbed`, focusing the app, issuing `splith`, and launching the dock
into it:

```
workspace(tabbed) id=4
  con(splith) id=7          <- this IS tab 1
    con id=5 app_id=aw-app1
    con id=8 app_id=aw-dock1 marks=['dock_5']
  con id=6 app_id=aw-app2   <- tab 2
```

The tabbed workspace still has exactly two children. `[app | dock]` lives inside one tab and
the dock is never a sibling *of* the tab. Native tree behavior, exactly as the design brief
claims. Criteria-driven focus (`[con_mark="^dock_"] focus`) works, so marks are a sufficient
handle for `resolve()` to exclude docks.

## Hazard 1 — tab switching lands in the dock

`focus left` from tab 2 into tab 1 landed on **the dock**, not the app, because the split
container remembers the dock as last-focused. Plain `focus right` from the app also walks
into the dock. sway has no built-in "skip this node in navigation".

This is the same failure class as Open #5 (dock swap race) and deserves the same ruling —
correctness, not polish. A tab switch that silently puts keystrokes into the agent panel is
exactly the failure the brief wants designed out.

**Lever found:** focus memory is per-container-last-focused, and it is decisive. Leaving the
app focused inside the tab makes `focus left` land on the app every time. So the rule is
*whenever dock interaction ends, refocus the app* — the dock must never be the container's
resting focus.

**Open question this raises:** if the user switches tabs *while typing in the dock*, should
returning land in the dock or the app? Both are defensible and it's a UX call, not a
technical one.

## Hazard 2 — the dock outlives its app

Killing the bound app left the split container standing with the dock as its only child:

```
con(splith) id=7
  con id=8 app_id=aw-dock1 marks=['dock_5']
```

A tab that looks like a Drab but is actually an orphaned Lifeless panel. Dock teardown must
be driven explicitly off the app's `close` event.

## Hazard 3 — the split container leaks and swallows new windows

The worse version of the same root cause. sway does **not** collapse a single-child split
container. After the dock closed, `con(splith) id=9` persisted, and the *next* app launched
landed inside it:

```
con(splith) id=9
  con id=6 app_id=aw-app2
  con id=11 app_id=aw-app3    <- swallowed into tab 2
```

Tab 2 now holds two surfaces and no dock. So `attach()` must own the split container's whole
lifecycle — create on attach, normalize on detach — or the tree degrades as windows come and
go.

## Confirmed: `no_focus` solves dock-steals-focus on map

`no_focus [app_id="aw-dock2"]` is declarative and works — the dock mapped without taking
focus. Right behavior for a dock created proactively for a surface. Note the hotkey-invoked
case wants the opposite (you're about to type to the agent), so focus-on-map is a per-invocation
decision, not a global config.

## Confirmed: change notification

Subscribing to `window` events yields `new` / `close` / `title` / `focus` with container ids
— sufficient for the interface's third call, and `close` is precisely the teardown trigger
Hazards 2 and 3 need.

## Implication for the three-call interface

`resolve` / `attach` / change-notification do not cover **focus**, and Hazard 1 shows focus
policy has to live below the compositor-agnostic line — it is expressed in sway-specific
criteria commands and driven by sway-specific focus-memory semantics. Either `attach()`
owns focus discipline as an invariant it maintains, or the interface grows a fourth call.
Worth deciding deliberately rather than letting focus handling leak upward, given the working
agreement to keep the interface at three calls.
