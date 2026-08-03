# The first Lifeless: what a real agent on a real surface actually cost

**Date:** 2026-08-03 · **Host:** kaladin · **Compositor:** headless sway 1.12 · **Agent:**
`claude` 2.1.220, Sonnet 4.6, launched by sway `exec` · **Scope:** the `awakener-invoke` loop,
end to end, with a real agent rather than a stand-in.

The automated suites (`AwakeningTest`, `AwakeningSwayTest`) cover the loop with a panel program
that records its identity and sits there. This is the run that put a **real `claude`** in the
dock, and everything below was measured on it. It is written down because four of the five things
that went wrong are invisible to a test whose dock program is a shell script — they are properties
of Claude Code and of spanreed, not of awakener, and the next person to wire a panel will meet all
four.

Every literal below was re-taken after the branch was rebased onto #17, which widened the slug
digest and so changed every identity. **Finding 3's diagnosis was wrong in the first version of
this document and is corrected in place**, with what was measured rather than what was inferred.

## What worked, first try

```
$ awakener-invoke list
* 5  demo-notes
    window:demo-notes
    drab (no agent bound)

$ awakener-invoke invoke
minted agent-lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e for window:demo-notes
  SPANREED_AGENT_NAME=lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e
  dock 8 beside surface 5
```

The digest is #17's: `sha256("window:demo-notes")` truncated to 128 bits, which is reproducible
from a shell and was checked that way.

The tree sway produced is exactly the shape `docs/design.md` rests on — one tab whose contents
are a split container, with the panel as a sibling of the application rather than a sibling of
the tab:

```
   4 workspace  layout=splith
     6 con        layout=tabbed
       7 con        layout=splith
         5 con      app_id=demo-notes
         8 con      app_id=awakener-dock  marks=['awakener_dock_8_for_5_f7d801f028dbc959']
```

and once the session came up, the bus had it under precisely the identity `:registry` minted:

```json
{
  "agent_id": "agent-lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e",
  "name": "lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e",
  "working_dir": ".../scratchpad/demo/cwd",
  "pid": 1119890,
  "pid_start": 26814720
}
```

It was addressable, and it answered:

```json
{
  "msg_id": "msg-b9e83ea1",
  "from_agent": "agent-lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e",
  "to_agent": "agent-awakener-proof",
  "body": "My SPANREED_AGENT_NAME is lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e.",
  "in_reply_to": "msg-dbe063f5"
}
```

## Finding 1 — a cold panel does not register until a human answers a trust prompt

The panel came up and **nothing appeared on the bus**. The session was sitting on Claude Code's
folder-trust dialog:

```
 Quick safety check: Is this a project you created or one you trust?
 ❯ 1. Yes, I trust this folder
   2. No, exit
```

The spanreed plugin registers from a `SessionStart` hook, and that hook does not run until the
dialog is answered. So **on a directory Claude Code has not seen before, a hotkey-invoked Lifeless
is invisible to the bus until the user looks at the panel and presses a key.**

This matters more than it looks, because of where a Lifeless's working directory comes from: it is
inherited from the compositor, and a surface has no directory of its own. Every new surface is
therefore a candidate for a first-time directory. The options, none taken yet:

- pin the panel's directory to one already-trusted path (a directory *per surface* would
  reintroduce the prompt for every new surface);
- have the dock command pass a flag that bypasses the dialog, which is a real widening of what a
  hotkey does and should be a flag with its own argument, not a default;
- accept it: the user pressed the hotkey and is looking at the panel, so answering one prompt on
  first contact with a surface is not obviously wrong.

The third is defensible and is what this run did. It is stated here rather than decided.

## Finding 2 — the same is true of every MCP permission the panel needs

After trust, the message arrived, the panel worked out what to answer — and then stopped again,
on a tool-permission prompt for the bus tool it needed to say it:

```
 plugin:spanreed:spanreed - send_message(from_agent:
 "agent-lifeless-window-demo-notes-82c06b6c0628630f1c6490ae1775d34e", …) (MCP)
 Do you want to proceed?
```

The reply landed the moment that was answered and not before. How many prompts a given panel
stops on depends on what has already been granted for its directory — the first run met two,
the re-take met one — but the shape does not change.

Same shape as Finding 1 and the same conclusion: **a panel is only as unattended as its
permissions are pre-granted.** Note that a Lifeless answering the bus needs `set_status`,
`list_agents` and `send_message` before it can say anything at all, so this is not a tail case.

Both findings are the same fact from awakener's side: `invoke.dock.command` is the whole of what
awakener controls about the agent it starts, and everything about how that agent behaves before
its first turn is Claude Code configuration awakener does not own and should not silently set.

## Finding 3 — a Lifeless will not answer a peer that is dead on the bus, and it is the *peer's* liveness that decides

**This finding was written up wrongly the first time.** The original text said `spanreed
register --pid <n>` writes `pid_start: null`, that a null `pid_start` reads as stale, and that
this was `CLAUDE.md`'s known gap blocking `:chrome` from mirroring origins under a manager's
PID. All three are false, and the correction is below with what was actually measured. The
observation was real; the diagnosis was invented to fit it.

What was observed: the first reply attempt did not arrive, and the panel said the sender was
not in the agent list. The peer's registry entry did read `pid_start: null` and was absent from
`spanreed list`. Re-registering the peer so it was live made the reply go through.

What that means, measured against a scratch `SPANREED_STATE_ROOT`:

