# The first Lifeless: what a real agent on a real surface actually cost

**Date:** 2026-08-03 · **Host:** kaladin · **Compositor:** headless sway 1.12 · **Agent:**
`claude` 2.1.220, Sonnet 4.6, launched by sway `exec` · **Scope:** the `awakener-invoke` loop,
end to end, with a real agent rather than a stand-in.

The automated suites (`AwakeningTest`, `AwakeningSwayTest`) cover the loop with a panel program
that records its identity and sits there. This is the one run that put a **real `claude`** in the
dock, and everything below was measured on it. It is written down because four of the five things
that went wrong are invisible to a test whose dock program is a shell script — they are properties
of Claude Code and of spanreed, not of awakener, and the next person to wire a panel will meet all
four.

## What worked, first try

```
$ awakener-invoke list
* 5  demo-notes
    window:demo-notes
    drab (no agent bound)

$ awakener-invoke invoke
minted agent-lifeless-window-demo-notes-9ae927be for window:demo-notes
  SPANREED_AGENT_NAME=lifeless-window-demo-notes-9ae927be
  dock 7 beside surface 5
```

The tree sway produced is exactly the shape `docs/design.md` rests on — one tab whose contents
are a split container, with the panel as a sibling of the application rather than a sibling of
the tab:

```
   4 workspace layout=tabbed
     6 con       layout=splith
       5 con     app_id=demo-notes
       7 con     app_id=awakener-dock  marks=['awakener_dock_7_for_5_8027192e7863db25']
```

and once the session came up, the bus had it under precisely the identity `:registry` minted:

```json
{
  "agent_id": "agent-lifeless-window-demo-notes-9ae927be",
  "name": "lifeless-window-demo-notes-9ae927be",
  "working_dir": "/tmp/awakener-demo/cwd",
  "pid": 1046903
}
```

It was addressable, and it answered:

```json
{
  "from_agent": "agent-lifeless-window-demo-notes-9ae927be",
  "to_agent": "agent-awakener-proof",
  "body": "My SPANREED_AGENT_NAME is lifeless-window-demo-notes-9ae927be and I am bound to the
           surface with app_id demo-notes.",
  "in_reply_to": "msg-2f5dee59"
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

After trust, the message arrived, and the session stopped again — twice — on tool-permission
prompts for the bus tools it needed to answer:

```
 plugin:spanreed:spanreed - set_status(status: "working") (MCP)
 Do you want to proceed?
```

Same shape as Finding 1 and the same conclusion: **a panel is only as unattended as its
permissions are pre-granted.** Note that a Lifeless answering the bus needs `set_status`,
`list_agents` and `send_message` before it can say anything at all, so this is not a tail case.

Both findings are the same fact from awakener's side: `invoke.dock.command` is the whole of what
awakener controls about the agent it starts, and everything about how that agent behaves before
its first turn is Claude Code configuration awakener does not own and should not silently set.

## Finding 3 — spanreed's MCP will not reply to an unregistered sender, and `register --pid` produces an entry that reads as stale

The first reply attempt failed:

> The sender agent-awakener-proof is not in the current agent list — it was likely a temporary
> agent that has since deregistered.

The MCP `send_message` requires `to_agent` to be a registered id (the CLI `send` does not). The
peer *had* been registered — with `spanreed register --pid <a live pid>` — but that produced:

```json
{"agent_id": "agent-awakener-proof", "pid": 1051350, "pid_start": null}
```

and `pid_start: null` reads as **stale**: the entry is absent from `spanreed list` and present in
`spanreed list --include-stale`. `register` computes `pid_start` from its own PPID, so passing
`--pid` explicitly leaves the field unset. Registering from a child of a long-lived process
instead gave `pid_start: 26574031` and a live entry, and the reply then went through unchanged.

**This is the known gap in `CLAUDE.md`, met in the wild.** It is the same one that keeps
`registry.agent.register_on_mint` defaulting off, and it is the one that will block the
manager-mirrors-managed-agents pattern (`:chrome` registering origins under the manager's PID):
there is no CLI path that registers a live entry for a process that is not the caller's parent.
That is a spanreed conversation, not a local workaround.

## Finding 4 — a new window opens *inside* the docked split, not as a new tab

With focus resting on a docked application, opening a second window of it gave:

```
   4 workspace layout=tabbed
     6 con       layout=splith
       5 con     app_id=demo-notes
       9 con     layout=splith          <- the new window arrived here
         8 con   app_id=demo-notes
        10 con   app_id=awakener-dock
       7 con     app_id=awakener-dock
```

sway opens a new window into the focused container, and after an attach the focused container is
the split the dock lives in. The invariant that matters survives — **every surface still has its
panel as an immediate sibling** — but the flat "one tab per surface" picture does not. Nothing is
broken; the shape is just messier than the design brief's diagram, and anyone reasoning about tab
counts should know it. `wm.focus.resting` is the lever if that is unwanted.

## Finding 5 — `reap` takes down the panel, not necessarily the agent

The sweep did its job exactly: killing surface 5 left panel 7 standing (sway does not collect it),
`awakener-invoke reap` took it down, **and flattened the leftover `splith`** — the tree went from
the nested shape above to a clean single tab.

But `ps` showed the `claude` process still alive afterwards. The reason is this run's own doing:
the dock command was `foot -- tmux new-session … claude`, so tmux was between the window and the
agent and the agent merely became detached. Under the default command (`foot -- env … claude`)
killing the window kills the agent.

Worth writing down because it is not obviously a bug. "Agents persist through idle" is a settled
point in the design brief, and a Lifeless that outlives the panel it was last seen in is arguably
what one wants — the next invocation would find it already animated and, once something can raise
a panel, re-dock it. What it is *today* is an agent with no way back to a window, since a one-shot
process holds no `DockHandle`. Whoever builds the daemon owns choosing between the two.

## Method, so this is reproducible

Headless sway with its own `SWAYSOCK`; `XDG_STATE_HOME` and `XDG_CONFIG_HOME` pointed at a scratch
directory so nothing touched the real bindings or config; the panel run inside `tmux` purely so
its screen could be captured (`tmux capture-pane -p`), which is what made Findings 1 and 2 visible
at all rather than appearing as "the bus stayed empty". `awakener-invoke` was launched with
`SPANREED_AGENT_NAME` unset, which is what a hotkey from a session would *not* do — hence the
clearing built into `SpanreedCli.liveAgents`.

Two `claude` sessions were spent: one that reached the trust prompt and one continuous session
thereafter. Everything else was proved with a panel program that records its identity, which costs
nothing and is what the automated suite uses.
