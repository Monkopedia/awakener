# Design note: who owns a dock node

**Date:** 2026-07-30, revised 2026-07-31 (three times, then amended for #20) · **Scope:** `:wm`
only · **Status:** decided; binds issues #4, #6, #7, #9, and requires #18

Every claim below about how sway **or awakener** behaves was run before it was written — against
a live headless sway 1.12, and through the real `SwayWindowManager` where the question is about
awakener's client rather than about sway. The probe shapes are at the end.

**Anything not measured is written as an open question and labelled one.** Three drafts of this
note each replaced a disproved claim with a new plausible sentence that the next probe also
contradicted; the failure was never the individual sentence, it was writing a reassurance in the
same breath as a rule and reasoning about awakener's *client* the way the rest of the note
reasons about sway. So the convention is the note's own now: an unmeasured assertion here is a
defect, and an honest gap costs one line.

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

> **`attach` installs no compositor state it cannot revoke — and where it must, it says which
> state and what that costs. awakener does not learn from the tree anything it already knew.**
> The tree is the compositor's; awakener's bookkeeping lives in awakener's memory, where it can
> be corrected and forgotten.

The second clause of the first sentence is not a hedge, it is the load-bearing part. `attach`
does install two things it cannot revoke — a `no_focus` rule and the dock program it `exec`s —
and an earlier draft of this note asserted the clean version, which made the contract in
Decision 2 false on its most likely failure path. Both are now named, with their cost, where an
implementer will trip over them.

Marks do not go away either — they are demoted. A mark stops being *the* truth and becomes the
hint that survives awakener's own restart while sway keeps running. "Durable" is the word an
earlier draft used and it is too strong: #14 and #15 were filed after that draft and both land on
the mark. See "The mark predicate, pinned" below, which is the one place #9 should read it from.

---

## Decision 1 — there is a dock table, and it is authoritative for dock-ness only

**Yes, it exists.** `SwayWindowManager` holds a dock table whose lifetime is one compositor
session — which turns out to need a mechanical definition rather than that phrase, and gets one
under "The session boundary".

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
takes, and it is what the tree returns. It is a key **only within one compositor session**,
though — verified monotonic and never recycled inside a session, and restarting from 5 in the
next one — which is why the table must neither persist nor outlive its IPC connection. Both
follow from the same fact, and both are in "The session boundary" below.

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
  reservation, next. The same holds for a *claim* left behind by a failed attach, which is
  Decision 2's answer to a dock that maps too late to be part of the transaction.

Recognition is therefore a **union**: a node is a dock if the table says so **or** it carries
the dock mark. Union rather than intersection because each source is reliable in one
direction only — the table is ahead of the mark during an attach, and the mark is ahead of
the table after an awakener restart. The union eliminates false negatives, and a false
negative is the expensive one: mistaking a dock for a Drab mints an agent for the panel and
writes it to the durable registry.

### The mark predicate, pinned

"Carries the dock mark" is not a predicate, it is two of them, and today the two call sites
disagree — which is #15. Pin it here so #9 does not get to choose:

> **A dock mark is the configured prefix followed by a parseable `con_id`, and nothing else
> counts.** One predicate, used by `surfaces()`, by the adoption scan, and by `reapOrphans`
> alike. A node carrying a prefix-matching mark whose suffix does not parse is **not** a dock; it
> is reported (per #15's option 4) rather than silently hidden in one place and skipped in the
> other.

This is #15's option 1, taken here rather than left to the implementer, because #9 rewrites both
call sites and picking `surfaces()`'s current form would carry #15 into the new design.

Two things this pins and does **not** fix, both measured through the real `SwayWindowManager` on
sway 1.12 (probe J4):

- **The mark is not durable across a second attach on one surface (#14).** It is derived from
  the *surface's* `con_id`, and sway's mark namespace is global, so applying it to a second dock
  takes it off the first — measured directly: after `[con_id=5] mark --add awakener_dock_999`
  then `[con_id=6] mark --add awakener_dock_999`, node 5's marks are `[]` and node 6's are
  `[awakener_dock_999]`. **The table mitigates this in-session** — the union still recognises the
  now-unmarked first dock, which is a point in this design's favour and worth saying — but it is
  exactly the hint's *durability* that is lost: after an awakener restart the adoption scan
  cannot see that dock at all. So "the hint that survives awakener's restart" is conditional on
  no surface ever having been attached twice.
- **The pinned predicate narrows #15 but does not close it.** A user mark that happens to be
  `awakener_dock_<some live con_id>` still hides a real window, and measured on the unpinned
  predicate a mark as ordinary as `awakener_dock_notes` removed a genuine application window from
  `surfaces()` outright.

**Open question, not settled here:** whether #14's fix is a second mark
(`<prefix><dockId>_for_<surfaceId>`), a refusal to attach twice to one surface, or both. That
belongs on #14. What this note fixes is that #9 must not *assume* the mark is durable while #14
is open.

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
**reservations** as well as entries. `attach` files a reservation immediately before `exec`,
and converts it to an entry when the node is identified. While a reservation is outstanding,
`surfaces()` excludes any window reporting that `app_id` that it did not already know as a
surface.

A reservation is `{ appId, standing: Set<SurfaceId>, deadline }`, and **`standing` is part of
the record, not something the reader recomputes.** "Did not already know as a surface" is the
snapshot `attach` takes inside the lock for `NEW_NODE`; `surfaces()` runs outside the lock and
has no way to reconstruct it after the fact. Spelling it out here is the point of the note —
left implicit, each of #9 and #6 would invent its own answer.

Two properties make this tolerable rather than a blunt instrument:

- Reservations are created only inside the tree-edit critical section, so at most one is
  outstanding at a time, bounded by the existing 5s map timeout. *(Amended by Decision 2: a
  failed attach converts its reservation into a claim that outlives it by one further map
  timeout. Restated in full there.)*
- Under `DockIdentity.PER_SURFACE_APP_ID` the reservation is exact. Under the default
  `NEW_NODE` it is coarse — it also hides a hand-launched window reporting the dock's name for
  the length of the attach. That is the same trust assumption `NEW_NODE` already documents in
  `WmFlags.dockIdentity`, not a new one.

The asymmetry is deliberate and should be stated in the code: a real surface transiently
missing from `surfaces()` costs a hotkey that says "no such surface" for a moment. A dock
transiently *present* costs a minted agent and a durable registry write for a panel. Prefer
over-suppression.

### The session boundary, mechanically

**The table does not persist, deliberately** — but "session-scoped" as prose is exactly the
kind of ambiguity this note exists to remove, and it hides the case that breaks the table.
There are **three** combinations, not two:

- **awakener restarts, sway keeps running.** The docks and their marks are still standing, so
  the table is rebuilt by one tree scan at first use: every window matching the pinned mark
  predicate (prefix + parseable `con_id`) becomes an entry, surface id parsed from the suffix.
  That scan is the adoption rule above and it subsumes what `reapOrphans` open-codes. This is
  what the marks are *for*, now that they are no longer the primary truth — subject to #14,
  which is the one case where a dock has no mark left to be adopted by.
- **Both restart.** Nothing to carry across; the table starts empty and correct.
- **sway restarts, awakener does not.** The dangerous one — and the reason this section is
  mechanical rather than prose.

awakener is a long-lived daemon: `:config` reloads on file change precisely so a flag flip
reaches a *live* one. sway 1.12 has no in-place restart — `restart` returns
`Unknown/invalid command 'restart'`, and `reload` only re-reads a config awakener does not
own — so a sway restart is always a fresh process with a fresh id counter, while awakener's
in-memory table survives untouched. Measured end to end, one client holding its connections
across the boundary:

```
=== SESSION A ===
   con_id=5   app_id=myapp          marks=[]
   con_id=6   app_id=awdock         marks=['awakener_dock_x']
>>> the dock table now holds { con_id=6 -> DOCK }

=== SESSION B (sway restarted; the client did NOT) ===
   con_id=5   app_id=users_editor   marks=[]
   con_id=6   app_id=users_browser  marks=[]
>>> con_id=6 in session B is the user's browser
```

This is verbatim the failure named above as the reason not to *persist* the table — a stale
entry colliding with a fresh id hides a genuine surface permanently — and an in-memory table
has the identical exposure the moment its lifetime is longer than the compositor's. It is not
a tail case: a fresh session allocates from 5 densely, and awakener's first attach happens
early, so the collision range is exactly where both sessions are busiest.

Neither existing rule saves it. The **tree veto** is "table says dock, tree has *no* such
node", and after a restart the tree *does* have a node at that id. The **union** makes it a
false *positive* on dock-ness — the direction the union deliberately biases toward — so the
safety argument for the union actively makes this worse.

**So, the rule:**

> **The dock table's lifetime is the IPC connection's lifetime.** Connection loss *is* the
> session boundary. On loss the whole table — entries, reservations and claims — is discarded,
> not repaired; the successor connection starts empty and rebuilds by the adoption scan against
> the tree it actually finds.

### Detecting that boundary needed machinery awakener did not have — added by #20

An earlier draft said the boundary "is observable, and it needs no new machinery". The *socket*
observes it; **awakener's client threw the observation away**, and that is the sentence an
implementer would have acted on. The machinery is now present — it landed as **#20** — so the
rest of this section is the record of what was wrong and what was built; the amendment that
states today's behaviour is at the end of it.

At the socket the signal is prompt and unambiguous — measured by killing sway under a client
holding both of the connections `SwayWindowManager` opens:

```
   blocked event connection -> EOF ("sway closed the IPC socket"), immediately
   idle command connection  -> ECONNRESET / EPIPE on its next request
```

But `SwayConnection.subscribe` *caught* that EOF and returned *normally* — EOF and a deliberate
`close()` landed on the same line (this is the pre-#20 code, kept because it is what the
measurement below was taken against):

```kotlin
val (type, payload) = try { readMessage() }
catch (e: IOException) { return@withContext } // closed underneath us; a normal shutdown
```

and `changes` was a `callbackFlow` whose `job` finishing did not close the channel, so the flow
neither completed nor failed. Measured against the real `SwayWindowManager` at that commit,
collecting `changes` across a `SIGKILL` of the compositor (probe J1):

```
   events before death = [Appeared, Focused]
   after sway death   -> collectorActive=true flowCompleted=false failed=null
   wm.tree() after death -> java.net.ConnectException: Connection refused
```

**A collector could not distinguish a dead compositor from an idle desktop.** So the rule above is
right and the cost of enforcing it was not zero. What it required, concretely:

> `subscribe` must distinguish EOF from a deliberate `close()`, and `changes` must close or fail
> its channel when the connection dies rather than going silent. **Until that exists the table
> rule has no trigger**, because a boundary nothing can observe cannot be what discards the table.
> *(Both halves done by #20 — see below. This is the requirement as it stood before it.)*

That was filed as part of **#18**, which is where the collector itself is owed from, and **the
observation half of it has since landed as #20**: `subscribe` returns only when the caller closed
the connection and throws `CompositorSessionEnded` otherwise, and `changes` closes its channel with
that cause. Re-measured through the real `SwayWindowManager` the same way, the probe above now
reads `collectorActive=false flowCompleted=false failed=CompositorSessionEnded` after the `SIGKILL`
and is unchanged on an idle desktop — both halves measured, and both are now asserted by
`SwaySessionEndTest`. So the boundary is observable; **nothing observes it yet**, which is the
rest of #18 — that half is read off the source rather than probed: `changes` has no collector
outside tests and `commands` is still `by lazy { connect() }`. Reporting death is not the same as
reacting to it, and #20 deliberately did not build the reaction — discarding the table on that
signal is still the reconnect owner's, in the commit that acquires the successor connection.

On the command connection: it is `by lazy { connect() }` and learns only on next use, which is
late. An earlier draft added "but never *wrong*: it cannot succeed against a dead socket", and
that absolute is false — measured directly (probe R1), a `GET_TREE` written immediately before
`SIGKILL` came back as a **complete 4473-byte reply on 1 of 6 trials**, and giving sway 5 ms to
serve it into the socket buffer before the kill made it **6 of 6**. The semantics survive: a
reply that arrives describes the session that produced it, which is the session the table was
built against. The sentence did not, and it is the kind of absolute this note now avoids. #20 left
`request` alone for that reason, so the command path still has no *typed* session-ended signal:
after the session ends it raises an untyped `IOException` on the held connection and a
`ConnectException` on a fresh one (measured in #20's review, not by a probe recorded here).

Two consequences, stated rather than designed around:

- **With `wm.events.enabled = false` there is no promptly-detecting connection**, and the
  boundary is noticed only at the next command. In that window the table is stale, and while it
  cannot make a *command* wrong, `surfaces()` reads the tree without the lock and would answer
  from a stale table against a fresh tree. That flag's description must say so, alongside the
  orphan-handling caveat it already carries.
- **Today this is latent, not live**, because `commands` never reconnects: a sway restart
  leaves the manager permanently broken rather than quietly wrong. That is not a defence —
  reconnection is table stakes for a daemon, and it is the change that arms this. **Whoever
  adds reconnect clears the table in the same commit** — the trigger it clears on already
  exists, since #20 made the boundary observable (above). Nothing in the four queued PRs would
  retrofit an invalidation rule this note did not ask for; **#18** is the home for both of the
  pieces that remain — acquiring the successor connection, and discarding the table.

If a stronger key is ever wanted than "this connection", sway supplies one: with `SWAYSOCK`
unset the socket is `$XDG_RUNTIME_DIR/sway-ipc.<uid>.<pid>.sock`, so the compositor's pid is in
the path. Not needed for the rule above — a connection cannot outlive the process that
accepted it — but worth knowing before someone invents a heartbeat.

> **Assumption resolved** (it was flagged here for the #9 PR to verify; it has been). `con_id`
> **is** monotonic per session and never recycled. Verified on sway 1.12 across 236 containers
> in two sweeps — 96 windows spawn-all/kill-all, then 140 more under interleaved churn that
> created `split` containers and refilled freed ids — strictly increasing, contiguous 5–240,
> zero reuse. sway allocates from one global counter shared by `root`/`output`/`workspace`/`con`
> and never returns a freed id to it. The key is therefore sound **within** a session, which is
> precisely why the exposure above is about the boundary and not about the key. #9 does not need
> to re-probe it.

### Flags

- `wm.dock.recognition` = `MARK_ONLY` | `MARK_OR_TABLE`, **default `MARK_OR_TABLE`.**
  `MARK_ONLY` is today's behaviour and is the debuggable one — the whole truth is in
  `swaymsg -t get_tree`, with nothing hidden in process memory. It is the lever to reach for
  if the table is ever suspected of hiding a real surface.
- `wm.dock.pending_suppression` = boolean, **default `true`.** The reservation. Off is
  today's behaviour, i.e. #9 unfixed but with no over-suppression risk at all. It gates whether
  a reservation is *filed*; it never gates whether one is cleared, and neither does any other
  flag — see "Bookkeeping is not compensation".

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
- **Persisting the table.** See "The session boundary" — and note that the argument against
  persisting is the same argument that bounds the in-memory table's lifetime to a connection.
- **A table that replaces marks entirely.** Then an awakener restart orphans every standing
  dock, permanently and invisibly.

---

## Decision 2 — `attach` is a transaction over the tree, with two declared exceptions

**Contract:** `attach` either returns a handle whose `detach()` owns everything the attach
did, or it throws having left the **tree** as it found it — with the sole residue of a dock
program that may still be in flight, which the claim below owns *if a collector exists to read
the claim*, and which today stands unowned because none does (#18). No half-built container is
ever observable to a caller.

That is deliberately narrower than "there is no third outcome", because there is one, and
promising two is how it gets implemented away. Two things `attach` does are not tree edits and
are not revocable:

1. a `no_focus` rule, under `NO_FOCUS_RULE` — sway has no verb that takes one back;
2. **the dock program it already `exec`'d.**

`exec` returns `{"success": true}` and nothing else — no pid, no handle — so there is nothing
to cancel it with. On the timeout path, which is the most likely attach failure and the one #6
was reproduced on, the dock has not mapped, so there is nothing to `kill`, and nothing stops it
mapping afterwards. Measured on sway 1.12 with the real dock shape — tabbed workspace,
`split horizontal`, then an `exec` whose window maps after the attach gave up:

```
-- unwind: kill the dock (not mapped yet) -> success:false  error:"No matching node."
-- unwind: split none on the survivor     -> success:true
### tree right after unwind — exactly as attach found it
      4  workspace layout=tabbed  kids=2
      5  con app_id=app1
      6  con app_id=app2
### tree after the late dock maps
      4  workspace layout=tabbed  kids=3
      5  con app_id=app1
      8  con app_id=latedock  marks=[]     <- a third tab, unmarked
      6  con app_id=app2
```

The unwind is correct and the tree is clean — and then a bare agent panel arrives as a
top-level tab, in no table, carrying no mark, which `surfaces()` reports as a bindable Drab.
That is Decision 1's expensive false negative arriving through Decision 2's own success path,
and the reservation that would have suppressed it is evicted by the unwind at exactly the
moment it is still needed.

### The late dock: the reservation outlives the attach that filed it

On the failure path the reservation is **not** evicted. It is converted to a **claim** — same
`app_id`, same `standing` set, deadline one further map timeout out.

An earlier draft then wrote the predicate as *"the first window that maps reporting that `app_id`
and is not in `standing` **is** this attach's abandoned dock"*. That is false, and it fails in
the ordinary case rather than a corner. Measured through the real `SwayWindowManager` at stock
defaults (probe J2): a failed attach with the dock command `sh -c 'exit 1'` — a dock program that
never starts, so **no late dock ever arrives at all** — followed by the ordinary retry a user
performs when the panel does not come up:

```
attach #1 -> IllegalStateException: dock 'aw-dock' never appeared  (after 5011ms)
             standing set captured at attach #1 = []
attach #2 -> dock con_id=7 at +31ms into the 5s grace
   node 5 app_id=aw-app1 marks=[]                  matchesClaimPredicate=false
   node 7 app_id=aw-dock marks=[awakener_dock_5]   matchesClaimPredicate=true
```

Node 7 is the **second attach's own dock** — marked, in the table, holding a live handle — and it
satisfies the claim 31 ms into the grace. `RECLAIM` kills it; attach #2's remaining commands then
fail against a dead node, so it throws and files a claim of its own, and the next retry is the
next victim. So *"killing it is the compensation `attach` would have run had the window existed
in time; it is late, not different"* is exactly wrong here: what dies is a different window,
belonging to a different attach, that awakener itself knows is a dock.

**The predicate, pinned.** A claim matches a node only if all of these hold:

1. it reports the claim's `app_id`;
2. it is not in the claim's `standing` set;
3. it is **not covered by a live reservation**;
4. it is **not in the dock table**;
5. the claim's deadline has not passed.

And a claim is **dropped as soon as a later attach for the same `app_id` succeeds** — that
attach's dock is the window the `app_id` now means, and a claim that outlives it has no
legitimate target left.

(3) and (4) are what the earlier draft was missing, and (3) is the one that actually does the
work: **the mark cannot be the exclusion.** Measured (probe J3), at the instant the dock's
`window::new` arrives the node is present in the tree with `marks=[]`, and it only reads
`[awakener_dock_5]` once `attach` has returned — the mark lands one round trip *after* the
window, which is #9's entire premise. Excluding "anything carrying a dock mark" would therefore
not have saved node 7. The in-memory reservation is the only fact that exists early enough.

Kill rather than merely suppress, where the predicate does match: a suppressed late dock is a
window the user can see, cannot bind, and nobody owns — worse than either honest alternative.

What it costs, stated rather than designed around:

- **Nothing consumes `changes` today, so `RECLAIM` does nothing at all.** See "The third outcome
  has no owner" below; this is the first cost, not the last.
- **It happens after `attach` returned.** It is not inside the transaction and cannot be — the
  transaction ended. "Left the tree as it found it" holds across the following window only if
  something outside the transaction finishes the job, and today nothing does (#18).
- **It is event-driven, not scheduled.** A claim is consulted when a `window::new` event
  arrives on the `changes` stream — the same stream `reapOrphans` is driven off — and its
  deadline is evaluated at that moment, not by a timer. Nothing polls, loops, or acts on a
  schedule, per the working agreement. A claim that expires unfired simply loses to the next
  event that reads it. **The claim is also evaluated against the tree once at filing time**,
  which closes the millisecond-wide gap between `awaitWindow`'s timeout and the claim existing:
  `awaitWindow` polls the tree rather than the event stream, so a dock that maps inside that gap
  emits a `window::new` that no claim is there to read, and nothing re-reads it afterwards.
- **With `wm.events.enabled = false` there is no event stream, so `RECLAIM` degrades to
  `LEAVE`** and the stray panel stands. That flag's description now owes three sentences, not
  one.
- **Under `NEW_NODE` the claim stays coarse even with the predicate pinned, and destructively
  so.** The exclusions above cover every window *awakener* knows about; they cannot cover a
  hand-launched window reporting the dock's shared `app_id`, which is killed rather than merely
  hidden. Over-suppression costs a moment's invisibility; an over-reaching claim costs the user a
  window. This is the strongest argument yet that `PER_SURFACE_APP_ID` should become the default
  — under it the claim is exact by construction.
- **It amends Decision 1's tolerability argument**, which is false as written. Restated: at
  most one *reservation* outstanding at a time, plus at most one *claim* per failed attach,
  each bounded by one map timeout, and none of them surviving the session boundary.

**Flag:** `wm.dock.late_dock` = `RECLAIM` | `LEAVE`, **default `LEAVE`** — changed from `RECLAIM`
in an earlier draft. The pinned predicate removes the cascade above; the default flip is about
what is left. A timeout has two causes: the dock maps late, or it never starts. **`RECLAIM` has a
legitimate target only in the first**, and in the second it stands its full grace with no
legitimate target at all, so the only window it can ever reach is somebody else's. Against that:

- `LEAVE` costs a stray bindable panel — this note's expensive false negative, but a *visible*
  and recoverable one: a Breath and a registry row, both undoable.
- `RECLAIM` under `NEW_NODE` costs a user's window, which is not recoverable at all.
- `RECLAIM` is also not today's behaviour. Nothing reclaims anything today, and nothing can until
  #18 — so `LEAVE` is both the conservative default and the one that "defaults are what you would
  have hard-coded" actually selects. Shipping a default whose behaviour cannot be exercised would
  be worse than either.

`RECLAIM` is the lever for whoever wants the stray panel gone, and it is the right default under
`PER_SURFACE_APP_ID`, where the claim is exact by construction.

**Open question, not settled here:** which of the two timeout causes dominates in practice. That
is the number that decides whether `RECLAIM` should be on by default, and measuring it needs a
real panel program on a real desktop — neither exists yet. This note picks the conservative
default rather than guessing the distribution, and says so rather than dressing the guess up as a
finding.

### The third outcome has no owner — #18

The mechanism above is specified against a collector that **does not exist**. Verified on `main`
(`7a01fe0`) and across every open PR:

```
$ grep -rn reapOrphans --include='*.kt' .
wm/src/jvmTest/.../SwayBindingTest.kt:152:        wm.reapOrphans()
wm/src/jvmMain/.../SwayWindowManager.kt:267:    suspend fun reapOrphans() {
```

— the declaration and one test. `changes` is likewise declared and never collected outside tests,
and none of #11, #12 or #17 touches either. PR #12 says so about `reapOrphans` in its own body.

So, stated where it cannot be lost rather than assumed away:

> **Nothing drives `reapOrphans` and nothing collects `changes` today.** Until something does,
> `RECLAIM` is inert by construction, no claim is ever read, and the contract sentence
> "'left the tree as it found it' holds because something outside the transaction finishes the
> job" is true of *nothing*. This note does not design that collector and none of the four PRs is
> its home; it is **#18**, and it should land before or alongside #6 and #9 rather than after.

The degradation list above names `wm.events.enabled = false` as the case where `RECLAIM` falls
back to `LEAVE`. "No collector at all" is today's *actual* case and has the same effect for a
different reason, which is why `LEAVE` being the default costs nothing at the moment.

#18 also owns the *reaction* to Decision 1's session boundary. The detection itself landed as #20,
so what is missing there is the same thing that is missing here: a report `changes` now makes and
nothing collects is the same defect seen from the other end.

### What `attach` owns

In order: the `split horizontal` container it created; the focus-suppression it applied; the
dock window it spawned; the mark; the geometry; the resting-focus disposition; and the
registry binding. On failure, each is compensated in reverse.

| step | compensation |
|---|---|
| registry `bind` | `unbind` — or, if `bind` is what failed, tear the dock down (below) |
| resting focus / geometry / mark | none needed; they die with the window |
| dock window | `kill`, then `awaitGone` |
| table entry | evict — *bookkeeping, always runs* |
| reservation | convert to a claim, then evict when the claim resolves or expires — *bookkeeping, always runs* |
| focus suppression | **`REFOCUS_AFTER_MAP`: nothing to undo. `NO_FOCUS_RULE`: not undoable.** |
| `split horizontal` | `split none` on the surviving child — the same normalisation `detach` already performs on the success path |

The container normalisation must be **one routine shared by unwind and `detach`**, not two.
#6 is the failure path of a job the success path already does correctly, and duplicating it is
how the two drift. One routine, two failure semantics — `detach` raises, unwind suppresses — so
the routine reports and the caller decides; see "When a compensating action itself fails".

### Bookkeeping is not compensation

Two rows above are not tree edits at all: the table entry and the reservation live in
awakener's own memory. They are cleared **unconditionally**, outside
`wm.dock.unwind_failed_attach`'s scope, and outside every other flag's.

The flag's stated reason is that *tree damage you can see beats tree damage that was tidied
away*. A leaked reservation is invisible in `swaymsg -t get_tree`, so leaving it standing
serves that reason not at all — and it costs a great deal: under the default `NEW_NODE` the
reservation is on the shared dock `app_id`, so `surfaces()` would hide **every** window under
that name for the life of the process. A flag whose off-state silently blinds surface
enumeration is precisely the failure this note indicts in #4, arriving inside this note's own
design.

So `wm.dock.unwind_failed_attach` covers exactly the tree compensations — the `kill` and the
container normalisation — and nothing else. The same division answers the fix-order gap below:
eviction is not part of #6's unwind and never was.

**Where the unconditional part lives**, since #9 lands before #6 and `attach` has no failure
handling at all today: a `try`/`finally` at the top of `attach`, around the whole method,
covering only the bookkeeping. **This is not the "top-level `try`/`catch` unwind" that is
rejected below.** That rejection is about the *lock* — a compensation that runs outside
`treeEdit` releases the tree between the failure and the repair, and hands another attach a
window in which to map its dock into the half-built container. Bookkeeping takes no lock, touches
no compositor state, and cannot be interleaved with anything, so the top of `attach` is the
correct home for it and the only one that works before #6 exists. Said here because otherwise it
is invented twice or not at all.

### When a compensating action itself fails

Unwind is **best-effort, and the original exception is what propagates.** A compensation that
fails is logged and suppressed; it never replaces the diagnosis of why the attach failed, and
it never turns one failure into two.

This is not covered by "no matching node is success", and it is not hypothetical. Measured on
sway 1.12:

```
[con_id=999999] kill       -> success:false parse_error:false error:"No matching node."
[con_id=5] split nonsense  -> success:false parse_error:false error:"Invalid split command (…)"
[con_id=5] split none      -> success:false parse_error:false
                              error:"Can only flatten a child container with no siblings"
totally invalid command    -> success:false parse_error:true  error:"Unknown/invalid …"
```

The third line is the unwind's own container normalisation failing in the unwind's own
scenario — it errors whenever the node it is given still has a sibling. And the failure that
matters most is *correlated* rather than independent: a socket that died mid-attach is a
leading cause of the attach failing, and it fails every compensation that follows. Without the
rule, a compensation throwing means the transaction throws **and** the tree stays modified —
both halves of the contract violated — with the interesting exception replaced by a dull one.

Consequence for #7's tolerant teardown — and here an earlier draft prescribed the wrong
mechanism. It said the tolerant `run` **must** discriminate on the literal string
`"No matching node."`. PR #12, the open fix for #7, deliberately does not, and #12 is right:

```kotlin
suspend fun kill(id: SurfaceId) {
    val failure = attempt("[con_id=${id.raw}] kill") ?: return
    check(tree().find(id.raw) == null) { "sway rejected killing ${id.raw}: ${failure.error}" }
}
```

*"The acknowledgement is therefore not the thing to check; the tree is."* That is strictly
better: it takes no dependency on a sway internal, and it also covers the case a string match
misses — the window dying between the read that found it and the command that acted on it. So the
rule, re-pointed:

> **Tolerate by checking the post-condition in the tree wherever one exists.** The command
> succeeded if the tree now has the shape the command was asking for, whatever sway said about
> it. The literal `"No matching node."` is the *fallback* for a compensation with no readable
> post-condition, and where it is used it belongs in one place with a comment saying it is a sway
> internal rather than an API.

What `parse_error` cannot do is still worth knowing, and is why "swallow every `success:false`"
is not the answer either: three of the four results above carry `parse_error:false`, and
`split none` failing with `"Can only flatten a child container with no siblings"` is not a
target-already-gone case at all — it is a genuine failure, which the best-effort rule above
covers by logging and suppressing it rather than by pretending it succeeded.

One consequence of taking #12's mechanism: **the shared container-normalisation routine now has
two callers with different failure semantics** — `detach` raises (and `reapOrphans` collects
per-dock, per #12), while unwind suppresses. That is fine, and it is a rule rather than an
accident: **the routine reports, the caller decides.** It must not swallow on its own account, or
`detach`'s aggregate loses the failures it exists to report.

### Compensation runs under the lock already held

The **tree** unwind belongs inside the single `treeEdit` block, as a `TreeEdit`-scoped facility —
**not** as `try { attach() } catch { handle.detach() }` at the top of the method. Releasing
the lock to re-take it hands another attach a window in which to map its dock into the
half-built container, which is precisely the class of bug the serialisation exists to prevent.
`Mutex` is not reentrant, so this has to be built where `TreeEdit` already lives.

The bookkeeping `finally` is the exception, and it is not one of these: see "Bookkeeping is not
compensation". It takes no lock, so it does not release one.

### Every compensating action is idempotent — this is #7, stated as a rule

sway rejects a criteria command that matches nothing, and the tree does not lose the node the
instant `kill` is issued. So a teardown that races another teardown reliably issues a second
`kill` that fails, and today that exception propagates out of `reapOrphans` and abandons the
rest of the sweep, turning a transient race into persistent tree damage.

The rule, not the patch: **teardown and compensating commands tolerate the target already
being gone** — established by reading the tree back, not by reading sway's complaint (above).
This wants a distinct `TreeEdit` primitive — `kill` in PR #12 is exactly it — used by every
compensating command, plus per-dock isolation in `reapOrphans` so one dock's failure does not
end the sweep.

That primitive is what #6's unwind is built on. If #7 lands as a local `try`/`catch` instead,
#6 has to redo it. **PR #12 satisfies this as written**, including the tree-read mechanism.

### The other declared exception: `no_focus`

The late dock is one of the two things `attach` cannot take back; this is the other, and it is
the worse of the two because it is *permanent* rather than merely late. sway offers no way to
revoke a `no_focus` rule — it is not "hard to undo", the command does not exist, and only
re-reading a config awakener does not own clears the set. So the design does not pretend:

- `wm.dock.focus_suppression` = `REFOCUS_AFTER_MAP` | `NO_FOCUS_RULE`, **default
  `REFOCUS_AFTER_MAP`.** Selected only when `wm.dock.focus_on_map` is off; that flag keeps
  meaning *whether* to suppress, this one means *how*.
- The default follows from the unwind contract, independently of the flicker argument in #4's
  triage. `REFOCUS_AFTER_MAP` is a compensating action *inside* the transaction: scoped to one
  attach, revocable, and nearly free because `attach` holds the lock across the map and already
  ends by settling focus. `NO_FOCUS_RULE` is an irreversible mutation of shared compositor
  state that outlives both the attach and every future attach. A flag whose only irreversible
  option is the default is a flag that stops meaning what it says after first use.
- **The default now rests on a measurement, not only on the argument.** Running the real attach
  sequence on sway 1.12 with an event subscription attached, sway emits `new` and `focus` for
  the dock **in the same millisecond**, so there is no race with the dock's own focus grab: by
  the time `awaitWindow` can see the node in `get_tree`, focus has already moved and the
  corrective `focus` cannot land early.

  ```
    0.028  new    con=7 app=rfdock
    0.028  focus  con=7 app=rfdock
    0.106  focus  con=5 app=app1     <- the correction
  ```

  Transient steal **73–78 ms** over one persistent IPC connection across three runs, ≈96 ms
  when driven through separate `swaymsg` process spawns. No steal-back over a 3 s observation;
  focus rests on the app.
- Under `NO_FOCUS_RULE`, `attach`'s unwind contract **explicitly excludes the rule**, and the
  flag description must say so. What can still be fixed is the *cumulative* half of #4: the
  table also remembers which `app_id`s already carry a rule, so a rule is issued at most once
  per name instead of once per attach. That memory is bounded by the same session boundary as
  everything else in the table, and correctly so: a new sway session has no rules in it.
- Known interaction, to be named in the flag description rather than designed around:
  `NO_FOCUS_RULE` + `PER_SURFACE_APP_ID` is the one combination whose rule list grows without
  bound, since every attach mints a fresh name. Reachable only by choosing both deliberately.

### Flags

- `wm.dock.unwind_failed_attach` = boolean, **default `true`.** Off leaves the **tree**
  wreckage standing — the dock window and the half-built split container — for the same reason
  `OrphanPolicy.LEAVE` exists: when diagnosing, tree damage you can see beats tree damage that
  was tidied away. It does **not** cover awakener's in-memory bookkeeping, which is cleared on
  both paths regardless; a leaked table entry or reservation is invisible in `get_tree` and
  blinds `surfaces()`, which is the opposite of what this flag is for. The description must say
  both halves.
- `wm.dock.late_dock` = `RECLAIM` | `LEAVE`, **default `LEAVE`.** See "The late dock". The
  description must state that `RECLAIM` kills a window `attach` never saw; that under `NEW_NODE`
  the residual predicate is the shared `app_id`, so it can reach a hand-launched window; and that
  it does nothing at all when `wm.events.enabled` is off **or while nothing collects `changes`,
  which is the case today (#18)**.

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

That reconciles two of the three flags. The third combination has to be closed here too, or the
suppression flag suppresses nothing: `wm.focus.restore_after_attach = false` with
`wm.dock.focus_on_map = false` under
`REFOCUS_AFTER_MAP`. `settleFocus` is called only `if (cfg[WmFlags.restoreFocusAfterAttach])`,
so the correction never runs and the dock keeps focus — the one outcome `focus_on_map = false`
was asked for. The rule:

> **Under `REFOCUS_AFTER_MAP` the corrective focus is part of the suppression, not part of
> resting focus, and runs regardless of `restore_after_attach`.** `restore_after_attach`
> decides whether the *resting-focus rule* is applied at the end of attach; it does not decide
> whether a transient steal is corrected.

The two are the same IPC command today, which is why they were conflated. They are not the same
decision.

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
- **Flags first.** Five flags, each defaulting to the behaviour that would otherwise have been
  hard-coded: `wm.dock.recognition`, `wm.dock.pending_suppression`,
  `wm.dock.focus_suppression`, `wm.dock.unwind_failed_attach`, `wm.dock.late_dock`. **None of
  them gates bookkeeping**, which is the property that matters and the one an earlier draft got
  wrong.

  An earlier draft also claimed none of them "has an off-state that silently breaks a read path",
  and that is too strong. `wm.dock.recognition = MARK_ONLY` and `wm.dock.late_dock = LEAVE` both
  leave `surfaces()` reporting a dock as bindable — this note's own expensive false negative. The
  accurate claim is weaker and still worth making: each off-state is a **chosen** cost, named in
  the flag's own description, rather than a silent one. `wm.events.enabled` is the one flag whose
  off-state costs correctness in a path it does not name, and its description is amended to say
  all of it.

## Does the recommended fix order still compose

**#7 → #9 → #6 + #4**, with three amendments that are the whole reason to write this down — and
one addition, **#18**, which was filed during this note's third review round.

**#18 does not reorder the four; it is a prerequisite for one of them being verifiable.** #7 and
#4 are unaffected. #9 can land its table, reservation and claim without a collector — the
suppression half of #9 is a `surfaces()` read and needs no events at all — but the *claim* half
is dead code until #18, which is why `wm.dock.late_dock` now defaults to `LEAVE`: shipping
`RECLAIM` on by default would be shipping a default whose behaviour cannot be exercised. #6 is
similarly fine, since its unwind is synchronous. So: **#7 → #9 → #6 + #4, with #18 before or
alongside #9**, and `late_dock` reconsidered once a collector exists. The order holds.

1. **#7 must land the rule, not the patch.** A tolerant teardown primitive on `TreeEdit` plus
   per-dock isolation in `reapOrphans`. #6's unwind is built directly on that primitive; a
   local `try`/`catch` gets thrown away. **PR #12 does this**, and its tree-read mechanism is
   what the note prescribes rather than the string match an earlier draft asked for.
2. **#9 must land the reservation, not just the table.** A post-map table narrows #9's window
   to a single round trip and leaves it open. #6 also needs something to cancel on the unwind
   path, and an entry that does not exist until the dock maps is not it.
3. **#9 must land the reservation's whole lifetime, not just its creation.** Otherwise the
   order does not compose: `wm.dock.pending_suppression` defaults `true`, so between #9 merging
   and #6 merging, every failed attach on `main` leaks a permanent reservation and blinds
   `surfaces()` for the dock's `app_id`.

   The resolution is not a reordering — #6 before #9 would land an unwind with nothing to
   unwind, and the gap would reappear the moment #9 arrived. It is the division already
   established above: **eviction is bookkeeping, not compensation.** It does not belong in #6's
   `TreeEdit` unwind and never did. #9 owns file, convert, evict, and the claim that outlives a
   failed attach; #6 then adds only the tree compensations, which are the part that actually
   needs to run under the lock. Each PR is self-consistent on `main` on its own.

   Concretely, #9 adds a `try`/`finally` at the top of `attach` covering the bookkeeping only —
   which is allowed, and is not the "top-level `try`/`catch` unwind" the note rejects. See
   "Bookkeeping is not compensation" for why the rejection does not reach it.

#6 and #4 stay paired: both rewrite `attach`'s command sequence, and splitting them means
rewriting the same block twice. #4's mechanism is a step in #6's transaction.

## What this does not settle

- **Over-suppression under `NEW_NODE`.** A user's own window reporting the dock's `app_id` is
  invisible to `surfaces()` for the duration of an attach. Chosen knowingly on the cost
  asymmetry above, but it is a real regression in that window, and `wm.dock.pending_suppression`
  exists to turn it off.
- **Over-*reach* under `NEW_NODE` + `RECLAIM`.** The same coarse predicate, but the cost is a
  window killed rather than hidden, and it lands on a user who did nothing but launch a panel
  by hand during a failed attach. This is why `wm.dock.late_dock` defaults to `LEAVE`; it is not
  fixed, it is off. `PER_SURFACE_APP_ID` removes it by construction — the sharpest reason yet to
  revisit that default, below.
- **The mark's durability has two known holes, both open: #14 and #15.** The pinned predicate
  above (prefix + parseable `con_id`) closes the half of #15 that #9 would otherwise carry
  forward, and the table mitigates #14 in-session, but neither issue is fixed here and #9 must
  not assume the mark survives a second attach on one surface. This note is the single place the
  *recognition* predicate is decided; it is not the place #14's repair is decided.
- **Nothing collects `changes` and nothing drives `reapOrphans` (#18).** The claim mechanism, and
  therefore Decision 2's "something outside the transaction finishes the job", is specified
  against an owner that does not exist yet. See "The third outcome has no owner".
- **`no_focus` remains unrevocable, and so does an `exec` in flight.** Both are declared
  exceptions to Decision 2 rather than solved problems. `no_focus` is made non-default and
  non-cumulative; the late dock is reclaimed after the fact rather than prevented. Neither is
  fixed — sway cannot fix either.
- **Reconnecting after a compositor restart** is not designed here. This note defines the
  session boundary and requires the table be discarded at it; it does not say how the manager
  acquires a successor connection, and today it does not try (`commands` is
  `by lazy { connect() }`). Whoever adds reconnect owns two things: acquiring the successor
  connection, and clearing the table. The third — **making the boundary observable at all** —
  landed as #20; `changes` now fails with `CompositorSessionEnded` where it used to go silent, so
  the trigger exists and is unclaimed. And awakener's `SWAYSOCK` is itself stale across the
  boundary, since the default socket path carries the compositor's pid. Filed as part of #18.
- **Changing `wm.dock.mark_prefix` while docks are standing** orphans them permanently: the
  adoption scan will not find them and nothing else can. Sharp edge for the #9 implementer to
  at least document.
- **Whether `PER_SURFACE_APP_ID` should be the default.** Still untouched here, on purpose —
  but the balance has moved. Two of this note's own decisions (the reservation, and `RECLAIM`)
  are exact under it and coarse under `NEW_NODE`, and one of them is destructive when coarse.
  Whoever revisits the default should weigh that against `NEW_NODE`'s only advantage, which is
  asking nothing of the dock program.
- **The probe's Hazard 1 open question** — switching tabs while typing in the dock, and where
  returning should land — is a UX call and is still open.
- **Verification of the *code*.** This note ships no production change. Every one of the four
  PRs needs a live sway run with `AWAKENER_REQUIRE_SWAY=1` and executed counts read out of
  `wm/build/test-results/jvmTest/*.xml`; "BUILD SUCCESSFUL" does not distinguish a passing
  `:wm` suite from a skipped one.

## How the claims here were checked

sway **1.12**, foot **1.27.0**, headless wlroots backend on kaladin, driven over IPC — the
same shape as `docs/findings/2026-07-30-sway-binding-probe.md`:

```sh
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 \
  SWAYSOCK=$D/s.sock sway -c $D/sway.conf      # sway.conf: xwayland disable
```

Note that a client speaks the i3-ipc wire format directly rather than shelling out to
`swaymsg`, because several of these probes are about what a *held connection* observes, which a
per-command process cannot see. `SWAYSOCK` must be a short path: it becomes a `sun_path` and is
silently truncated past 108 bytes.

**Where the question is about awakener's client rather than about sway, the probe runs through
the real `SwayWindowManager`** (throwaway tests under `wm/src/jvmTest`, run against a
`SwayHarness` sway, deleted afterwards; nothing was pushed). That distinction is the one this
note kept getting wrong: sway signalling something correctly and awakener's client acting on it
are separate facts, and only the second one is what an implementer reads.

Raw i3-ipc (`R*`):

- **`con_id` monotonic, never recycled** — 96 windows spawn-all/kill-all, then 20 rounds of
  interleaved churn with `split` containers refilling freed ids. 236 containers, 5–240
  contiguous, zero reuse.
- **Cross-session `con_id` collision** — two sequential sway sessions under one surviving
  client. Session A's dock id is session B's browser. Fresh sessions allocate from 5.
- **No in-place `restart` on 1.12** — `restart` returns `Unknown/invalid command 'restart'`
  with `parse_error:true`; only `reload` exists. `no_focus [...] disable` returns
  `success:true` because it installs another rule; there is no revoke verb.
- **R1 — connection loss, three ways** — kill sway under a client holding several connections. A
  blocked event connection gets EOF immediately. An idle connection gets ECONNRESET/EPIPE on its
  next request. **An in-flight request can still succeed**: `GET_TREE` written immediately before
  `SIGKILL` returned a complete 4473-byte reply on **1 of 6** trials, and on **6 of 6** when sway
  was given 5 ms to serve it into the socket buffer first. That is what disproved "it cannot
  succeed against a dead socket".
- **The default socket carries the compositor pid** — start sway with `SWAYSOCK` unset →
  `$XDG_RUNTIME_DIR/sway-ipc.<uid>.<pid>.sock`.
- **The late dock** — tabbed workspace, `split horizontal`, `exec` a dock that maps 4 s later,
  unwind at 1.5 s. The unwind is clean; the dock then arrives as an unmarked third tab.
- **Compensation failure taxonomy** — four commands compared on `success` / `parse_error` /
  `error`, including `split none` on a node with siblings, which is the unwind's own
  normalisation failing in the unwind's own scenario.
- **`REFOCUS_AFTER_MAP`** — the real attach sequence with a `window` subscription. `new` and
  `focus` in the same millisecond, 73–78 ms transient across three runs, no steal-back over 3 s.

Through the real `SwayWindowManager` (`J*`):

- **J1 — what a collector observes when the compositor dies.** Collect `changes`, `SIGKILL` sway,
  wait 2 s: `collectorActive=true flowCompleted=false failed=null`. The flow neither completes
  nor fails; a collector cannot tell a dead compositor from an idle desktop. *(Fixed by #20,
  which keeps J1 as a test — both states, since the idle one has to keep reading exactly this.)*
- **J2 — the claim predicate against the ordinary retry.** A failed attach at stock defaults with
  the dock command `sh -c 'exit 1'`, then a normal attach. The second attach's own dock
  (`con_id=7`, `marks=[awakener_dock_5]`, in the table) satisfies the un-narrowed claim predicate
  31 ms into the 5 s grace.
- **J3 — is the dock marked when its `window::new` arrives?** No: the node is already in the tree
  with `marks=[]` at that instant, and reads `[awakener_dock_5]` only after `attach` returns.
  This is why the claim's exclusion has to be the in-memory reservation and cannot be the mark.
- **J4 — the mark namespace is global and user-facing.** Marking a second node with an existing
  mark takes it off the first (`5 -> marks=[]`, `6 -> marks=[awakener_dock_999]`), which is #14
  at the sway level; and adding `awakener_dock_notes` to a real application window removed it
  from `surfaces()` entirely, which is #15.
