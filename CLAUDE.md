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

**This fence is the whole command, not the JDK-pinning half of it.** Copy it as it stands:

```sh
AWAKENER_REQUIRE_SWAY=1 AWAKENER_REQUIRE_SPANREED=1 \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew clean build --no-build-cache
```

*That* is the full autonomous check — it compiles every module and runs all tests, including
the `:wm` integration suite against a real headless sway. Every piece of it is load-bearing,
and dropping any piece still prints `BUILD SUCCESSFUL`:

- **`JAVA_HOME`** pins 21, because the system JDK on kaladin is 8.
- **The two REQUIRE flags** turn a missing `sway`/`foot`/`spanreed` into a failure instead of
  a skip. The bullet below is the long version, and it calls them mandatory; a fence that
  omitted them was a way to disobey the rule by following the documentation (#91).
- **`clean` and `--no-build-cache`** make it a run rather than a replay. Without them
  `jvmTest` can resolve UP-TO-DATE, or come out of the build cache, and execute nothing.
  **`--no-daemon` is not a substitute** and buys nothing here: it governs whether a daemon
  persists between invocations, and has nothing to do with the up-to-date check, which reads
  task inputs and outputs on disk.

**Then read the executed count, and look at the clock.** `BUILD SUCCESSFUL` is printed
identically by a run of the whole suite and a run of zero, so the number comes from
`*/build/test-results/jvmTest/*.xml` and never from the console. Delete the results
directories first, so that *absent* and *stale* cannot be read as each other:

```sh
find . -type d -path '*/build/test-results' -prune -exec rm -rf {} +
```

Not `rm -rf */build/test-results`: in `zsh` an unmatched glob is fatal, so on a tree that has
never built it kills the chain before Gradle starts — and `2>/dev/null` does not hide it,
because the shell is refusing to run the command rather than the command writing to stderr.

**Wall-clock is the one tell that needs no flag and no file.** Measured, not guessed: a
`clean build --no-build-cache` of this repo takes **38–55s on kaladin** (two runs over a
331-test tree on 2026-08-07, cold and warm daemon) and **1m28s–3m51s on CI** across the eight
most recent `main` runs up to `3d16851`. Tens of seconds is normal here and eight seconds is
not — a build an order of magnitude below what the work implies has measured nothing, and that
is a finding to report rather than good news. Another repo in this fleet printed
`BUILD SUCCESSFUL in 8s` across five modules with zero tests executed, and what caught it was
someone noticing the clock.

**Every bullet below is one failure wearing a different coat.** None of these instruments was
broken; each answered its own question correctly and was read as answering a different one.
`BUILD SUCCESSFUL` was reached by a run that skipped everything. `skipped="0"` was written by a
gate that returned early. `git status` answered about a tree six commits old. A signal is
evidence about the thing you care about only if something on the path between them confronted
it with reality — so when you cite one, say which question it actually answers, and prefer the
check that reads back the artefact you are making a claim about.

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
- **Both of those answer questions about the tests that ran. Neither one can see a module that
  ran nothing.** `AWAKENER_REQUIRE_SWAY=1` turns *a test that ran and found no sway* into a
  failure and says nothing about a test that never ran; `skipped="0"` is true and empty when
  there were no tests to skip. Four mechanisms produce that — a disabled test task, a module
  never wired to `check`, a cached task replayed as up-to-date, a run filtered to an empty set —
  and all four render as an honest, smaller table. Measured on `00c6d9e` (#97): one line in
  `wm/build.gradle.kts` took the total from 279 to 178 with `BUILD SUCCESSFUL`, `skipped="0"`,
  and the reassurance printed in full over a run in which the entire compositor suite never
  executed. **`.github/scripts/test-floors` is the other half of the comparison** — a committed
  per-module floor that `test-summary.sh` reads and *gates* on, and that `./gradlew build` checks
  through `:root:verifyTestFloors`. Two things follow for you. First, the floor file has to name
  every module in the build, in both directions, so **a new module cannot land without a line
  saying what it should run** — `0` is a legitimate answer and a stated one. Second, when a
  change legitimately removes tests, **lower the floor in the same commit**: the number is
  committed precisely so a drop in coverage appears in a diff next to its cause. Adding tests
  never needs an edit. Enumerating the four routes was the losing move, and the invariant is
  worth carrying to any other instrument you build here: **an aggregate reports on what it ran,
  never on what it should have run**, and an expectation derived from the same run shares the
  run's blind spot — a module that produced nothing contributes zero to the counts *and* zero to
  any expectation drawn from them, and `0` is not below `0`.
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
  .github/scripts/check-staleness.sh
  ```

  Three outcomes, each with its own word and its own exit status: `current` / 0, `STALE` / 1, and
  **`UNKNOWN: <reason>` / 2**. The third is why this is a script and not the one-liner it replaces
  (#123):

  ```sh
  git fetch origin && git merge-base --is-ancestor origin/main HEAD && echo current || echo STALE
  ```

  `||` binds to the whole `&&` list, so *every* way of failing to reach an answer printed `STALE` —
  a failed fetch, no such remote, running outside a repository, and (twice observed, once on #122)
  a `cd` in front of it that failed, with ancestry never evaluated. Measured on 2026-08-08 against
  four forced states, the old idiom printed `STALE` **and exited 0** for all three non-answers, so
  the word on stdout was its only signal and that word was wrong. It errs safe — it cannot print a
  false `current` — but "I checked, you are behind" says rebase while "I could not check" says fix
  the environment, and an agent that rebases on a false STALE spends a build *and* comes away
  believing it verified currency. Pass `-C <dir>` rather than chaining behind a `cd`; a directory
  that cannot be entered is `UNKNOWN`, not `STALE`. A detached HEAD is answered, not refused — it
  resolves to a commit, so the question has an answer. `AWAKENER_STALENESS_UNKNOWN=warn` downgrades
  `UNKNOWN` from a gate to a report (exit 0); it still never prints `current`.

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
  *does* qualify is a merge whose every changed path is one **neither the build nor the tests
  read** — docs and findings notes, or a module neither the suite nor anything it depends on
  compiles against. `44c03f3` qualifies because `CLAUDE.md` sits on no compile or runtime
  classpath, is read by no build script, and is opened by no test. If you cannot name that
  property for every path in the merge, it does not qualify.

  **Same shape as the known-flake exemption** (#56): both claim a diff *cannot reach* a result,
  both are argued per case from what the diff actually touches, and neither is ever discharged
  by looking the case up on a list of things previously agreed to be harmless. An agent who
  understands one should recognise the other on sight.
- **Do not read the shared checkout for anything load-bearing.** `/home/jmonk/git/awakener` is
  the tree the `agent-*` worktrees hang off, and **nothing pulls it on a schedule** — checked
  2026-08-05: kaladin has no cron at all (no `crontab` binary, no `/etc/cron.*`), and no unit
  reachable from `systemctl list-timers`, system or user, runs `git fetch` or `git pull`
  against this checkout. #71 is the corroboration, since a scheduled pull would have prevented
  what it describes. **Two scripts within a timer's blast radius do *name* the repo** —
  `~/git/urithiru/workflows/triage-fanout.sh:70` and `health-rotation.sh:58`, both carrying
  `Monkopedia/awakener` in a `ROSTER` array — but only one of them is timer-*activated*:
  `code-health.timer` → `code-health.service` → `health-fanout.sh:24` execs
  `health-rotation.sh`, whereas `nightly-triage.timer` → `nightly-triage.service` →
  `nightly-triage.sh` only `spanreed send`s a `nightly_triage` message to main-coordinator,
  which runs `triage-fanout.sh` as an agent. And the units hold no `awakener` at all
  (`/usr/bin/grep -ra awakener ~/.config/systemd/user/` exits 1, with `triage` as the positive
  control that the files were read), so the hits are in the scripts, not in anything
  `systemctl` shows you. Those are GitHub slugs for `gh api` and agent dispatch: grepping both
  scripts for `git fetch`, `git pull` and a `cd` into the checkout matched nothing (2026-08-07,
  with `/usr/bin/grep -rac awakener` on those same two scripts returning 2 and 1 as the
  positive control that they were being read), so neither script enters the checkout and the
  hits do not falsify this bullet. The checkout therefore sits on whatever commit it was last
  left on, and `git status` reports **clean** the entire time, because it *is* clean —
  relative to its own stale `HEAD`. That
  command answers "are there uncommitted changes"; it never answers "is this current", and the
  second is what it keeps getting read as. #71: the checkout sat six commits back overnight
  with `cli/build.gradle.kts` at 34 lines against 244 on `origin/main`, and an agent checking a
  claim from #66 read it and nearly filed a **correct** report as wrong — the inversion is what
  makes this one dangerous, since a false report is argued down rather than shipped. Work in
  your own worktree. When a claim turns on file contents, `git fetch` and read them at a named
  ref — `git show origin/main:<path>` — and **quote the SHA in the finding**, which is the part
  that does not depend on the next reader remembering the rule: with provenance in the output,
  a stale read is a diff of two SHAs instead of a judgement call about whether a file looks the
  way the repository says it should.
- **A local write that succeeded tells you nothing about what GitHub now holds.** Worktrees
  isolate source trees and nothing else. The scratchpad path is keyed on the project and
  session — `/tmp/claude-1000/-home-jmonk-git-awakener/<session-id>/scratchpad` — so every
  agent in a session shares one directory (on 2026-08-05, twenty `agent-*` worktrees to a
  single scratchpad holding `pr.md`, `body.md`, `review.md`, `squash.md`, and a symlink into
  another agent's build output). PR bodies, review bodies and issue text are staged there,
  which is exactly the material that must not cross. #61 wrote its description to `pr.md`; so
  did the agent on #70. #61 published **#70's body verbatim**, closing keyword and all, opening
  `Fixes #66` — it would have merged closing the wrong issue and leaving #52 and #49 open. The
  edit that overwrote it reported success: `s.replace('## Scope', new + '## Scope', 1)`, and
  Python's `str.replace` returns the string **unchanged** when the anchor is absent, so "already
  applied" and "wrong file entirely" produce the same output and the same `ok`. Two rules, the
  second of which holds whatever the cause:
  - **Name scratch files for the artefact and the agent** — PR number plus worktree id, as in
    `pr61-body-agent-a8a1d92943819cc6d.md`. Never `pr.md`, `body.md`, `review.md`: those are
    the names everyone independently picks, which is what makes them collide.
  - **Read the result back from the platform and assert on it.** After any body upload,
    `gh pr view <n> --json closingIssuesReferences` (a read, so no wrapper needed) must return
    exactly the issues you meant to close. It has to be the **description** you check, because
    that is what the merge acts on and what `closingIssuesReferences` is derived from; a
    curated squash body can *add* a closure but cannot retract one the description already
    promised. This is the check that catches a wrong body regardless of how it got there.
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

**Correcting a claim in this section: the replacement must be at least as precise as the
evidence that falsified it.** Five entries here have been wrong and three of those were
introduced *while correcting an earlier one* — each time by measuring something sharp and
writing it down blunt. `ls /dev/dri` failing while `seat-status` showed `fb0` distinguishes a
DRM node from a graphics device; the sentence that replaced "no seat at all" said "no graphics
device" and lost the distinction the measurement had just drawn. Quote the command, and let
the clause claim no more than its output does. The cost of a blunt replacement is not that
someone believes it — it is that an agent who measures, catches the loose clause, and
**discards the whole bullet**, operative claims included.

- **kaladin** — this repo's host. Headless: no `/dev/dri`, no seated session, no compositor.
  Cannot run anything needing a display. **It does have a `seat0`, and that does not
  contradict the sentence before it** — this said "no seat at all" until 2026-08-07 and the
  correction is the only reason the bullet still reads as trustworthy. Re-measured that day,
  every command below run on kaladin: `loginctl list-seats` → `seat0`, `1 seats listed`;
  `loginctl seat-status seat0` lists input devices, USB hubs, a sound card, NIC LEDs and one
  graphics device (`graphics:fb0 "EFI VGA"`), but **no DRM node**; `ls -la /dev/dri` → `No
  such file or directory`, with `ls -la /dev/kvm` in
  the same invocation succeeding, so the probe was working and the absence is real;
  `loginctl list-sessions` shows both sessions with `SEAT` empty; `pgrep -a
  'sway|gnome-shell|weston|Xorg|kwin'` exits 1. A seat is a udev grouping of input devices and
  exists on any machine with a keyboard port; that `fb0` is real too — `ls -la /dev/fb*` →
  `crw-rw---- 1 root video 29, 0 /dev/fb0` and `cat /proc/fb` → `0 EFI VGA` (2026-08-07). So
  kaladin **has** a graphics device. What it lacks is the **DRM node**, and anything logged in
  on that seat — which does not stop a compositor starting: `WLR_BACKENDS=headless
  WLR_LIBINPUT_NO_DEVICES=1 sway` logs `drmGetDevices2 failed: No such file or directory` and
  comes up anyway, with `swaymsg -t get_version` → `sway 1.12` (2026-08-07, on kaladin; that
  is the probe described below). **`sudo` here is passwordless**, so installing a tool you
  need is your call to make, not something to ask Jason for. `sway`, `foot`, `chromium`, `jq`,
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
  **GNOME Shell**. Known present: `google-chrome-stable`, `xorg-xwayland`, `sway 1.12`,
  `foot 1.27.0`, `waydroid 1.6.3`, `spanreed`, `jq`, `qemu`. Reachable by passwordless ssh
  from kaladin, but **sudo there requires a password** — Jason runs any *further* installs
  himself.

  **This file does not say what adolin is missing. Check, don't assume:**
  `ssh adolin 'bash -lc "command -v <tool>"'`. The **login shell is load-bearing** — some
  tools are per-user `uv` installs under `~/.local/bin`, invisible to `pacman -Q` and off
  `PATH` in a non-login ssh. `spanreed` is one, and reading it as absent is how you end up
  asking Jason for a password-gated install of something already there.

  The asymmetry is why there is no absent list. A **present** entry is self-confronting: you
  act on it by using the tool, so a wrong one fails loudly at the point of use. An asserted
  **absence** is the one shape no use can falsify, because you act on it by not trying, and a
  wrong one is discharged by silence. This bullet claimed three tools absent that were
  installed — the third while correcting the first two.
- **Neither host runs a tabbed WM as its session** — adolin's is GNOME Shell on seat0,
  kaladin has no session on its seat at all. The dock design depends on i3/sway tree
  semantics, so it has nowhere to run *for real* yet. Installed is a different question, and
  the answer is **both hosts, not one**: `sway 1.12` and `foot 1.27.0` are on kaladin *and* on
  adolin, explicitly installed there on 2026-07-30 — the same day this section was first
  written and marked verified. CI installs them too, and the `:wm` and `:cli` suites drive
  sway on every build.
  `WLR_BACKENDS=headless sway` gives a genuine sway tree drivable entirely over ssh via
  `swaymsg` — that is how structural probes should run, and it needs no display and no change
  to Jason's GNOME session.
- **Waydroid runs on kaladin, and Test 1 (occlusion lifecycle) is answered** — under qemu on
  2026-07-30 and natively on 07-31; see `docs/findings/`. **Nothing was gated on a DKMS
  module**: the LTS kernel's `CONFIG_ANDROID_BINDER_IPC_RUST=y` driver serves Waydroid
  unmodified, the `binder` filesystem type is registered at all times (`grep binder
  /proc/filesystems` → `nodev binder`), and `/usr/bin/waydroid` is installed. **`/dev/binderfs`
  is mounted on demand when `waydroid-container` starts, not at boot** — this said flatly that
  it "is mounted", which is false at rest and is the sentence an agent checks before deciding
  whether Waydroid work needs a kernel module. Measured 2026-08-07 on both hosts, which is what
  makes it a mechanism rather than a guess: on kaladin `systemctl is-active waydroid-container`
  → `inactive`, `mount | grep -i binder` → nothing (exit 1, against 31 mount lines, so the pipe
  was working) and `/dev/binderfs` does not exist; on adolin, where the unit is `active`, the
  same `mount` line reads `binder on /dev/binderfs type binder (rw,relatime,max=1048576)`. An
  absent `/dev/binderfs` therefore means the container is stopped and says nothing about the
  driver. What remains open is narrower — both runs were software-rendered, so buffer
  back-pressure from a gbm/DRM-backed Waydroid is untested and needs adolin's real GPU.
  **That is no longer an install.** Waydroid 1.6.3 has been on adolin since 2026-07-31,
  `/var/lib/waydroid` is
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
- **Never cache a flag value across an operation that could span a reload — read it from the
  snapshot.** Write to that rule; it is not yet enforced by anything running. `:config` *has*
  the reload mechanism — `FileConfigStore.watch(scope)` replaces the snapshot when the file
  changes, and `FileConfigStoreWatchTest` holds it — but **nothing calls it**, because every
  entry point in the build is one-shot: it reads the file, acts and exits (#43). So a flag flip
  applies to the next run, not to a running process, and `awakener-config list` says so. The
  per-operation re-reads in `:wm` and `:cli` are forward-looking rather than cargo, and the
  first entry point that outlives one operation is expected to call `watch` at the composition
  root. Until then this bullet describes a discipline, not an observed property — which is
  exactly why it is worth keeping: re-deriving it after the first daemon exists costs more than
  writing it this way now.
- A snapshot is total: a bad value degrades to that flag's default and is reported through
  `Config.problems`. The config file gets hand-edited against a running desktop, so a typo
  must not take the process down.
- **"Bad value" includes one that decodes and is out of range.** A flag whose sane values are
  narrower than its type says so — `Flags.int(key, default, description, Flags.within(1..100))`,
  `Flags.atLeast(0L)`, or `Flags.requires("…") { … }` for anything else. `Config.of` then reports
  it and `Config.get` degrades it exactly like a value that will not parse, `set` refuses it
  outright, and `awakener-config list` prints the range. Declare the requirement rather than
  coercing at the read site: coercing keeps the process up and tells nobody, which is the
  failure that costs the most to diagnose. For a rule spanning two flags — one flag's grace
  period outlasting another's wait — use `Flags.constraint(key, description) { config -> … }`
  from the same `*Flags` object; it reports and degrades nothing, because a pair that
  contradicts itself has no single key to degrade.

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
