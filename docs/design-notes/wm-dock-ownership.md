# Design note: who owns a dock node

**Date:** 2026-07-30 · **Scope:** `:wm` only · **Status:** decided; binds issues #4, #6, #7, #9

Five bugs found in review today are one diagnosis: *a dock is a real tree node, and some code
path forgot it*. #2 (fixed), #4, #6, #7, #9. Their fix sites barely overlap, so they will be
four separate PRs — and implemented naively each fix is locally right while the set is
globally incoherent: #7 lands an ad-hoc `try`/`catch`, then #9 introduces the in-memory dock
table #7 should have been built on. This note is what those four PRs implement against.

Nothing here changes the interface, and nothing here is a production code change. It settles
two questions and names the flags the fixes should introduce.

## The thesis

Every one of these bugs comes from the same trade: awakener stores its bookkeeping *in sway*
— as marks, as `no_focus` rules — instead of in its own memory. That looks free, because sway
already has a tree and the tree is shared with the user anyway. It is not free:

- a mark lands one IPC round trip *after* the window it describes (#9);
- a `no_focus` rule can never be taken back (#4);
- a `split` container is compositor state `attach` created and does not track, so a failure
  path forgets it (#6);
- a teardown that re-derives everything from the tree cannot tell "already gone" from
  "error" (#7).

So the general rule this note establishes, from which both decisions below follow:

> **`attach` installs no compositor state it cannot revoke, and awakener does not learn from
> the tree anything it already knew.** The tree is the compositor's; awakener's bookkeeping
> lives in awakener's memory, where it can be corrected and forgotten.

Marks do not go away — they are demoted. A mark stops being *the* truth and becomes the
durable hint that survives awakener's own restart while sway keeps running.

---

## Decision 1 — there is a dock table, and it is authoritative for dock-ness only

**Yes, it exists.** `SwayWindowManager` holds a session-scoped dock table.

### What it is authoritative for

Exactly one predicate: **is this node a dock, and whose.** Nothing else. The division that
makes two sources of truth safe is:

| | authority |
|---|---|
| **Is node *N* a dock, bound to which surface** | the table |
| **Does node *N* exist, and where does it sit** | the tree |
| **Which agent is bound to a surface, durably** | `:registry`, keyed on `SurfaceKey` |

The table never asserts that a window exists, never asserts geometry or parenthood, and is
**never consulted by `resolve`**. `resolve` continues to answer from the durable registry via
`keyFor`; a session-scoped `con_id` table has no business in an answer that must survive a
reboot. If the table ever appears in `resolve`'s path, the durability story has rotted.

### What it keys on

`SurfaceId` (the dock's `con_id`) → entry of `{ surface: SurfaceId, agent: AgentId, appId,
markApplied: Boolean }`, with a secondary index by `surface` so the hotkey path can ask "does
this surface already have a dock" without a tree read.

`con_id` is the right key and the only available one: it is what every sway criteria command
takes, and it is what the tree returns. It is also the reason the table must not persist —
see below.

### When the table and the tree disagree

They *will* disagree: the tree is shared with a user who closes windows. The rule is a union
on recognition and a tree veto on existence.

- **Table says dock, tree has no such node** — the dock is gone. The entry is stale: evict
  it, silently. Not an error, and never a reason to fail a teardown (this is #7's defect
  stated as a data rule).
- **Tree has a marked node the table does not know** — adopt it. The mark is trusted as a
  hint precisely because awakener wrote it. This is how docks survive an awakener restart, and
  it is what `reapOrphans` is already doing by hand today.
- **Both** — agree; the common case.
- **Neither, but an attach is in flight for that `app_id`** — treat as a dock. See the
  reservation, next.

Recognition is therefore a **union**: a node is a dock if the table says so **or** it carries
the dock mark. Union rather than intersection because each source is reliable in one
direction only — the table is ahead of the mark during an attach, and the mark is ahead of
the table after an awakener restart. The union eliminates false negatives, and a false
negative is the expensive one: mistaking a dock for a Drab mints an agent for the panel and
writes it to the durable registry.

### The pre-map gap, which the table alone does not close

This is the sharpest technical point in #9, and the reason a "just add a table" PR would not
actually fix its own issue.

The dock's `con_id` does not exist until the window maps. So a table keyed on `con_id` cannot
be populated before the map — the earliest possible insertion is when `awaitWindow` observes
the node. That shrinks #9's window from *map → mark* (several round trips) to *map →
awaitWindow's next poll* (one round trip). It does not close it. `surfaces()` can still read
the tree in between and hand back the panel as bindable.

Closing it requires suppressing on the only predicate that exists before the window does,
which is the same predicate `no_focus` uses: the dock's `app_id`. So the table carries
**reservations** as well as entries. `attach` files a reservation on the dock's `app_id`
immediately before `exec`, and converts it to an entry when the node is identified. While a
reservation is outstanding, `surfaces()` excludes any window reporting that `app_id` that it
did not already know as a surface.

Two properties make this tolerable rather than a blunt instrument:

- Reservations are created only inside the tree-edit critical section, so at most one is
  outstanding at a time, bounded by the existing 5s map timeout.
- Under `DockIdentity.PER_SURFACE_APP_ID` the reservation is exact. Under the default
  `NEW_NODE` it is coarse — it also hides a hand-launched window reporting the dock's name for
  the length of the attach. That is the same trust assumption `NEW_NODE` already documents in
  `WmFlags.dockIdentity`, not a new one.

The asymmetry is deliberate and should be stated in the code: a real surface transiently
missing from `surfaces()` costs a hotkey that says "no such surface" for a moment. A dock
transiently *present* costs a minted agent and a durable registry write for a panel. Prefer
over-suppression.

### Restart

**The table does not persist, deliberately.**

- Across a **sway** restart, nothing to persist: the windows are gone, and `con_id`
  allocation restarts. A persisted table could only be wrong, and wrong in the worst way —
  a stale entry colliding with a recycled id would hide a genuine surface permanently.
- Across an **awakener** restart with sway still running, the docks and their marks are still
  standing, so the table is rebuilt by one tree scan at first use: every window carrying the
  mark prefix becomes an entry, with the surface id parsed from the mark suffix. That scan is
  the adoption rule above, and it subsumes what `reapOrphans` open-codes.

This is what the marks are *for*, now that they are no longer the primary source of truth.

> **Assumption the #9 PR must verify against live sway 1.12:** that `con_id` is monotonic per
> session and never recycled. The eviction rules above are sound either way, but a recycled id
> would make a briefly-stale entry able to shadow a new window. Cheap to probe: spawn and kill
> windows on the headless backend and watch the ids.

### Flags

- `wm.dock.recognition` = `MARK_ONLY` | `MARK_OR_TABLE`, **default `MARK_OR_TABLE`.**
  `MARK_ONLY` is today's behaviour and is the debuggable one — the whole truth is in
  `swaymsg -t get_tree`, with nothing hidden in process memory. It is the lever to reach for
  if the table is ever suspected of hiding a real surface.
- `wm.dock.pending_suppression` = boolean, **default `true`.** The reservation. Off is
  today's behaviour, i.e. #9 unfixed but with no over-suppression risk at all.

### Rejected

- **Reads take the tree-edit lock.** This is option 2 in #9 and it does fix the symptom. It
  costs the property PR #3 established at length: `surfaces()` would queue behind an attach,
  bounded by dock *map* time — up to 5s — and enumeration is the first thing a hotkey does.
  Rejected because the table gives the same correctness for free.
  **This proposal does not reverse PR #3.** The table is an immutable snapshot behind an
  atomic reference: reads take no lock, block on nothing, and see a consistent view. The
  constraint is that reads never wait on a compositor round trip, not that they touch no
  shared state.
- **Pre-arm the mark with a `for_window [app_id=…] mark …` rule.** It closes the pre-map gap
  the same way `no_focus` closes the focus gap — and inherits the same defect: `for_window`
  rules are unrevocable for the session, and under the default shared `app_id` the rule would
  stamp *every* later dock with the first surface's id, which is #2 arriving by another route.
  Rejected by the thesis: no unrevocable compositor state.
- **Persisting the table.** See Restart.
- **A table that replaces marks entirely.** Then an awakener restart orphans every standing
  dock, permanently and invisibly.

---

## Decision 2 — `attach` is a transaction over the tree, with one declared exception

**Contract:** `attach` either returns a handle whose `detach()` owns everything the attach
did, or it throws having left the tree as it found it. There is no third outcome, and no
partially-built state is ever observable to a caller.

### What `attach` owns

In order: the `split horizontal` container it created; the focus-suppression it applied; the
dock window it spawned; the mark; the geometry; the resting-focus disposition; and the
registry binding. On failure, each is compensated in reverse.

| step | compensation |
|---|---|
| registry `bind` | `unbind` — or, if `bind` is what failed, tear the dock down (below) |
| resting focus / geometry / mark | none needed; they die with the window |
| dock window | `kill`, then `awaitGone` |
| table entry / reservation | evict |
| focus suppression | **`REFOCUS_AFTER_MAP`: nothing to undo. `NO_FOCUS_RULE`: not undoable.** |
| `split horizontal` | `split none` on the surviving child — the same normalisation `detach` already performs on the success path |

The container normalisation must be **one routine shared by unwind and `detach`**, not two.
#6 is the failure path of a job the success path already does correctly, and duplicating it is
how the two drift.

### Compensation runs under the lock already held

The unwind belongs inside the single `treeEdit` block, as a `TreeEdit`-scoped facility —
**not** as `try { attach() } catch { handle.detach() }` at the top of the method. Releasing
the lock to re-take it hands another attach a window in which to map its dock into the
half-built container, which is precisely the class of bug the serialisation exists to prevent.
`Mutex` is not reentrant, so this has to be built where `TreeEdit` already lives.

### Every compensating action is idempotent — this is #7, stated as a rule

sway rejects a criteria command that matches nothing, and the tree does not lose the node the
instant `kill` is issued. So a teardown that races another teardown reliably issues a second
`kill` that fails, and today that exception propagates out of `reapOrphans` and abandons the
rest of the sweep, turning a transient race into persistent tree damage.

The rule, not the patch: **teardown and compensating commands tolerate the target already
being gone.** "No matching node" is success for a command whose entire purpose is that the
node not be there. This wants a distinct `TreeEdit` primitive — a tolerant `run` — used by
every compensating command, plus per-dock isolation in `reapOrphans` so one dock's failure
does not end the sweep.

That primitive is what #6's unwind is built on. If #7 lands as a local `try`/`catch` instead,
#6 has to redo it.

### The one thing that is genuinely not undoable

sway offers no way to revoke a `no_focus` rule. It is not "hard to undo" — the command does
not exist, and only re-reading a config awakener does not own clears the set. So the design
does not pretend:

- `wm.dock.focus_suppression` = `REFOCUS_AFTER_MAP` | `NO_FOCUS_RULE`, **default
  `REFOCUS_AFTER_MAP`.** Selected only when `wm.dock.focus_on_map` is off; that flag keeps
  meaning *whether* to suppress, this one means *how*.
- The default follows from the unwind contract, independently of the flicker argument in #4's
  triage. `REFOCUS_AFTER_MAP` is a compensating action *inside* the transaction: scoped to one
  attach, revocable, and nearly free because `attach` holds the lock across the map and already
  ends by settling focus. `NO_FOCUS_RULE` is an irreversible mutation of shared compositor
  state that outlives both the attach and every future attach. A flag whose only irreversible
  option is the default is a flag that stops meaning what it says after first use.
- Under `NO_FOCUS_RULE`, `attach`'s unwind contract **explicitly excludes the rule**, and the
  flag description must say so. What can still be fixed is the *cumulative* half of #4: the
  session-scoped table also remembers which `app_id`s already carry a rule, so a rule is
  issued at most once per name instead of once per attach.
- Known interaction, to be named in the flag description rather than designed around:
  `NO_FOCUS_RULE` + `PER_SURFACE_APP_ID` is the one combination whose rule list grows without
  bound, since every attach mints a fresh name. Reachable only by choosing both deliberately.

### Flag

- `wm.dock.unwind_failed_attach` = boolean, **default `true`.** Off leaves the wreckage
  standing, for the same reason `OrphanPolicy.LEAVE` exists: when diagnosing, tree damage you
  can see beats tree damage that was tidied away.

### Rejected

- **`detach` as a fourth interface call.** Already rejected in `WindowManager`; restated
  because #6 invites it. Teardown lives on the handle `attach` returns, and unwind is `attach`
  finishing its own job, not a new capability.
- **`PER_SURFACE_APP_ID` as the fix for #4.** It mitigates — each dock gets its own rule — but
  it does not fix: the rule is still permanent, still one per attach, and it now charges the
  dock program an `app_id` argument. Whether it becomes the default is a separate question and
  should not be bundled into #4, per that issue's triage.
- **Top-level `try`/`catch` unwind.** See above: it releases the lock.

---

## A clarification the #4 implementer needs

`wm.dock.focus_on_map` and `wm.focus.resting` + `wm.focus.restore_after_attach` currently both
claim the focus state at the end of `attach`, and on today's defaults they disagree: the dock
takes focus on map, and `settleFocus` immediately takes it back to the app. Assign the
end-state to exactly one rule:

> **Resting focus decides where focus ends. `focus_on_map` decides only whether the dock is
> ever *transiently* focused during the map.**

Under `REFOCUS_AFTER_MAP` the transient steal happens and is corrected inside the lock, before
`attach` returns; under `NO_FOCUS_RULE` it never happens. Either way the end state is the
resting-focus flag's.

This does **not** settle the hotkey case. The probe's note that focus-on-map is a
per-invocation decision (you are about to type at the agent) is still right, and
`wm.focus.resting` is a global flag. Expressing "this invocation should rest on the dock"
needs the hotkey path, which does not exist yet. Do not force it into these flags now.

---

## Constraints, checked

- **Three calls.** Nothing here grows `WindowManager`. The table is private to
  `SwayWindowManager`; `reapOrphans` is already an implementation-side entry point and stays
  one; teardown stays on `DockHandle`.
- **Reads outside the lock.** Preserved. See Decision 1, Rejected.
- **Compositor-agnostic above `:wm`.** The table is keyed on `SurfaceId`, a `:wm` value class,
  and is never returned or consulted from above. Marks, criteria, `no_focus`, split containers
  and `con_id`s all stay below the line.
- **Flags first.** Four flags, each defaulting to the behaviour that would otherwise have been
  hard-coded: `wm.dock.recognition`, `wm.dock.pending_suppression`,
  `wm.dock.focus_suppression`, `wm.dock.unwind_failed_attach`.

## Does the recommended fix order still compose

**#7 → #9 → #6 + #4. Yes, unchanged**, with two amendments that are the whole reason to write
this down:

1. **#7 must land the rule, not the patch.** A tolerant teardown primitive on `TreeEdit` plus
   per-dock isolation in `reapOrphans`. #6's unwind is built directly on that primitive; a
   local `try`/`catch` gets thrown away.
2. **#9 must land the reservation, not just the table.** A post-map table narrows #9's window
   to a single round trip and leaves it open. #6 also needs something to cancel on the unwind
   path, and an entry that does not exist until the dock maps is not it.

#6 and #4 stay paired: both rewrite `attach`'s command sequence, and splitting them means
rewriting the same block twice. #4's mechanism is a step in #6's transaction.

## What this does not settle

- **Over-suppression under `NEW_NODE`.** A user's own window reporting the dock's `app_id` is
  invisible to `surfaces()` for the duration of an attach. Chosen knowingly on the cost
  asymmetry above, but it is a real regression in that window, and `wm.dock.pending_suppression`
  exists to turn it off.
- **`no_focus` remains unrevocable.** Made non-default and non-cumulative. Not fixed — sway
  cannot fix it.
- **Changing `wm.dock.mark_prefix` while docks are standing** orphans them permanently: the
  adoption scan will not find them and nothing else can. Sharp edge for the #9 implementer to
  at least document.
- **Whether `PER_SURFACE_APP_ID` should be the default.** Untouched here, on purpose.
- **The probe's Hazard 1 open question** — switching tabs while typing in the dock, and where
  returning should land — is a UX call and is still open.
- **Verification.** This note is docs-only and exercised nothing. Every one of the four PRs
  needs a live sway run with `AWAKENER_REQUIRE_SWAY=1` and executed counts read out of
  `wm/build/test-results/jvmTest/*.xml`; "BUILD SUCCESSFUL" does not distinguish a passing
  `:wm` suite from a skipped one.