- **`--pid` is honoured, not ignored.** `StateStore.upsert` computes `pid_start` from the pid it
  is *given* (`store.py`: `pid_start=pid_start_time(pid)`). Registering under a genuinely live
  pid that is not the caller's parent yields a non-null `pid_start` and an entry `spanreed list`
  shows as live — done twice here, once in a bare probe and once for this run's own peer:

  ```
  $ spanreed register --agent-id agent-awakener-proof --pid <a live pid>
  {"agent_id": "agent-awakener-proof", "pid": 1120699, "pid_start": 26817770}
  $ spanreed list
  ['agent-lifeless-window-demo-notes-82c06b6c…', 'agent-awakener-proof']
  ```

  `pid_start: null` comes from `/proc/<pid>/stat` being unreadable, which means the pid was
  **already dead when `register` ran**. Registering under a pid that had just exited reproduces
  it exactly.
- **A null `pid_start` reads as *live*, not stale.** `is_stale` returns `False` when
  `pid_start is None` and the pid is alive, with a docstring saying so — it is the macOS
  fallback. The original entry was filtered out because its *pid* was dead; the null field was a
  symptom of the same fact, not a second one.
- **Neither the CLI nor the MCP refuses a stale recipient.** `_resolve_recipient` lists with
  `include_stale=True` on purpose, "so a mid-restart peer still accepts mail that waits for it",
  and both `spanreed send` and the MCP `send_message` go through it. Measured: a send to a stale
  entry is delivered by both; both raise only for a recipient the registry knows under no id and
  no name at all. So the claim that the MCP is stricter than the CLI was wrong too — they are
  the same call.

Which leaves the finding that survives, and it is a different one: **what stopped the reply was
the agent's own judgment, not a spanreed refusal.** A Lifeless asked to answer looks the bus up
with `list_agents`, which is live-only by default; a peer whose process is gone is simply not
there, and the sensible thing to do with mail from a ghost is nothing. Anything that wants a
reply from a Lifeless has to still be alive when the Lifeless gets round to answering — which
for a hotkey-driven agent can be minutes.

And the `:chrome` inference does not follow: **registering under a live manager PID works.**
`CLAUDE.md`'s known-gap language is about the *shape* of that pattern rather than a missing CLI
path, and the real constraint is one liveness is keyed on the manager: every mirrored origin
lives and dies with the manager process and none of them can be individually stale. That is a
property to design around, not a defect to report. Nothing here is a spanreed bug, and this
should not have been aimed at one.

## Finding 4 — a new window opens *inside* the docked split, not as a new tab

With focus resting on a docked application, opening a second window of it gave:

```
   4 workspace  layout=splith
     6 con        layout=tabbed
       7 con        layout=splith
         5 con      app_id=demo-notes
        10 con      layout=splith          <- the new window arrived here
           9 con    app_id=demo-notes
          12 con    app_id=awakener-dock
         8 con      app_id=awakener-dock
```

sway opens a new window into the focused container, and after an attach the focused container is
the split the dock lives in. The invariant that matters survives — **every surface still has its
panel as an immediate sibling** — but the flat "one tab per surface" picture does not. Nothing is
broken; the shape is just messier than the design brief's diagram, and anyone reasoning about tab
counts should know it. `wm.focus.resting` is the lever if that is unwanted.

## Finding 5 — `reap` takes down the panel, not necessarily the agent

The sweep did its job exactly: killing surface 5 left panel 8 standing (sway does not collect
it), `awakener-invoke reap` took it down, **and flattened what was left over it** — both the
`splith` the pair had lived in and the `tabbed` wrapper above it, leaving the surviving surface
and its panel as the workspace's only child:

```
   4 workspace  layout=splith
    10 con        layout=splith
       9 con      app_id=demo-notes
      12 con      app_id=awakener-dock
```

Killing that surface too and sweeping again left the workspace empty and `awakener-invoke list`
reporting `no surfaces`.

But the `claude` was still alive afterwards — its tmux session merely detached. That is this
run's own doing: the dock command was `foot -- tmux … claude`, so tmux was between the window
and the agent. Under the default command (`foot -- env … claude`) killing the window kills the
agent.

Worth writing down because it is not obviously a bug. "Agents persist through idle" is a settled
point in the design brief, and a Lifeless that outlives the panel it was last seen in is arguably
what one wants — the next invocation would find it already animated and, once something can raise
a panel, re-dock it. What it is *today* is an agent with no way back to a window, since a one-shot
process holds no `DockHandle`. Whoever builds the daemon owns choosing between the two.

## Method, so this is reproducible

Headless sway 1.12 with its own `SWAYSOCK`, driven through the launchers `gradlew
:cli:installDist` produces — every command quoted above is the shipped `awakener-invoke`, not a
hand-assembled classpath. `XDG_STATE_HOME`, `XDG_CONFIG_HOME` **and `SPANREED_STATE_ROOT`** all
pointed at a scratch directory, and the real registry was checked afterwards for anything the
run might have left in it. The panel ran inside `tmux` purely so its screen could be captured
(`tmux capture-pane -p`), which is what made Findings 1 and 2 visible at all rather than
appearing as "the bus stayed empty". `awakener-invoke` was launched with `SPANREED_AGENT_NAME`
unset, which is what a hotkey from a session would *not* do — hence the clearing built into
`SpanreedCli.liveAgents`.

**One trap worth naming, because it silently invalidates the isolation.** `tmux new-session`
attaches to whatever tmux *server* is already running, and that server has the environment of
whoever started it — not the compositor's. The first attempt at this run put the panel on a
server started hours earlier, so `SPANREED_STATE_ROOT` never reached `claude` and the Lifeless
registered on the **real** bus; it had to be deregistered by hand. `tmux -L awakener` gives the
panel a server of its own and the leak stops. Anything that instruments a dock command by
inserting a process in front of the agent needs the same check: the agent's environment is only
the compositor's if nothing in between substitutes its own.

Three `claude` sessions were spent across the run and its re-take. Everything else was proved
with a panel program that records the `SPANREED_AGENT_NAME` it received, which costs nothing and
is what the automated suite uses.
