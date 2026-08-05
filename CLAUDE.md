# Working in this repo

## What awakener is

Persistent, per-surface agents bound to the windows on a Linux desktop. A hotkey brings up
an agent docked to the window it belongs to; agents coordinate over spanreed. What the agent
holds is not the task but the accumulated model of the user on that surface.

**Read `docs/design.md` first.** It is the settled design brief — layers, substrate
decisions, memory model, what is explicitly out of v1, what is still open, and the working
agreements. It supersedes any inference you would otherwise make from the code layout.

Vocabulary is from *Warbreaker*: an **Awakener** binds agents to surfaces, a **Lifeless** is
one agent bound to one surface, **Breath** is the resource spent to animate one, a
**Command** is its standing instruction, a **Drab** is an unbound window, and a **Returned**
is the ephemeral per-task coordinator.

## Verification

**The system JDK on kaladin is 8. Every Gradle command must pin 21:**

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build
```

That is the full autonomous check — it compiles every module and runs all tests, including
the `:wm` integration suite against a real headless sway.

- **The REQUIRE flags are the protection.** `AWAKENER_REQUIRE_SWAY=1` — and
  `AWAKENER_REQUIRE_SPANREED=1` for `:registry` — are mandatory for any run whose result you
  intend to report. Without them a machine lacking `sway`/`foot`/`spanreed` skips those tests
  and the build still succeeds; with them a missing tool fails the build. CI sets them.
  Nothing else *prevents* a green run that verified nothing.
- **The skipped count corroborates; it does not protect.** Read
  `*/build/test-results/jvmTest/*.xml` and check `tests=` and `skipped=`. A tool-gated test
  now reports as genuinely skipped — a real `<skipped/>` element, via
  `SwayHarness.assumeAvailable()` and `SpanreedCliTest.spanreedOrSkip()` — so `skipped="0"`
  does mean those tests executed. That was **false before #26**: the gate was an early
  `return`, which JUnit records as PASSED, so a host with no compositor reported `skipped="0"`
  and a full count of passes, indistinguishable from a run against a live sway. Read the count
  *and* set the flags: the count catches the mistake afterwards, the flags stop it happening.
- "BUILD SUCCESSFUL" alone still distinguishes nothing. If you add a tool-gated test, gate it
  with an assumption, never with a bare `return` — and if you add a new gate, declare what it
  reads as a test input. `org.gradle.caching=true`, and Gradle treats neither `PATH` nor the
  environment as an input, so every build script whose tests can be tool-gated names the tool
  presence and the REQUIRE flags explicitly as task inputs. Without that the build cache will
  replay a run that skipped everything into a `clean build` that demanded the tools. That is a
  property each such build script has to hold for itself; there is no list of them here,
  because a list is one more thing to forget to update when a module gains its first gated
  test.
- **A run measures one tree, and `main` moves under you.** Every count above describes the
  tree the run ran against. Counts from a branch that `main` has since left describe a tree
  that will not exist after the merge, and nothing in the output says so. A PR's numbers are
  only meaningful if `origin/main`'s tip is an ancestor of its head — one line, run by the
  implementer before opening and by the reviewer before believing any number in the body:

  ```sh
  git fetch origin && git merge-base --is-ancestor origin/main HEAD && echo current || echo STALE
  ```

  On `STALE`, rebase or merge and **re-run**, then report the new counts. `mergeStateStatus:
  CLEAN` does **not** imply this — that field answers whether the merge would conflict, which
  is a different question. #59 merged `CLEAN` reporting an honest, XML-read 176 tests /
  skipped=0 / failures=0 against a tree `main` had already advanced past to 201. A conflict
  announces itself; staleness does not.

  **The check is ancestry, but the question is reach.** An intervening merge that cannot reach
  what the numbers measure does not invalidate them, and re-running against it spends a full
  build to reproduce the same figure. The exemption is the *argument*, though, and the argument
  has to be **stated**: *"`44c03f3` is `CLAUDE.md` only, so #61's 205 tests / skipped=0 still
  describe the merged tree."* Unstated it is indistinguishable from never having checked —
  nothing on the page separates a considered "this merge cannot reach the suite" from a
  forgotten `git fetch`, and the reviewer has to treat the two the same way. Judge reach by
  what the merge touches, not by how small it looks: a shared build script, a test harness, or
  `:config` reaches everything and never qualifies, however few lines it is. Positively, what
  *does* qualify is a merge whose every changed path is one nothing under test reads — docs and
  findings notes, or a module neither the suite nor anything it depends on compiles against.
  `44c03f3` qualifies because `CLAUDE.md` sits on no compile or runtime classpath and no test
  opens it. If you cannot name that property for every path in the merge, it does not qualify.

  **Same shape as the known-flake exemption** (#56): both claim a diff *cannot reach* a result,
  both are argued per case from what the diff actually touches, and neither is ever discharged
  by looking the case up on a list of things previously agreed to be harmless. An agent who
  understands one should recognise the other on sight.
- `:wm` needs a live compositor, so "no automated test for this window behaviour" is not the
  defect it would be elsewhere — but a PR must say what it exercised and against which sway
  version.

## Stack (decided 2026-07-30 by Jason)

**Kotlin**, KMP with the JVM target first (JDK 21 is present on both hosts). Keep
`commonMain` free of JVM-only APIs so a Native target stays open if per-hotkey startup
latency ever matters.

Planned module boundaries, from `docs/design.md`:

- `:wm` — the three-call compositor interface (`resolve`, `attach`, change notification) and
  its sway IPC implementation. **Nothing above this module may learn which compositor it is
  talking to.** The i3-ipc wire format is a 14-byte little-endian header plus a JSON payload;
  hand-roll it rather than taking a dependency.
- `:registry` — surface→agent binding, persisted.
- `:bus` — spanreed adapter.
- `:pairing-mcp` — read-on-demand surface tools, via the official Kotlin MCP SDK.
- `:chrome` — CDP client; needs a dedicated Chrome profile, because anything that can reach
  the debugging port can drive a logged-in browser.
- `:cli` — entry points that need the whole flag set (`awakener-config`), and **the only
  module allowed to depend on every other one**. That dependency is what puts every
  flag-declaring class on one classpath; a `main` in a module that cannot see them enumerates
  an empty registry and reports that those flags do not exist.

**Talk to spanreed through its CLI, never through its files.** `spanreed` exposes
`register` / `send` / `recv` / `list` / `name` / `focus` / `status` / `conjoin` as a
versioned public contract. `~/.claude/spanreed/registry.json` and its lockfile are internal;
reimplementing that locking discipline in a second language would duplicate invariants that
are not ours to hold. Known gap: registry entries are keyed on `pid` + `pid_start` for
liveness, so the manager-mirrors-managed-agents pattern (Chrome origins registering under
the manager's PID) may need a CLI path that does not exist yet — that is a spanreed
conversation, not a local workaround.

## Environment (verified 2026-07-30 — re-check before relying on it)

- **kaladin** — this repo's host. Headless: no `/dev/dri`, no seat, no compositor. Cannot run
  anything needing a display. **`sudo` here is passwordless**, so installing a tool you need
  is your call to make, not something to ask Jason for. `sway`, `foot`, `chromium`, `jq`,
  `qemu` and `waydroid` are already present. **KVM works** (Jason enabled SVM in firmware on
  2026-07-31): `kvm_amd` loads at boot, `/dev/kvm` is mode 0666, nested virtualisation is on.
  Binder needs nothing — the LTS kernel ships `CONFIG_ANDROID_BINDER_IPC_RUST=y`, so Waydroid
  runs natively with no DKMS module. `kernel.dmesg_restrict=1`, so read kernel messages with
  `journalctl -k`, not `dmesg`.
- **kaladin has hung twice at kernel level** (2026-07-30 15:09 and 07-31 00:07): powered but
  unresponsive, no journal, staying dark until power-cycled. It is now instrumented —
  `kernel.panic=30` and `panic_on_oops=1` so a panic reboots instead of hanging forever, a 60s
  systemd hardware watchdog, netconsole streaming kernel output to adolin
  (`~/kaladin-netconsole/kernel.log`), and a 15s load/thermal sampler at
  `/var/log/kaladin-load.log`. Cause unproven. Keep concurrent heavy work modest and prefer
  targeted module tests over repeated full builds.
- **adolin** — the desktop, and awakener's actual target. GPU, active seat0 on tty2, running
  **GNOME Shell**. Installed: `google-chrome-stable`, `xorg-xwayland`, `sway 1.12`,
  `foot 1.27.0`, `waydroid 1.6.3`, `jq`, `qemu`. Absent: `chromium`, `spanreed`. Reachable by
  passwordless ssh from kaladin, but **sudo there requires a password** — Jason runs any
  *further* installs himself. **Check before asking**: most of what the open work needs is
  already on the box, and this list has twice claimed otherwise.
- **Neither host runs a tabbed WM as its session** — adolin's is GNOME Shell on seat0,
  kaladin has no seat at all. The dock design depends on i3/sway tree semantics, so it has
  nowhere to run *for real* yet. Installed is a different question, and the answer is **both
  hosts, not one**: `sway 1.12` and `foot 1.27.0` are on kaladin *and* on adolin, explicitly
  installed there on 2026-07-30 — the same day this section was first written and marked
  verified. CI installs them too, and the `:wm` and `:cli` suites drive sway on every build.
  `WLR_BACKENDS=headless sway` gives a genuine sway tree drivable entirely over ssh via
  `swaymsg` — that is how structural probes should run, and it needs no display and no change
  to Jason's GNOME session.
- **Waydroid runs on kaladin, and Test 1 (occlusion lifecycle) is answered** — under qemu on
  2026-07-30 and natively on 07-31; see `docs/findings/`. **Nothing was gated on a DKMS
  module**: the LTS kernel's `CONFIG_ANDROID_BINDER_IPC_RUST=y` driver serves Waydroid
  unmodified, `/dev/binderfs` is mounted, and `/usr/bin/waydroid` is installed. What remains
  open is narrower — both runs were software-rendered, so buffer back-pressure from a
  gbm/DRM-backed Waydroid is untested and needs adolin's real GPU. **That is no longer an
  install.** Waydroid 1.6.3 has been on adolin since 2026-07-31, `/var/lib/waydroid` is
  initialised, and `waydroid-container` is active with the session stopped. What is left is a
  session started against Jason's live seat — his call to make, not a package for him to
  fetch.

## Flags first (owner directive, 2026-07-30)

**Behaviour goes behind a runtime flag, not a constant.** Jason's time is the scarce resource,
not tokens: he wants to try alternatives against a running system without a rebuild and
without a round-trip through you. So when you find yourself choosing between two plausible
behaviours, *build both and add the switch* rather than asking which he wants.

- Declare flags in a `*Flags` object via `com.monkopedia.awakener.config.Flags`. Each carries
  its own default and a description, which is what makes `awakener-config list`
  self-documenting instead of a hand-maintained list that drifts.
- **The name and the package are load-bearing**: `FlagDiscovery` finds declaring classes by
  scanning the classpath for `com.monkopedia.awakener.**` classes whose name ends in `Flags`,
  and `:cli` depends on every module in the build. Exactly one class is excluded, and it is
  excluded by its **whole path** — `com.monkopedia.awakener.config.Flags`, the registry, which
  registers nothing. A prefix is conventional, not required: `Flags` is a simple name any
  package may mint, so `com.monkopedia.awakener.chrome.Flags` is a declarer and *is*
  discovered. Excluding by *simple* name was a bug — it dropped exactly that class with
  nothing in `FlagDiscovery.Report.problems` to say so — and a fixture wired to nothing
  (`cli/src/jvmTest/kotlin/com/monkopedia/awakener/futuremodule/Flags.kt`) plus its test hold
  the fix. Follow the convention and a new module's flags show up in `list` with no
  registration step anywhere; deviate — a package outside `com.monkopedia.awakener`, or a name
  that does not end in `Flags` — and they are invisible until someone names the class in
  `config.flags.declarations`.
- Defaults must be the behaviour you would have hard-coded, so an unconfigured system is
  correct.
- `:config` reloads on file change, so a flag flip applies to a live daemon. Never cache a
  flag value across an operation that could span a reload — read it from the snapshot.
- A snapshot is total: a bad value degrades to that flag's default and is reported through
  `Config.problems`. The config file gets hand-edited against a running desktop, so a typo
  must not take the process down.

## Working agreements

From `docs/design.md`; these bind agent work here:

- Prove the binding model over sway IPC before writing any compositor code. **No compositor
  fork.**
- Keep the WM interface at three calls.
- No new spanreed primitives without exhausting the existing ones.
- **No unattended autonomous action** in the product itself: agents wait for the user or the
  bus. They do not poll, loop, or act on a schedule.
- The design brief has a "Settled — don't relitigate" section. Respect it. Bring new evidence
  if you disagree; do not re-argue from first principles.

## Conventions that already apply

- **PUBLIC repo.** Auto-merges are visible to the world, so lean conservative: when in doubt
  escalate rather than auto-merge. Being public is an argument for *review quality*, not for
  routing every change to Jason.
- **⚠️ OWNER DIRECTIVE (2026-08-02): NOTHING IS TIER 3 during standup.** Jason capped every
  category at tier 2 until the project is stood up, so a clean review auto-merges and nothing
  waits on him. The `security` → tier 3 rule below is **suspended**, not deleted. This is a
  direct instruction and it is the current default; the reviewer still `request_changes` on
  anything not clean, so the bar is unchanged — only the gate moved.

  **Revisit before any of these lands, not on a date:** `:chrome` gaining the ability to
  attach to a real browser profile, `:pairing-mcp` gaining a way for one agent to address
  another's surface, `:bus` gaining an inbound path a local process could inject on, or
  anything running as a daemon against Jason's live desktop. Those are the reaches the tier-3
  rule was written for, and none of them exists yet — which is exactly why suspending it now
  costs nothing and why it stops being free the moment one arrives. Say so and re-tier rather
  than letting the suspension become the permanent state by inertia.
- **Review tiers when the suspension lifts** (re-tiered 2026-07-30 against the real stack; see
  `review-policy.md` in the urithiru fleet config, which is authoritative). `public_api` and
  `breaking_change` are **tier 2** — this is an application, not a published library, so module
  boundaries are internal contracts: surface them in the verdict, don't owner-gate them.
  Scaffolding is **tier 1** — the Gradle skeleton, CI workflow, license, gitignore, module
  stubs, and docs are not gated. `security` is **tier 3**.
- **What counts as `security` here is wider than it looks**, and these will arrive framed as
  features. Classify all of the following as `security`, not `feature_internal`:
  - `:pairing-mcp` — how a surface proves it is the surface it claims to be; anything where
    one agent could address or impersonate another's surface.
  - `:chrome` — attaching to a browser is reach over live sessions, cookies, and page
    content. *Widening* what may be attached to, read, or executed is security-relevant.
  - `:bus` / IPC trust boundaries — anything letting an unintended local process inject
    messages or drive `:wm`.

  Hotkey/window binding that can raise or move arbitrary windows is worth flagging in the
  verdict too.
- **`:wm` needs a live compositor**, so "no automated test for this window-management
  behavior" is *not* the defect it would be in a pure-JVM module. But a PR touching it must
  state what was exercised against a live compositor, and at which version.
- **Every bot write goes through a wrapper by its FULL path**, never bare and never plain
  `gh` — plain `gh` runs as `Monkopedia` and silently misattributes bot actions to the owner.
  **Which wrapper depends on whether you are authoring or reviewing, and they are not
  interchangeable:**
  - **Authoring** — commits, pushes, `pr create`, issue writes:
    `/home/jmonk/git/urithiru/coder-bot/coderbot`.
  - **Reviewing** — `pr review`, review comments, and the merge:
    `/home/jmonk/git/urithiru/reviewer-bot/reviewerbot`.

  A reviewer that reaches for `coderbot` **cannot review a PR the coder bot authored**: GitHub
  rejects it with *"Can not request changes on your own pull request."* That failure lands at
  the moment the verdict is posted — after the entire review is done — so it reads as a broken
  tool rather than the wrong identity. Two identities is what makes the trail a real second
  pair of eyes instead of the author signing off their own work.
- **Commit style**: subject ≤ 70 chars, present tense; body explains *why*. Co-author
  trailer on agent commits.
- **A reviewer must be explicitly spawned.** Opening a PR with `--reviewer` sets an inert
  marker; nothing reviews a PR just because it exists. If a PR is sitting without a review,
  ask "was a reviewer ever spawned?" before "did the reviewer die?" — the two are
  indistinguishable from the outside and have opposite fixes.
- Fleet-wide workflow rules (review tiers, triage classification, the code-health pass)
  live in `~/git/urithiru/workflows/` and apply here unless this file overrides them.
