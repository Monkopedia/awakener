# Awakener — Design Brief

Orientation doc for a Claude Code session working on this repo. This captures decisions
already made and the reasoning behind them, so they don't get relitigated. Where something
is genuinely open, it says so.

---

## What this is

Persistent, per-surface agents bound to the windows on a Linux desktop. Every surface you
work in has an agent attached to it that holds the context you'd otherwise be holding in
your head. A hotkey brings it up, docked to the window it belongs to. Agents coordinate
with each other over spanreed.

The thing being offloaded is **not** the task. It's the accumulated model of *you* on that
surface — preferences, prior decisions, how you use this app — plus whatever it takes to
drive the app. Tasks churn; that residue persists.

**Vocabulary** (Warbreaker): an *Awakener* binds agents to surfaces. A *Lifeless* is one
agent bound to one surface. *Breath* is the resource spent to animate one. A *Command* is
its standing instruction. A *Drab* is a window with nothing attached. A *Returned* is the
ephemeral task coordinator.

---

## Layers

```
  Returned (ephemeral, per-task)      holds intent, spawns and dies with the task
        |
  spanreed bus                        existing; peer-to-peer, addressed inboxes
        |
  Lifeless (durable, per-surface)     personal model + app-driving capability
        |
  surface managers                    Waydroid, Chrome — multiplex many surfaces per host
        |
  WM layer                            binding, geometry, dock placement
```

### WM layer

Keep the interface **tiny** and compositor-agnostic:

- `resolve(surface) -> agent`
- `attach(surface, dock)`
- change notification (only multiplexed surfaces ever fire it)

First implementation is a **sway IPC client**. `get_tree`, window events, marks, and
criteria are enough to prototype the entire binding model without touching compositor code.

The dock is a **real window placed as a tree sibling inside the tab** — i3/sway's tree model
means a tab's contents can be a split container, so `[app | dock]` lives inside one tab and
the dock is never a sibling *of* the tab. This is native behavior, not a trick. Layer-shell
does not work for this: layer surfaces anchor to outputs, not windows.

Consequence: the dock is a genuine tree node, so all scripting needs marks/criteria to
exclude it from focus cycling and from `resolve()`, or it'll be mistaken for a surface
needing its own agent.

**Do not fork a compositor yet.** Chrome-grade tab dragging and a dock that isn't a window
both genuinely require compositor-side work, but none of the project risk lives there. Prove
the binding model over IPC first. If UI friction turns out to be the binding constraint,
write a small wlroots/smithay compositor against this interface — cleaner than carrying a
sway fork's rebase burden.

### Surface managers

Waydroid and Chrome are the same shape: a manager for a host that multiplexes many surfaces
behind fewer OS windows. They unify at the registry/bus layer and **diverge at the dock** —
Waydroid gives N windows for N agents (static binding, WM handles it); Chrome gives one
window whose bound agent changes underneath you (different widget, not a skin).

Bus integration follows spanreed's existing cross-host bridge pattern: managed agents don't
self-register, the manager mirrors them into the registry under its own PID, qualified
(`agent-X@chrome`), and forwards into their inboxes. Liveness falls out — manager dies,
managed agents vanish. No new bus primitives needed.

---

## Substrate decisions

**Waydroid for the long tail.** Android's accessibility layer dispatches actions to nodes
rather than to whatever holds the pointer, so control is genuinely out-of-band — this is the
answer to focus contention. Rouse Context already is an on-device Android MCP; a Waydroid
container is just another device to point it at, so a large part of the pairing layer exists.

**Chrome stays native.** Android Chrome has no extension support, so moving it inside
Waydroid costs the single best adapter available. Use CDP or an extension. If using
`--remote-debugging-port`, it wants a dedicated profile — anything that can reach localhost
can drive a logged-in browser.

**Chrome binds to origin, not window or tab.** What accumulates is "how Jason deals with
GitHub PRs," not anything about a tab you'll close in ten minutes. Hotkey on a tab resolves
origin → agent, spawning cold on first invocation. Lifetime is tab-scoped, memory is
origin-scoped: load the origin's distilled residue on spawn, write back on close. Tab close
becomes a natural distillation trigger.

Spawn on first **invocation**, not first visit — tab-creation as trigger would spawn
hundreds a day for tabs you glance at and close.

**Terminal is a tool, not an agent.** `tmux capture-pane -p -S -` returns full scrollback as
text on demand: no scraping, no injection, fully out-of-band. There's no durable personal
model to accumulate — it's pure retrieval — so any agent can just call it. (Bare terminal
emulators have no API; kitty and wezterm expose remote-control sockets.)

**Tabbed WM lineage.** sway/i3, or Hyprland + hy3. Hyprland's built-in groups are flat and
can't nest a split inside a tab, which is the property the dock depends on.

---

## Memory model

Split what an agent holds:

- **Durable** — preferences, decisions, learned app quirks. Belongs in a *written-down
  layer*, not an active context buffer. It then survives window close, transfers to a fresh
  agent, and is inspectable when the agent gets you wrong.
- **Perishable** — in-flight state, what you're mid-way through, what you just rejected.
  Lives in the session and dies with it.

Long-lived surfaces (email, browser) are exactly the ones whose windows never close, so
"lives as long as the window" is unbounded where it matters most. Distillation isn't a
workaround for context limits — it's what makes the agent a faithful offload. You don't
remember six weeks of a window either; you kept a compressed residue. An agent that retains
everything can't tell gist from noise.

