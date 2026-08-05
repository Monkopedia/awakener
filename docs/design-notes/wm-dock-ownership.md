# Design note: who owns a dock node

**Date:** 2026-07-30, revised 2026-07-31 (three times, then amended for #20, again for #18, again
for #14/#15, and again for #35) ·
**Scope:** `:wm` only · **Status:** decided; binds issues #4, #6, #7, #9, and requires #18 —
which has since landed the collector, in part. What it does and does not drive is stated under
"The third outcome's owner, half built". The mark predicate it pins was amended by #14/#15's fix;
"The mark predicate, pinned" is still the one place to read it from.

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
the mark. See "The mark predicate, pinned" below, which is the one place #9 should read it from —
and which #14/#15's fix amended, since both were the same defect in what the mark *named*.

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
**not reachable from `resolve`**. `resolve` derives its key from the tree and answers from the
durable registry; a session-scoped `con_id` table has no business in an answer that must survive
a reboot. If the table ever appears in `resolve`'s path, the durability story has rotted.

> **Amended 2026-08-04 by #52.** The tripwire fired, on code that was not wrong. As built, the
> path was `resolve → keyFor → surfaces() → dockedTo`, and `dockedTo` both reads *and writes* the
> table. Durability was intact throughout — the table holds no agent, so the answer was always
> `:registry`'s — which is exactly the problem with a tripwire phrased as "does this function
> appear in that call path": it cried wolf about the property the whole registry exists for, and
> a tripwire that cries wolf gets switched off.
>
> Restated **and** made true, rather than either alone. What was genuinely session-dependent was
> narrower than the wording suggested and worth fixing on its own: `surfaces()` filters out docks
> and reserved windows, so a surface the table was hiding resolved as a **Drab** however durably
> it was bound — and a caller acting on that mints a second agent for a surface that already has
> one. Two ways to be hidden, both real: a recognition the table latched at some past read (the
> stated residual under `wm.dock.reap_evidence`), and an in-flight attach's reservation over a
> shared dock `app_id` (`wm.dock.identity=NEW_NODE`). So `resolve` now reads the node out of
> `get_tree` and derives the key from it directly. Its whole path is one tree read and one
> registry lookup, the table is unreachable from it, and the tripwire is now a grep somebody can
> run rather than a promise somebody has to keep.
>
> The cost, disclosed: `resolve` no longer answers *nothing* for a dock. A dock is an ordinary
> node to it and resolves to whatever the registry holds under the key its `app_id` gives —
> nothing, unless something bound that key. Callers get their surface ids from `surfaces()`,
> which excludes docks either way. `wm.resolve.key_source=ENUMERATION` restores the old route for
> a caller who wants `resolve` to refuse a dock outright, at the price of the session dependence
> it comes with.

### What it keys on

`SurfaceId` (the dock's `con_id`) → `{ surface: SurfaceId, origin }`, where `origin` is
`STOOD_UP` or `ADOPTED`, and nothing else.

> **Amended 2026-07-31**, from `{ surface, agent: AgentId, appId, markApplied: Boolean }` plus a
> secondary index by `surface`. That shape was reasoned, not measured, and #9's implementation
> found three of its four fields unwritable or unread. `agent` is not known when the entry is
> created: the bind happens *after* the tree edit, deliberately and for the reason under "Reads
> outside the lock", so an entry recorded inside the lock has nothing to put there. `markApplied`
> and the by-surface index have no reader until the hotkey path exists, and a field nothing reads
> is a field nothing keeps honest. `appId` had no reader either, and an *adopted* entry cannot
> supply one: a dock recognised from its mark is whatever node wears the mark, and sway reports
> no `app_id` at all for an xwayland window. Add each back with the caller that needs it.
>
> **Amended again, same day:** `origin` is the one field that arrived with its caller. `surfaces()`
> does not care where a claim came from; `reapOrphans` does, because it kills what it acts on, and
> a recorded adoption is a recognition latched at some past read rather than evidence that exists
> now. See "Recording is one-way" below. This is the rule working as intended rather than an
> exception to it.

**Adoption records; it does not merely answer.** "Tree has a marked node the table does not know
— adopt it", below, is a write. An implementation that computes the union freshly on every read
gives a different answer, and the difference is this note's expensive false negative: measured
2026-07-31 through two real `SwayWindowManager`s against one sway 1.12, a dock attached, awakener
restarted, the surface enumerated (correct — the mark carries it), then a second dock attached to
the same surface. sway moves the mark (#14), and the read-time union has nothing left to answer
from, so the first agent panel comes back as a bindable surface — while being invisible to
`reapOrphans`, which shares the predicate, so nothing can take it down either. A recorded
adoption survives the mark moving; a recomputed one does not.

> **Amended 2026-07-31 by #14's fix.** A second attach no longer moves the mark — that was the
> mark naming the surface, and it now names the dock. The measurement stands as taken and is
> reproducible under `wm.dock.mark_scheme=SURFACE`; what it is no longer is the *likeliest* way a
> dock loses its mark. The argument for recording is unchanged and is now the general one: the
> mark namespace is the user's too, so a mark can go without awakener doing anything, and a
> recognition that leaves no record behind hands the panel back the moment it does.

**Recording is one-way, and that is the price.** *(Added 2026-07-31, measured on #23's second
head with the pre-adoption code as the control in the same worktree.)* Nothing withdraws a
record, so recognition outlives the evidence that produced it. Run #15's residual — a user's own
mark shaped exactly like that window's *own* dock mark, on a genuine application
window — through one enumeration and the window is a dock for the life of the process:
`swaymsg unmark` used to hand it straight back (`[5, 6]`) and now does not (`[5]`). One
enumeration while the mark is on is what arms it; mark and unmark with no read in between leaves
the window alone. `wm.dock.recognition=MARK_ONLY` releases it live (measured `[5]` → `[5, 6]`),
and so does restarting awakener. Those are the whole of the recovery.

What that must not become is a **kill**. Measured on the same head before the gate below existed:
the surface named in the removed mark closes, a sweep runs, and `reapOrphans` destroys the user's
window on the strength of a mark that is no longer there. This note's own bar, two hundred lines
down, is that `RECLAIM` under `NEW_NODE` *"costs a user's window, which is not recoverable at
all"*. So the destructive path asks a narrower question than enumeration does: **a sweep kills
only on evidence that exists at the moment of the sweep** — a dock mark on the node now, or an
entry with `origin = STOOD_UP`. `wm.dock.reap_evidence` carries the choice, defaulting to that
(`CURRENT`); `RECOGNITION` is the older, wider behaviour.

**That gate bounds the latch and nothing wider, and the difference is a window.** It is about a
recognition whose mark has *gone*. While a mark is still on the node, it is precisely the
evidence `CURRENT` asks for, so the sweep kills on it — measured on the current head, probe J7.
`CURRENT` is what makes removing the mark survivable; it is not what makes the mark itself safe.
The residual that survives it is stated under "What it does not close", on the destructive side of
this note's bar rather than the recoverable one.

> **Amended 2026-07-31 by #35, which added a third value.** `STOOD_UP` reaps only on an entry this
> process wrote when it stood the dock up, so under it *no mark is evidence for a kill at all* —
> the only value for which that is true, and the only answer to the residual below that does not
> depend on how hard a mark is to write. Its price is what the mark is for: a dock adopted after an
> awakener restart is never reaped, so its panel stands when its surface closes. `CURRENT` remains
> the default because the default mark scheme now makes that residual need a mark nobody writes by
> accident. See "What it does not close", which is where the choice between the two is made.

The gap `CURRENT` leaves is exactly one case, and it is the mirror of the false negative above: a
dock **adopted** after a restart whose mark has since gone has neither kind of
current evidence, so it stays out of enumeration — no agent is minted for the panel, which was
the expensive half — but its panel is left standing when its surface closes and has to be closed
by hand. A leftover panel is recoverable; a destroyed window is not. That asymmetry is the whole
argument, and it is the same one that makes recognition a union in the first place. *(Amended
2026-07-31: this named a later attach moving the mark (#14) as the way to get there. Under the
default `wm.dock.mark_scheme` it takes a hand-run `unmark`; under `SURFACE` the second attach
still does it.)*

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

> **A dock mark is the configured prefix followed by the dock's own `con_id`, `_for_`, its
> surface's, `_`, and sixteen lowercase hex digits — and it counts only on the node whose `con_id`
> it names.** One predicate, used by `surfaces()`, by the adoption scan, and by `reapOrphans`
> alike. A node carrying a prefix-matching mark that is not *its own* dock mark is **not** a dock;
> it is reported (per #15's option 4) rather than silently hidden in one place and skipped in the
> other.

*(Amended 2026-07-31 by #14/#15's fix. It read "the configured prefix followed by a parseable
`con_id`" — the surface's — which is what both issues then turned on, so the two are one repair
and are stated as one predicate. `wm.dock.mark_scheme=SURFACE` is the old form, kept reachable.
Below, in strikethrough-by-amendment rather than by deletion, is what that predicate did and did
not fix, because it is the argument for the current one.)*

*(Amended again 2026-07-31 by #35, which added the hex field. The two `con_id`s alone were a shape
a hand reaches — that is the residual under "What it does not close" — so the mark now carries a
nonce as well. **It is checked by shape and never by value**, and that is not a shortcut: the
process that reads a mark is routinely a later awakener that never saw it written, which is the
whole of what a mark is for, so a check against a remembered value would strand every standing
dock on every restart. `wm.dock.mark_scheme=DOCK_AND_SURFACE` is the previous form, kept reachable
beside `SURFACE`.)*

This is #15's option 1, taken here rather than left to the implementer, because #9 rewrites both
call sites and picking `surfaces()`'s current form would carry #15 into the new design.

**Why the dock's `con_id` is in it, and not only the surface's.** Two reasons, one per issue, and
they are the same fact read in two directions — a mark is an identifier in a namespace shared with
the user, so it has to be unique to what it identifies and it has to say what that is.

- **#14.** sway's mark identifiers are globally unique: marking a second container with an
  existing identifier removes it from the first, measured directly (probe J4, below). A mark
  derived from the *surface* is therefore a name that two docks of one surface both want, and
  awakener's own second attach takes it off the first dock. Adding the dock's `con_id` makes the
  string unique by construction, since a `con_id` is unique within a session and a dock has one by
  the time it is marked.
- **#15.** Because the mark now names the node it belongs on, awakener can *check* that — and a
  mark on any other node is not a dock mark at all. That is what the pinned predicate could not
  do: `<prefix>7` was a dock mark on whatever node wore it, so a user's own
  `mark awakener_dock_7` hid their window, and once #18's collector began sweeping on every
  window close, **destroyed it** when node 7 closed. Measured against `d576d28`: enumeration
  dropped the window, and the sweep killed it.

The two issues therefore interact rather than merely coincide: #14's repair *is* #15's, and
fixing #14 alone — a second mark, or a `<dockId>_for_<surfaceId>` string recognised anywhere —
would have left every dock carrying a mark this predicate reported as unrecognised. Requiring the
node to be the one named is what keeps one predicate rather than two.

~~**What it does not close, and it is on the destructive side of this note's own bar.** A user mark
that is `<prefix><that window's own con_id>_for_<some con_id>` passes the self-check — the mark
does name the node it is on — so the one predicate calls that window a dock. It is hidden from
enumeration, and hidden for the life of the process once adoption records it. It is also
**destroyed**: the mark is on the node at the moment the sweep looks, so `reap_evidence=CURRENT`
is satisfied, and when the `con_id` after `_for_` closes `reapOrphans` kills the user's window —
which #18 gave a caller on every window close.~~

*(**Closed 2026-07-31 by #35**, and closed in the shape stated: that mark is no longer a dock mark
under the default `wm.dock.mark_scheme`, so the window is enumerated, its mark is named in
`unrecognisedDockMarks`, and the sweep has nothing to act on. Both halves were run red against
`0e2446b7` before the fix — `expected:<[5, 6]> but was:<[5]>` for the hiding, and the window gone
from the tree for the kill. The measurement below stands as taken, against
`wm.dock.mark_scheme=DOCK_AND_SURFACE`, which is what it is now a fact about. What replaces it is
"What #35 closed, and what it did not", immediately after.)*

Measured on this branch, through the real `SwayWindowManager` against sway 1.12 (probe J7): a
second application window marked `awakener_dock_<its own con_id>_for_<the first window's con_id>`,
the first window killed, one sweep, and the marked window is gone from the tree. Asserted then by
`SwayBindingTest.the residual the self-check leaves is a destroyed window, not a hidden one`, whose
KDoc said in as many words that it recorded a defect rather than a fix; that test went red when
#35 landed and is now `SwayBindingTest.a user mark naming its own window and a dead con_id no
longer costs that window`, asserting the opposite.

So what the self-check buys is the **trigger, not the consequence**. Before it, any
`<prefix><live con_id>` on any window would do; then the user had to have written their own
window's `con_id` into the mark. That was a real narrowing and it was the whole of that change's
claim. Everywhere else this document says *a window hidden is recoverable and a window destroyed
is not* — that residual was the second kind, and it was filed here as such rather than under the
recoverable half.

### What #35 closed, and what it did not

The mark now ends in a nonce field, and `wm.dock.reap_evidence` has a value under which no mark is
evidence for a kill. Both are needed, because they answer different questions, and the reason the
first is not enough is a measurement rather than a caution:

> **Nothing in sway's tree is evidence a desktop cannot write.** sway sets a mark through exactly
> one thing — `RUN_COMMAND` — on the socket `swaymsg` speaks, with the same parser. There is no
> privileged channel. So "a mark shape a user cannot forge" **does not exist**, and neither does a
> structural substitute, since the layout is `swaymsg`'s to write as well.

Measured directly (probe J8, raw i3-ipc against sway 1.12), because "a shape sway will not
round-trip" was the other half of the option this issue offered and it had to be tested before
being designed against:

- **Length is not a limit.** Marks round-trip byte-identical at 8, 16, 32, 42, 64, 128, 256, 512,
  1024, 4096 and **16384** characters. Every one came back `success:true` and compared equal.
- **Nor is the character set.** ``- . : / @ # % [ ] { } + = ~ ^ | \ $ ` * ?``, a single quote, a
  quoted space, a literal `\x01`, and multi-byte UTF-8 (`✦`, `ÿ`) all round-trip verbatim. The only
  things that do not survive are the *command* parser's separators — `;`, `,` and a newline end the
  command, and a tab collapses to a space — and those are limits on writing any command, not on
  what a mark may hold.
- **A hand writes awakener's exact mark.** `swaymsg '[con_id=5] mark --add
  awakener_dock_5_for_5_9f3a1c7e0b2d8465'` returns `success:true` and reads back verbatim.

So the honest statement of what the nonce buys is **accident, not forgery**: `<prefix><own
con_id>_for_<con_id>_<16 hex>` is not a string anybody arrives at without meaning to, and that is
the whole claim. A nonce copied out of `swaymsg -t get_tree` and re-marked onto another window is
still a dock mark and is still destroyed by the sweep —
`SwayBindingTest.a nonce-shaped user mark is still destroyed, and only the reap evidence closes
that` pins that, and its KDoc says it records current behaviour rather than proving a fix, as its
predecessor's did.

**Why the nonce is per dock and not per process.** A per-process nonce is a field with no reader.
The only question it could answer — "did *this* awakener stand that dock up" — is the one
`origin = STOOD_UP` already answers, from memory, without depending on a string the desktop can
write. This is the note's own rule about fields nothing reads, applied before the field existed.

**And why the value is never checked.** The issue's first constraint, and it decides the design: a
mark exists to be read by a process that did not write it. A nonce the successor does not know
would turn every standing dock into a stranded one on every awakener restart — the exact failure
the mark is there to prevent. Verifying the *shape* costs nothing across a restart and is what
makes the mark still adoptable; `SwayBindingTest.a dock adopted after a restart is left standing
under a stood-up requirement` exercises a fresh manager reading a mark it never wrote.

**The consequence, narrowed as well.** `wm.dock.reap_evidence=STOOD_UP` reaps only on an entry
this process wrote when it stood the dock up, so a forged mark — deliberate or otherwise — costs
nothing at all. It is **not** the default, and this is the trade: its price is the mark's own
purpose, since a dock adopted after a restart is then never reaped and its panel stands when its
surface closes, to be closed by hand. A leaked panel against a destroyed window is the asymmetry
this note settles everything else on, and it would select `STOOD_UP` outright were the destroyed
window still reachable by accident. It is not, so the default stays `CURRENT` and `STOOD_UP` is
the lever for a desktop that wants no tree evidence to be destructive at all.

**Migration.** #34 established by measurement that a permissive read over the old shape re-opens
the wider kill path; a third scheme inherits that, and does not take it. The rule is unchanged and
now holds across three values: no scheme reads another scheme's mark as one of its own
(`DockTableTest.no scheme reads another scheme's mark as a dock mark`, all six ordered pairs), so
an upgrade over standing docks strands them into a **leak** — bindable, named in
`unrecognisedDockMarks`, never reaped — and never into a kill.

**A scheme flip is a restart, not a flip — and an upgrade over standing docks is one.**
`wm.dock.mark_scheme` decides reading and writing together, so a live sway session that outlives
the awakener process holds marks the successor no longer reads. **The choice made here is: do not
adopt them.** Reading the old shape as well would mean recognising `<prefix><any live con_id>` on
any node again, which is the destructive defect #15 filed and this change closes — a migration
read re-opens the kill path on every window in the session, so the migration would cost strictly
more than the strand it repairs.

What an upgrade therefore costs, measured on this branch (probe J7): the stranded dock is
**enumerated as an ordinary bindable surface**, so a hotkey on it mints a Lifeless for an agent
panel and writes it to the durable registry; its mark is **named in `unrecognisedDockMarks`**, so
it is diagnosable rather than silently lost; and it is **never reaped**, because a mark this build
does not recognise is not evidence for a kill. The reverse mistake cannot happen either — no scheme
reads another scheme's mark as a dock mark of its own (`DockTableTest.no scheme reads another
scheme's mark as a dock mark`, all six ordered pairs) — so a strand costs a leak and never a kill,
which is the direction this note demands. *(Amended 2026-07-31 by #35: this said "neither scheme …
since one contains `_for_` and the other cannot", which was the argument for two values. There are
three now, and the property is asserted exhaustively rather than argued from one substring. The
live half is `SwayBindingTest.a dock marked under the other scheme is reported and left standing`,
which now drives the upgrade this change actually causes — `DOCK_AND_SURFACE` marks read by a build
whose default wants a nonce.)*

The recovery is by hand or by flag: close the stranded panels, or set `wm.dock.mark_scheme` to the
value they were marked under, let the docks be recognised again, close them, and flip back.
`DOCK_AND_SURFACE` and `SURFACE` are here for that, and each has its own price, stated at the
value. Same shape as `wm.dock.mark_prefix`, and the same advice: move it with no docks standing.

**#14's other option is not taken.** Refusing a second attach on an already-docked surface (#14's
option 2) is a change to `attach`'s contract — it is currently documented as safe to call
concurrently, and the refusal would also forbid a deliberate second panel — rather than a repair
of the defect, which was the mark. It stays available to whoever owns the hotkey path.

`wm.dock.reap_evidence` does **not** reopen this. The two call sites still answer "is this node a
dock" from the one predicate; what the sweep additionally asks is whether the evidence is current —
and, under `STOOD_UP`, whether it is awakener's own — because it is about to kill. Narrowing the
*action* is not the same as the two sites disagreeing about the *predicate*, which is what left a
window unreachable by every path at once.

Two things the *pinned* predicate did not fix, both measured through the real
`SwayWindowManager` on sway 1.12 (probe J4). **Both are closed by the amendment above; kept
because they are the argument for it.**

- **The mark is not durable across a second attach on one surface (#14).** It is derived from
  the *surface's* `con_id`, and sway's mark namespace is global, so applying it to a second dock
  takes it off the first — measured directly: after `[con_id=5] mark --add awakener_dock_999`
  then `[con_id=6] mark --add awakener_dock_999`, node 5's marks are `[]` and node 6's are
  `[awakener_dock_999]`. **The table mitigates this in-session** — the union still recognises the
  now-unmarked first dock, which is a point in this design's favour and worth saying. *(Amended
  2026-07-31: the rest of this bullet said that after an awakener restart the adoption scan
  cannot see that dock at all, and that is true only of an adoption that leaves no record.
  Because adoption writes an entry, a dock enumerated even once after the restart stays
  recognised when something takes its mark off it — measured through two managers against one
  sway, and asserted by `an adopted dock stays a dock once its mark is taken off it`. That test
  drove the loss with a second attach while the mark named the surface; it takes the mark off by
  hand now, because under the default scheme a hand is the only thing that takes one off, and what
  it is about — that an adoption is a write — is unchanged either way. What is genuinely lost is
  narrower: a dock whose mark goes before anything has enumerated it — a hand-run `swaymsg unmark`,
  say — since there was nothing there to adopt it.)*
- **The pinned predicate narrows #15's trigger and widens its consequence.** A user mark that
  happens to be `awakener_dock_<some live con_id>` still hides a real window — and measured on the
  unpinned predicate a mark as ordinary as `awakener_dock_notes` removed a genuine application
  window from `surfaces()` outright, which the pinned one does not. *(Amended 2026-07-31: "still
  hides a real window" understated it once adoption records. The hiding is no longer transient —
  removing the mark does not release the window, because a recorded node is never asked about its
  marks again — so it lasts for the life of the process, and `wm.dock.recognition=MARK_ONLY` or an
  awakener restart is the only way back. It stops there **for the latch, and only for it**:
  `wm.dock.reap_evidence=CURRENT`, the default, keeps the sweep from killing a window on a
  recognition with no live mark and no stood-up entry behind it — but while the mark is still on,
  it is exactly the evidence `CURRENT` asks for and the sweep destroys the window. See "Recording
  is one-way", and "What it does not close" above for the residual that survives the amendment.)*
  *(Amended again 2026-07-31 by #35: that residual is closed for the shape it named. The mark now
  carries a nonce field as well, so `<prefix><own con_id>_for_<con_id>` is not a dock mark at all
  and neither hides nor kills. What survives is a mark whose nonce field is well formed, which is
  no longer a shape reached by accident — and `wm.dock.reap_evidence=STOOD_UP` is what makes even
  that harmless. See "What #35 closed, and what it did not".)*

~~**Open question, not settled here:** whether #14's fix is a second mark
(`<prefix><dockId>_for_<surfaceId>`), a refusal to attach twice to one surface, or both. That
belongs on #14.~~ *(Settled 2026-07-31: the mark, in one string rather than two, and recognised
only on the node it names. See "Why the dock's `con_id` is in it" above. The refusal is not
taken.)* What this note fixed was that #9 must not *assume* the mark is durable while #14 was
open; #14 is closed by the commit that amended this section.

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
  predicate (prefix + its own `con_id` + `_for_` + its surface's + `_` + a nonce-shaped field)
  becomes an entry, surface id parsed from the suffix. *(Amended 2026-07-31 by #35, which added the
  last of those. This case is exactly why the nonce is verified by shape and never by value: the
  successor has no idea what the predecessor drew, and a check that needed one would strand every
  dock here.)*
  That scan is the adoption rule above and it subsumes what `reapOrphans` open-codes. This is
  what the marks are *for*, now that they are no longer the primary truth. *(Amended 2026-07-31:
  this said "subject to #14, which is the one case where a dock has no mark left to be adopted
  by". #14 is fixed — a mark naming the dock is one no other dock takes — so the remaining ways
  to reach a mark-less dock are a hand-run `unmark` and `wm.dock.mark_scheme=SURFACE`.)*
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
`SwaySessionEndTest`.

> **Amended 2026-07-31 by #18.** The rest of this said "nothing observes it yet". Something does
> now: `SwayWindowManager` starts a repair collector on the scope it is constructed with, and that
> collector's `catch (CompositorSessionEnded)` discards the whole table — entries and reservations
> — before recording the reason in `repairs`. Measured through the real manager against a
> `SIGKILL`ed sway 1.12: with a dock standing, the table holds
> `{7=DockEntry(surface=5, origin=STOOD_UP)}` before the kill and is empty after it, and the same
> assertion against the pre-#18 code fails with that entry still in place
> (`SwayRepairTest.the dock table is discarded when the compositor dies`).
>
> `commands` is still `by lazy { connect() }`, so the manager remains permanently broken after the
> boundary rather than quietly wrong — discarding the table is the half of the boundary #18 built,
> and acquiring a successor connection is still nobody's. See "Reconnecting after a compositor
> restart" below, which is now the only unowned piece.

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
  from a stale table against a fresh tree. That flag's description now says so, alongside the
  orphan-handling caveat it already carried — and with events off the collector has nothing to
  collect and returns, so *nothing* discards the table for the life of that manager, which the
  description also says. Asserted by `SwayRepairTest.with events off nothing is collected and
  nothing is repaired`.
- **Today this is latent, not live**, because `commands` never reconnects: a sway restart
  leaves the manager permanently broken rather than quietly wrong. That is not a defence —
  reconnection is table stakes for a daemon, and it is the change that arms this. #18 has since
  taken the discard out of that commit's way: the table is cleared on the signal already, so
  **whoever adds reconnect (#33) owns acquiring the successor connection and nothing else here.**
  Nothing in the queued PRs retrofits an invalidation rule this note did not ask for.

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
  if the table is ever suspected of hiding a real surface — and since adoption records, it is
  the *only* thing short of a restart that releases one; see "Recording is one-way".
- `wm.dock.reap_evidence` = `STOOD_UP` | `CURRENT` | `RECOGNITION`, **default `CURRENT`.** *(Added
  2026-07-31 with #23; `STOOD_UP` added the same day with #35.)* What the orphan sweep must see
  before it kills a node enumeration calls a dock: an entry this process wrote when it stood the
  dock up and nothing else (`STOOD_UP`), a mark on it now or such an entry (`CURRENT`), or nothing
  further at all (`RECOGNITION`, the wider, older behaviour). The default costs one case — an
  adopted dock whose mark has since gone is no longer reaped — and buys the case where
  `RECOGNITION` destroys a user's window. `STOOD_UP` is the only value under which no mark can cost
  a window, since it asks nothing of the tree; its price is that *no* adopted dock is ever reaped,
  which is every dock that outlived an awakener restart. It is not the default because the default
  mark scheme already puts that kill out of accidental reach — see "What #35 closed, and what it
  did not", where that trade is made.
- `wm.dock.mark_scheme` = `DOCK_SURFACE_AND_NONCE` | `DOCK_AND_SURFACE` | `SURFACE`, **default
  `DOCK_SURFACE_AND_NONCE`.** *(Added 2026-07-31 with #14/#15; the nonce value added the same day
  with #35.)* What a dock's mark says after the prefix, for reading and writing together. The
  default is `<dockId>_for_<surfaceId>_<16 hex digits>`, recognised only on the node it names and
  only with a nonce-shaped trailing field — unique per dock, which is #14; self-checking, which is
  #15; and not a shape written by accident, which is #35. The nonce is checked by shape and never
  by value, because the reader is routinely a later awakener. `DOCK_AND_SURFACE` is the same
  without the nonce, and its price is a destroyed window: `<prefix><that window's own
  con_id>_for_<any con_id>` is a dock mark, and the sweep kills that window when the `con_id` after
  `_for_` closes. `SURFACE` is the original `<surfaceId>` and is worse again: under it any
  `<prefix><live con_id>` is a dock mark on whatever node wears it, so a user's own
  `awakener_dock_7` is hidden and then killed by the sweep when node 7 closes. Both older values
  are the recovery path if an upgrade lands while docks are standing — a flip strands every mark
  written under another value — and each is worth its price only in a session with no marks under
  this prefix that awakener did not write. The default's own residual is destructive too, and
  narrower again; see "What #35 closed, and what it did not".
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
the claim*, and which today stands unowned **because no claim is filed and none is read** — see
the amendment on that section. *(Amended 2026-07-31 by #18: the missing collector is no longer
part of that sentence. `changes` has one now; it reads close events and the session boundary, and
there is still no claim for it to consult.)* No half-built container is ever observable to a
caller.

> **Amended 2026-07-31, with #6's fix for the map-deadline race: the boundary is the unwind's
> last look at the tree, not the moment `awaitWindow` gave up.** A dock that has mapped by then
> is killed like any other, *whether or not the attach ever identified it* — the node id is
> assigned only once `awaitWindow` returns, so across the whole map deadline `attach` holds no
> `con_id` for a window it may already have spawned, and the record that stands in for one is the
> `app_id` + `standing` pair filed with the `exec`. Without that, the likeliest failure of all —
> the deadline expiring, which is also when a slow dock is likeliest to be mapping — skips the
> kill and then has its flatten refused on a container that has just acquired a second child,
> which is #6 arriving through #6's own repair. Found in review by sweeping `sleep` offsets
> across the deadline — 1 of 4 and 1 of 8 genuine timeouts — and since made deterministic; see
> probe J6.

That is why the container half is stated as an outcome and not as an invariant of the moment of
failure: a `split none` is a command sway can refuse, and it refuses this one whenever a window
arrives between the tree read that checked and the command that acted. The unwind therefore makes
that check-and-flatten twice, since the refusal *is* the news that the dock arrived — see the
narrowing under "The late dock".

**The rule the race generalises to, since it was found twice:** *a compensation must not depend
on a fact recorded on the far side of the round trip that created the thing it compensates.* The
node id is the case above. The other is the `split horizontal` itself — its "I created a
container" flag was set after the command's acknowledgement, so a cancellation landing on that
acknowledgement left the container built and the unwind unaware of it, the same #6 leftover
through a much narrower door. Both are fixed by recording *before* the command and rolling back
only where sway's own rejection proves nothing happened; every other failure means the edit may
have landed, and a compensation attempted against an edit that did not happen is cheap where an
edit left standing is not. Audited across the rest of `attach`'s sequence, since the shape is
worth more than the two instances: the reservation and the table entry are each recorded in the
same non-suspending step as the thing they name, so there is no round trip for a failure to arrive
inside, and the mark needs no record at all — it dies with the window the kill takes. Two others
do have the shape, and neither is fixed the same way:

- **the `no_focus` rule's record**, written after sway acknowledges it. A cancellation landing on
  that acknowledgement installs a rule nothing remembers and the next attach issues a second —
  #4's accumulation through a one-round-trip door. Left as it is *deliberately*, because the
  polarity that helps everywhere else hurts here: a rule remembered but never installed suppresses
  nothing, for every dock under that name, for the rest of the session, and no correction runs
  under `NO_FOCUS_RULE` to notice. One redundant unrevocable rule is the cheaper residue, and it
  suppresses exactly what the first already did.
- **the registry bind**, which cannot be recorded before the call at all — see "What this does not
  settle".

That is deliberately narrower than "there is no third outcome", because there is one, and
promising two is how it gets implemented away. Two things `attach` does are not tree edits and
are not revocable:

1. a `no_focus` rule, under `NO_FOCUS_RULE` — sway has no verb that takes one back;
2. **the dock program it already `exec`'d.**

`exec` returns `{"success": true}` and nothing else — no pid, no handle — so there is nothing
to cancel it with. On the timeout path, which is the most likely attach failure and the one #6
was reproduced on, the dock has not mapped, so there is nothing to `kill`, and nothing stops it
mapping afterwards. *(Amended 2026-07-31: "the dock has not mapped" is a fact about this probe
run — its dock was told to map 4 s late against an unwind at 1.5 s — and is false in general. A
dock is at its most likely to be mapping exactly as the deadline it overran expires, which is
what the sweep across the deadline found and what the narrowing under "The late dock" now
covers.)* Measured on sway 1.12 with the real dock shape — tabbed workspace,
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

> **Amended 2026-07-31, in the PR that landed #6 and #4: none of this section is built.** The
> reservation is released on both paths rather than converted, no claim is ever filed, and
> `wm.dock.late_dock` was not introduced. #9 landed the table, the reservation and its
> unconditional eviction (PR #23) and stopped there; #6 was assigned only the tree compensations
> and did not widen to take it on, since a claim nothing reads is a flag whose default behaviour
> cannot be exercised — which is the same argument that already set that default to `LEAVE`.
> **So the third outcome is real and unowned**: a dock that maps after the unwind has finished
> stands as an unmarked, untabled panel that `surfaces()` reports as bindable. What follows is
> therefore the design for whoever builds the collector (#18), read as specification rather than
> as a description of the code. *(Re-pointed 2026-07-31: #18 built the collector and stopped
> there, because `RECLAIM` kills a window `attach` never saw and that is its own change. The
> specification below is now **#32**'s, and the blocker it named is gone.)*
>
> **Narrowed 2026-07-31 in the same PR, and one line of the specification below is wrong.** The
> tree evaluation this section prescribes for the claim is now done by the unwind itself, inside
> the transaction, where it needs neither a claim nor a collector: the lock is still held and the
> reservation is still live, so both exclusions a claim needs come free, and the search can be
> narrowed further to the container the attach built. What is wrong is "**once** at filing time".
> Measured through the built code (probe J6): a single evaluation at `awaitWindow`'s timeout
> closes the gap between the last poll and that read, and leaves the *wider* half — the gap
> between that read and the `split none` — through which a dock arrives, sway refuses the flatten
> and the leftover container stands. Two evaluations close both, and two suffice: the dock program
> maps one window, so it lands either before a pass's read (adopted), between that read and the
> command (whose refusal is what sends the next pass looking), or after a flatten that has already
> succeeded. Only the last is left, and it is a dock arriving as its own tab in a tab the unwind
> has already restored. **So the third outcome is real but smaller than this section says**: a
> dock that maps after the unwind's last look, not one that maps after `awaitWindow` gave up.

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

- **Nothing consumes a `window::new` for this purpose today, so `RECLAIM` does nothing at all.**
  *(Amended 2026-07-31: was "nothing consumes `changes` today". `changes` now has a collector —
  #18 — but it acts on `close` and on the session ending, and there is no claim in the code for it
  to read: `DockTable` has entries, reservations and focus rules only. The `new` branch is #32's
  to add along with the claim itself.)* See "The third outcome's owner, half built" below; this is
  the first cost, not the last.
- **It happens after `attach` returned.** It is not inside the transaction and cannot be — the
  transaction ended. "Left the tree as it found it" holds across the following window only if
  something outside the transaction finishes the job, and today nothing does — the collector
  exists (#18) and the claim it would read does not (#32).
- **It is event-driven, not scheduled.** A claim is consulted when a `window::new` event
  arrives on the `changes` stream — the same stream `reapOrphans` is driven off — and its
  deadline is evaluated at that moment, not by a timer. Nothing polls, loops, or acts on a
  schedule, per the working agreement. A claim that expires unfired simply loses to the next
  event that reads it. **The claim is also evaluated against the tree once at filing time**,
  which closes the millisecond-wide gap between `awaitWindow`'s timeout and the claim existing:
  `awaitWindow` polls the tree rather than the event stream, so a dock that maps inside that gap
  emits a `window::new` that no claim is there to read, and nothing re-reads it afterwards.
  *(Amended: the unwind now does this itself and needs no claim to do it, and "once" is measurably
  not enough — the gap has two halves and one read closes only the first. See the narrowing
  above.)*
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
  #32 files a claim for #18's collector to read — so `LEAVE` is both the conservative default and
  the one that "defaults are what you would have hard-coded" actually selects. Shipping a default
  whose behaviour cannot be exercised would be worse than either.

`RECLAIM` is the lever for whoever wants the stray panel gone, and it is the right default under
`PER_SURFACE_APP_ID`, where the claim is exact by construction.

**Open question, not settled here:** which of the two timeout causes dominates in practice. That
is the number that decides whether `RECLAIM` should be on by default, and measuring it needs a
real panel program on a real desktop — neither exists yet. This note picks the conservative
default rather than guessing the distribution, and says so rather than dressing the guess up as a
finding.

### The third outcome's owner, half built — #18

*(This section said "The third outcome has no owner" until 2026-07-31. The original diagnosis is
kept first, because it is what the rest of the note was written against, and the amendment follows
it.)*

The mechanism above was specified against a collector that **did not exist**. Verified on `main`
(`7a01fe0`) and across every open PR:

```
$ grep -rn reapOrphans --include='*.kt' .
wm/src/jvmTest/.../SwayBindingTest.kt:152:        wm.reapOrphans()
wm/src/jvmMain/.../SwayWindowManager.kt:267:    suspend fun reapOrphans() {
```

— the declaration and one test. `changes` was likewise declared and never collected outside tests,
and none of #11, #12 or #17 touched either. PR #12 says so about `reapOrphans` in its own body.

> **Amended 2026-07-31 by #18.** A collector exists now, and it is deliberately narrower than
> "the collector" this section asked for. `SwayWindowManager` starts one on its own scope as it is
> constructed — not offered as a `start()` for a caller to remember, because forgetting to wire it
> is precisely the defect being fixed — and it does exactly two things:
>
> - **a `close` event drives one `reapOrphans`**, under `wm.repair.sweep_on_close` (default
>   `true`; off is the previous behaviour, in which the sweep existed and nothing ran it). A sweep
>   that raises does not end the collection, under `wm.repair.sweep_failure` (default `CONTINUE`)
>   — the same argument that makes the sweep isolate docks from one another, one level up: a
>   wedged panel is permanent, so a collector that stopped on the first one would sweep once and
>   never again. `STOP` is the other half of that choice and lets the failure out of the collector.
> - **a `CompositorSessionEnded` discards the dock table**, which is Decision 1's rule finally
>   having a trigger.
>
> Both outcomes, and any sweep failure, are reported through `SwayWindowManager.repairs`, since
> nobody is watching a collector by construction. *(Second amendment, same day, after the review
> measured the hole in that sentence: everything **else** the collector could raise — a `connect()`
> that fails, a subscription sway refuses, an event that will not parse — escaped into the caller's
> scope and cancelled it, reporting nothing. A constructor started that job, so there was nowhere
> for a caller to put a `try`. It is contained and reported as `collectorFailure` now, under
> `wm.repair.collector_failure`; `PROPAGATE` is the loud half, and a caller choosing it owns giving
> this manager a scope that tolerates a failing child.)*
>
> **What it still does not do**, so that this section is not read as closed: it does not consult a
> claim on `window::new`, because no claim exists in the code to consult — `attach` files none,
> `DockTable` carries none, and `wm.dock.late_dock` was never introduced. Both halves are **#32**,
> filed rather than folded in, because `RECLAIM` kills a window `attach` never saw and that is a
> destructive change owed its own diff. It does not reconnect. And a `close` that the flow drops
> under back-pressure is a sweep that does not happen; nothing comes back for it, which is the
> same "one shot per event" property the sweep's own isolation exists to protect.
>
> **Why a collector is not the unattended autonomous action `docs/design.md` forbids.** That
> agreement — "Agents wait. They don't poll, don't loop, don't act on a schedule" — is about what
> an agent does between requests, and this does none of the three: no timer, no interval, no work
> at all until sway writes to a socket the coroutine is parked on. What it reacts to is a user
> closing a window, which is the user acting one layer below the hotkey, and the policy it applies
> is entirely in flags a caller set. This is the same argument the claim mechanism above was
> already settled on ("It is event-driven, not scheduled"), applied to its driver. A *periodic*
> sweep would be that rule broken, and is why one was not written.

The degradation list above names `wm.events.enabled = false` as the case where `RECLAIM` falls
back to `LEAVE`. "No claim to read" is today's *actual* case and has the same effect for a
different reason, which is why `LEAVE` being the default costs nothing at the moment.

### What `attach` owns

In order: the `split horizontal` container it created; the focus-suppression it applied; the
dock window it spawned; the mark; the geometry; the resting-focus disposition; and the
registry binding. On failure, each is compensated in reverse.

| step | compensation |
|---|---|
| registry `bind` | `unbind` — or, if `bind` is what failed, tear the dock down (below) |
| resting focus / geometry / mark | none needed; they die with the window |
| dock window | `kill`, then `awaitGone` — **including one the attach never identified**, found by reading the container back against the `app_id` the `exec` reserved; see the narrowing under "The late dock" |
| table entry | evict — *bookkeeping, always runs* |
| reservation | convert to a claim, then evict when the claim resolves or expires — *bookkeeping, always runs*. **As built: evicted outright, no claim** — see the amendment above |
| focus suppression | **`REFOCUS_AFTER_MAP`: nothing to undo. `NO_FOCUS_RULE`: not undoable.** |
| `split horizontal` | `split none` on the surviving child — the same normalisation `detach` already performs on the success path. **Up to twice on the unwind path**: sway refuses it on a container that has just acquired a second child, and that refusal is the news that the dock arrived after the read which checked |

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

  **Re-measured through the built code when #4 landed, and the number is much smaller: 1–2 ms.**
  Same shape — a `window` subscription on its own connection, three runs on sway 1.12 —
  but driven by `SwayWindowManager.attach` rather than by the probe's hand-written sequence:

  ```
    17ms new con=7 | 17ms focus con=7 | 18ms focus con=5   <- the correction
    21ms new con=7 | 21ms focus con=7 | 22ms focus con=5
    32ms new con=7 | 36ms focus con=7 | 38ms focus con=5
  ```

  The 73–78 ms above is a fact about the probe, not about awakener's client: `awaitWindow`
  polls `get_tree` in a tight loop on a connection nothing else is using, so it sees the node
  within a round trip of the map, and only three commands separate that from the correction.
  `new` and `focus` land in the same millisecond on two of three runs and 4 ms apart on the
  third, so the no-race finding holds unchanged. Focus rested on the app over a 1 s observation.
  Recorded because this note's own convention is that sway behaving a certain way and awakener's
  client behaving that way are separate facts — the flicker `REFOCUS_AFTER_MAP` costs is an
  order of magnitude smaller than the argument for it assumed, which strengthens the default
  rather than weakening it.

  > **Amended 2026-08-04 by #49.** "A tight loop" is no longer the whole of it: the loop spins
  > for `wm.wait.poll_spin_ms` (250 ms by default) and paces itself at `wm.wait.poll_interval_ms`
  > afterwards. Every latency figure above is inside that first 250 ms, so they stand as taken.
  > What changed is the cost of a wait that *expires*, which is where the reads were buying
  > nothing: 6,637–11,085 round trips per second and 59% of a compositor core, so ~33,000 round
  > trips and ~2.9 s of compositor CPU to establish that a dock never appeared. Spinning does
  > earn its ~10 ms — the hypothesis that it starved the compositor of the time to map the very
  > window being waited for was tested and refuted, 8 alternated trials, median 16.5 ms spinning
  > against 26.1 ms at a flat 25 ms poll — so the spin is kept where it pays and dropped where it
  > does not. Counted at the socket rather than estimated: a 5 s deadline that expires costs
  > **1,719** `get_tree` round trips at the stock defaults against **~27,500** spun.
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
- `wm.dock.late_dock` = `RECLAIM` | `LEAVE`, **default `LEAVE`.** *(Specification, not built —
  see the amendment on "The late dock". It is **#32**'s, and the outcome it covers is now the
  narrower one recorded there.)* See "The late dock". The
  description must state that `RECLAIM` kills a window `attach` never saw; that under `NEW_NODE`
  the residual predicate is the shared `app_id`, so it can reach a hand-launched window; and that
  it does nothing at all when `wm.events.enabled` is off **or while no claim exists for the
  collector to read, which is the case today (#32)**. *(Amended 2026-07-31: that last clause said
  "while nothing collects `changes`", which #18 fixed. The collector reads `close` and the session
  boundary; the `window::new` branch and the claim it consults arrive together, in #32.)*

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
  one; teardown stays on `DockHandle`. *(#18 adds no call either: the collector is private and
  starts itself, and what it reports — `SwayWindowManager.repairs` — is on the implementation
  beside `reapOrphans`, not on the interface. `DockRepairStatus` names no compositor concept, so
  nothing above `:wm` learns one by reading it.)*
- **Reads outside the lock.** Preserved. See Decision 1, Rejected.
- **Compositor-agnostic above `:wm`.** The table is keyed on `SurfaceId`, a `:wm` value class,
  and is never returned or consulted from above. Marks, criteria, `no_focus`, split containers
  and `con_id`s all stay below the line.
- **Flags first.** Six flags, each defaulting to the behaviour that would otherwise have been
  hard-coded: `wm.dock.recognition`, `wm.dock.reap_evidence`, `wm.dock.pending_suppression`,
  `wm.dock.focus_suppression`, `wm.dock.unwind_failed_attach`, `wm.dock.late_dock`. **None of
  them gates bookkeeping**, which is the property that matters and the one an earlier draft got
  wrong. *(As built, five of the six exist. `wm.dock.late_dock` does not: it gates a claim
  mechanism nothing builds and nothing reads — see the amendment on "The late dock".)*
  *(#18 adds three more, outside this note's six and in their own namespace:
  `wm.repair.sweep_on_close`, `wm.repair.sweep_failure` and `wm.repair.collector_failure`. None of
  them gates bookkeeping either, and the session-boundary discard is under no flag at all, since a
  table outliving its session is not a behaviour anyone would choose. `wm.repair.collector_failure`
  is the one that is not about repair policy but about blast radius: the collector is started by a
  constructor, so a failure that escaped would cancel the caller's whole scope, and the default
  contains it and reports it instead.)*

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

*(Amended 2026-07-31. #7 landed as PR #12, #9 as PR #23, and #6 + #4 as PR #27; #18 landed after
all of them rather than alongside #9 — without consequence, because the only thing #9 shipped that
needed it was the reservation's own eviction, which `attach` does in a `finally` of its own.
#18's collector does not make `late_dock` exercisable on its own: it drives the sweep and the
session boundary, and the claim `RECLAIM` acts on is unbuilt at both ends. So `late_dock` is
reconsidered once **#32** lands, not now.)*

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

   **What landed:** #9 (PR #23) filed and evicted; it did not convert, and no claim exists. #6
   added the tree compensations as assigned. The gap between them is the late dock. *(Re-pointed
   2026-07-31: that gap was #18's "along with the collector that would read a claim at all". #18
   built the collector and left the claim, so the gap is **#32** and the collector it needed is
   no longer in front of it.)*

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
  fixed, it is off. *(Prescriptive: that flag does not exist — see the amendment on "The late
  dock". The unwind's own adoption is exposed to the same coarseness and is bounded instead: it
  looks only inside the container this attach built, and only while the lock it took is still
  held.)* `PER_SURFACE_APP_ID` removes it by construction — the sharpest reason yet to
  revisit that default, below.
- ~~**The mark's durability has two known holes, both open: #14 and #15.**~~ *(Closed
  2026-07-31. Both were one defect — a mark naming the surface rather than the dock — and both
  are fixed by the amended predicate above: the mark carries the dock's own `con_id` and counts
  only on the node it names. What is left of #15 is a user mark that writes their own window's
  `con_id` into it; what is left of #14 is the policy question of whether a second attach on one
  surface should be *refused*, which is `attach`'s contract rather than the mark's, and is not
  taken.)* *(Amended 2026-07-31 by #35, which was that residual of #15 filed as its own issue and
  is closed: the mark carries a nonce field too, so the shape #35 names is not a dock mark at all.
  What is left after **that** is a mark whose nonce field is well formed — a deliberate copy rather
  than an accident, since no mark sway holds is beyond a hand's reach — and it is bounded by
  `wm.dock.reap_evidence=STOOD_UP` rather than by the mark. Stated with its measurements under
  "What #35 closed, and what it did not".)* This note is still the single place the *recognition*
  predicate is decided.
- **The claim is neither filed nor read.** *(Amended 2026-07-31: this said "nothing collects
  `changes` and nothing drives `reapOrphans` (#18)", and #18 has since fixed that half — a
  collector now drives the sweep off the `close` event and discards the table at the session
  boundary.)* What remains is the claim itself, and it is *both* halves of it: `attach` files
  none, `DockTable` holds none, `wm.dock.late_dock` does not exist, and the collector has no
  `window::new` branch to consult one from. So Decision 2's "something outside the transaction
  finishes the job" is still true of nothing. #18 removed the reason it could not be built — the
  missing collector — and deliberately did not build it, because a mechanism that kills a window
  `attach` never saw is its own change with its own review. Filed as **#32**. See "The third
  outcome's owner, half built".
- **A cancelled `registry.bind` can leave a durable binding with no panel.** The third instance of
  the late-recording shape above, and the one this layer cannot close. `bind` is deliberately
  outside the tree section because it can shell out to spanreed; if the attach is cancelled while
  that call is returning, the binding is written and its result never arrives, so `attach` unwinds
  the dock and leaves the row. The compensation would be an `unbind` — but `bind` with a null
  agent *resolves an existing Lifeless or mints one*, and which of the two it did is exactly the
  fact that was lost, so an unconditional `unbind` would throw away a binding the attach did not
  create. Closing it needs `:registry` to make the bind's outcome recoverable rather than only
  returned; it is not `:wm`'s to fix, and the window is one cancellation landing on one
  resumption.
- **`no_focus` remains unrevocable, and so does an `exec` in flight.** Both are declared
  exceptions to Decision 2 rather than solved problems. `no_focus` is made non-default and
  non-cumulative; the late dock is reclaimed after the fact rather than prevented. Neither is
  fixed — sway cannot fix either.
- **Reconnecting after a compositor restart** is not designed here, and is the last piece of the
  boundary — **filed as #33**, so that closing #18 does not leave it hanging off a closed issue.
  This note defines the session boundary and requires the table be
  discarded at it; it does not say how the manager acquires a successor connection, and today it
  does not try (`commands` is `by lazy { connect() }`, so a manager whose session ended stays
  broken rather than becoming quietly wrong). Of the three things that were owed here, two have
  landed: **making the boundary observable** (#20 — `changes` fails with `CompositorSessionEnded`
  where it used to go silent) and **discarding the table on it** (#18 — the repair collector's
  `catch`). What is left is acquiring the successor connection, and the parts of it this note can
  already name: awakener's `SWAYSOCK` is itself stale across the boundary, since the default
  socket path carries the compositor's pid; nothing restarts the repair collector, which returns
  when its session ends; and a reconnect that reuses a `SwayWindowManager` would have to answer
  what happens to the `DockHandle`s callers are still holding, every one of which names a
  `con_id` from the dead session. **Not half-built on purpose** — #18 deliberately stopped at the
  discard rather than guessing at any of that, and #33 carries that list forward. One thing #18
  added to it: a collector that ends on a failure other than the boundary (see
  `wm.repair.collector_failure`) leaves the manager not observing the boundary at all, so on that
  path the table is never discarded and the successor question arrives with a stale table already
  in hand.
- **Changing `wm.dock.mark_prefix` while docks are standing** orphans far less than this bullet
  used to claim, and hides more. *(Amended 2026-07-31, measured against #23's second head with the
  pre-adoption code as the control: fresh manager over intact marks, one `surfaces()`, then flip
  the prefix — the dock is still recognised, `[5]`, where the pre-adoption code hands it back as a
  bindable surface, `[5, 7]`.)* Because adoption records, everything already in the record
  survives the flip: the docks this process stood up, and every dock any read has recognised from
  its old-prefix mark. What is genuinely orphaned is narrower — a dock nothing has enumerated
  since this process started, which then becomes an ordinary bindable surface and stops being
  reapable. The mirror image is the sharper edge and was never stated: a genuine window hidden by
  a prefix-shaped *user* mark under the old value stays hidden under the new one, for the same
  reason and with the same recovery (`MARK_ONLY`, or a restart). Moving the prefix is still a
  restart with no docks standing rather than a flip. *(Amended 2026-07-31: `wm.dock.mark_scheme`
  is the same hazard in a second flag and takes the same advice, with one thing the prefix flag
  does not have — a dock stranded by either flip has its mark named in `unrecognisedDockMarks`,
  since a mark under the prefix that this build does not recognise is reported rather than passed
  over. That is a diagnosis, not a recovery.)*
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
  from `surfaces()` entirely, which is #15. *(Both remain true of sway. Neither is now reachable
  through awakener at stock defaults: the first fact is why the mark carries the dock's own
  `con_id`, and `awakener_dock_notes` has been reported rather than hidden since the predicate was
  pinned. The regression tests are in `SwayBindingTest`, and were run red against `d576d28`
  before the fix — including `awakener_dock_<live con_id>` on a real window, which that build's
  sweep destroyed when the named window closed.)*
- **J5 — the `REFOCUS_AFTER_MAP` transient, as built.** Added when #4 landed: the real `attach`
  under `wm.dock.focus_on_map=false`, watched on a `window` subscription over its own connection,
  three runs. 1–2 ms of steal, `new` and `focus` in the same millisecond on two runs of three,
  focus resting on the app after 1 s. See the amendment under the flag; the probe's 73–78 ms was
  a fact about the probe's own command sequence.
- **J6 — the map-deadline race, and how much of it one tree read closes.** Added when #6's fix
  landed. Found in review of that fix by sweeping the dock command's `sleep` across the 5 s
  deadline: 1 of 4 and 1 of 8 genuine timeouts left a `splith` container standing over the
  surface and an unmarked dock beside it, because the unwind ran with no `con_id` for a window
  that had just mapped. Reproducing it that way costs several runs an observation, so it was then
  **pinned rather than swept**, by holding one IPC request on its way to sway until the dock has
  mapped — `SwayValve` in the test source set, which both regression tests drive. Two placements,
  each of which the valve makes a single run rather than a sweep:

  1. the dock maps while the map wait is still outstanding — held request is `awaitWindow`'s
     first poll, released 5.2 s later, so the deadline has certainly expired;
  2. the dock maps between the unwind's tree read and the `split none` it sends next — held
     request is that command.

  Both are red against the unfixed transaction (3 runs, 3 × 2 failures, each
  `expected:<[5, 6]> but was:<[7, 6]>` — node 7 being the leftover container in place of the
  surface). **A single re-read at the timeout fixes the first and not the second**: measured by
  building exactly that (`FLATTEN_PASSES = 1`) and running both, 2 runs, placement 1 green and
  placement 2 red on each. Two check-and-flatten passes fix both, 2 runs green, and the whole
  `SwayBindingTest` suite green with them.
- **J7 — what the `_for_` mark leaves behind, and what an upgrade over standing docks costs.**
  Added when #14/#15's fix landed, both run against that fix rather than against `main`, and both
  kept as tests whose KDoc says they record current behaviour rather than prove one.

  1. **The residual is destructive, not hiding.** A second application window marked
     `<prefix><its own con_id>_for_<the first window's con_id>`, the first window killed, one
     sweep: the marked window is **gone from the tree**. The self-check passes, so the mark is
     current evidence and `reap_evidence=CURRENT` is no defence. Was `SwayBindingTest.the residual
     the self-check leaves is a destroyed window, not a hidden one`. *(Amended 2026-07-31 by #35:
     this measurement is now a fact about `wm.dock.mark_scheme=DOCK_AND_SURFACE` rather than about
     the default, and the test that pinned it went red when the fix landed. Re-run against
     `0e2446b7` in the shape the issue states, both halves: the enumeration one fails
     `expected:<[5, 6]> but was:<[5]>` and the destructive one leaves the marked window gone. Now
     `SwayBindingTest.a user mark naming its own window and a dead con_id no longer costs that
     window`, asserting the opposite.)*
  2. **A stranded dock leaks and is never killed.** A dock attached under one scheme, then a fresh
     `SwayWindowManager` reading another against the same sway session — which is an upgrade over a
     standing dock, since the scheme decides reading and writing together. The dock comes back as a
     **bindable surface**, its mark is **named in `unrecognisedDockMarks`**, and killing its
     surface and sweeping **leaves it standing**. `SwayBindingTest.a dock marked under the other
     scheme is reported and left standing`, which drives `DOCK_AND_SURFACE` → the nonce default
     since that is the upgrade #35 causes; the parse in every direction is `DockTableTest.no scheme
     reads another scheme's mark as a dock mark`. *(Amended 2026-07-31 by #35: the live half ran
     `SURFACE` → `DOCK_AND_SURFACE` when it was taken, and the property is the same one.)*
- **J8 — what sway will round-trip in a mark, and who can write one.** Added by #35, and the
  reason its design is what it is. Raw `swaymsg` against a headless 1.12, because the question is
  about the compositor and not about awakener's client.

  1. **Length is not a limit.** Marks written and read back byte-identical at 8, 16, 32, 42, 64,
     128, 256, 512, 1024, 4096 and 16384 characters; every write `success:true`, every read equal.
  2. **Nor is the character set.** ``- . : / @ # % [ ] { } + = ~ ^ | \ $ ` * ?``, a single quote, a
     quoted space, a literal `\x01` and multi-byte UTF-8 (`✦`, `ÿ`) all round-trip verbatim. What
     does not survive belongs to the *command* parser rather than to marks: `;`, `,` and a newline
     terminate the command (the mark truncates at them), and a tab collapses to a space. Quoting
     recovers `;` and `,` — `mark --add "aw;dock"` stores `aw;dock`.
  3. **There is no shape awakener can write that a hand cannot.** sway sets a mark through
     `RUN_COMMAND` and nothing else, on the socket `swaymsg` speaks. A hand-run
     `swaymsg '[con_id=5] mark --add awakener_dock_5_for_5_9f3a1c7e0b2d8465'` returns
     `success:true` and reads back verbatim. **So "a shape sway will not round-trip" is not
     available, and unforgeability is not reachable through the mark at all** — which is why #35
     ships an accident barrier *and* a `reap_evidence` value that asks the tree nothing.
  4. **Marks survive everything awakener does to a dock.** `split horizontal`, `resize set width
     30 ppt`, `move left`, `floating enable`/`disable`, `layout tabbed`, and moving the container
     to another workspace and back: the mark is unchanged after each.
  5. **Nor is the structure any different.** The alternative to a mark check was a structural one,
     and it is not evidence either: when a surface closes, sway leaves its dock as the **sole child
     of the `splith` container** `attach` built — measured, `kids` goes 2 → 1 with the container
     still there — which is a shape `swaymsg` produces as readily, and which a window opened into
     that container undoes. The tree is the user's; nothing in it is proof.