---

## Not in v1

- **No "window content changed" event feed.** The pairing MCP is read-on-demand. Adding a
  push feed reintroduces the fan-out that spanreed avoids by construction, and then the real
  design problem becomes building a filter for it. The user is the primary trigger; bus
  messages are secondary.
- **No unattended autonomous action.** Agents wait. They don't poll, don't loop, don't act
  on a schedule.
- **No compositor fork.**
- **No cross-user / multi-tenant anything.** Single-user assumption, same as spanreed.

---

## Settled — don't relitigate

- Agents persist through idle. Session semantics is the point: it doesn't die, doesn't loop,
  it waits for the user or the bus. `claude -p` does not have this property.
- Idle agents are cheap. Cost is wake rate × accumulated context, not headcount.
- An app having a clean API does **not** disqualify it from getting an agent — the durable
  asset is the personal model, not the driving expertise.
- The binding unit is whatever has a durable personal model behind it. For most apps that's
  the window; for Chrome it's the origin. **For Waydroid it can only be the package** — not a
  design choice but a substrate limit, measured 2026-07-31: two tasks of one app produce two
  toplevels with identical `app_id` *and* identical title, and every Waydroid toplevel reports
  the same `pid`, because the whole container is one Wayland client. Per-task binding has
  nothing to key on from the WM layer and would need a Waydroid-side channel.
- Every surface gets an agent. "This app doesn't get one" breaks the invariant that makes
  the whole thing worth reaching for.

---

## Open — resolve before building on them

1. ~~**Waydroid freeform lifecycle.**~~ **RESOLVED 2026-07-30, re-confirmed natively
   2026-07-31** — occluded windows stay RESUMED and Android never learns it was occluded at
   all, so the Waydroid plan stands. The native re-run
   (`docs/findings/2026-07-31-waydroid-occlusion-native.md`) removed the TCG timing caveat and
   tightened the CPU corroboration from 40% jitter to 0.8%. Still **not** settled: the probe
   ran under software rendering both times, so buffer back-pressure from a *gbm/DRM-backed*
   Waydroid remains untested and needs adolin's real GPU.

   The real lifecycle cliffs are *window close* (the Android task is destroyed) and *all
   windows closed*, both of which belong to attach/detach discipline. Two things the native
   run added: the freeze is driven by Android's display sleep, **not** by window count or
   `waydroid.active_apps` — neither predicts it in either direction, and only
   `cgroup.freeze` reads true. And enabling multi-window is a **race** that a fast host loses
   deterministically, whose symptom is other agents' surfaces vanishing and their activities
   going STOPPED, so a Waydroid manager must assert `android.hardware.type.pc` at startup
   rather than assume the property took effect.
2. **Focus contention for native non-Waydroid apps.** Input injection goes to the focused
   surface. Either the agent steals focus mid-sentence or the app needs a second headless
   instance, which many apps refuse. Probably per-app tiering, not a general solution.
3. **Chrome tab-drag loss.** Tabs-as-toplevels (`--app=URL`) makes one-window-one-agent hold
   universally and kills the multiplexing special case entirely — but costs Chrome's tab
   search, tab groups, ctrl+shift+T, and drag reordering. i3-lineage mouse dragging is not
   Chrome-grade. Decide whether that's a loss or the point.
4. **Distillation trigger and format.** What gets written back, when, and in what shape.
5. **Dock swap race.** If the panel lags a tab switch you type into the previous origin's
   agent without noticing. The swap must beat the first keystroke, or the panel holds input
   until re-bound. Treat as correctness, not polish. Also: let the Chrome dock **pin**
   deliberately — reading one tab while talking to another agent is a real thing to want.

---

## Known risks

- **Delegation removes verification.** The email agent asks the calendar agent precisely so
  it doesn't have to learn the calendar — which means it cannot evaluate the answer. Unlike
  a subagent's diff, "Tuesday at 10" has no verifiable artifact, and errors surface
  downstream in a sent email. Context isolation and error detection are the same dial turned
  opposite ways.
- **Provenance dies at the first hop.** Content scraped from a page or email is untrusted
  internet text. Once it crosses the bus it arrives wearing the shape of a legitimate peer
  message. spanreed's trust model was designed when every message body originated from a
  human-driven session; nothing in the frame carries "this started as untrusted." Needs a
  provenance field.
- **No undo.** Git is why coding agents can be aggressive. Nothing here has a diff or a
  revert — the agent sends, deletes, RSVPs. Reads free / writes gated is the obvious rule but
  it stalls exactly the flows that seemed most valuable. Likely mitigation is UI: stage the
  write, render it in the dock, one keystroke to approve.
- **Staleness on stale-buffer action.** An agent is fresh when you hotkey it and drifts
  after. A user request carries re-grounding for free; agent-initiated work on a cold buffer
  does not, and needs an explicit re-ground step.

---

## Working agreements

- Prove the model over sway IPC before writing any compositor code.
- Keep the WM interface at three calls. Anything above it must never learn which compositor
  it's talking to.
- No new spanreed primitives without exhausting the existing ones — the mirror/bridge
  pattern already covers multiplexed surfaces.
- Test 1 is Waydroid occlusion lifecycle. Test 2 is a single hotkey-invoked agent on one
  static window, dock as tree sibling. Neither needs the bus.
