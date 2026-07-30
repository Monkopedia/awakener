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

## Status: design settled, no code yet

As of 2026-07-30 the repo holds documentation only. **There is still no build system and no
CI**, so the anti-invention rule from the original scaffolding still applies in full:

- **Do not invent build/test commands.** There is no `./gradlew` here yet. If you need to
  verify something and there is no documented way to do it, say so — "I could not verify X
  because the repo defines no build" is a legitimate result. Replace this section with the
  real verification commands in the same change that introduces the build.
- **Zero check-runs is the expected state**, not "pending". There is no `.github/workflows/`
  at all, so no `check_suite` will ever arrive. Never wait on one.
- **A triage or code-health pass with no source is a clean no-op**, not a prompt to
  manufacture findings.

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

**Talk to spanreed through its CLI, never through its files.** `spanreed` exposes
`register` / `send` / `recv` / `list` / `name` / `focus` / `status` / `conjoin` as a
versioned public contract. `~/.claude/spanreed/registry.json` and its lockfile are internal;
reimplementing that locking discipline in a second language would duplicate invariants that
are not ours to hold. Known gap: registry entries are keyed on `pid` + `pid_start` for
liveness, so the manager-mirrors-managed-agents pattern (Chrome origins registering under
the manager's PID) may need a CLI path that does not exist yet — that is a spanreed
conversation, not a local workaround.

## Environment (verified 2026-07-30 — re-check before relying on it)

- **kaladin** — this repo's host. Headless: no `/dev/dri`, no seat, no compositor. Cannot
  run anything needing a display.
- **adolin** — the desktop, and awakener's actual target. GPU, active seat0, running
  **GNOME Shell**. `google-chrome-stable` and `xorg-xwayland` installed. Reachable by
  passwordless ssh from kaladin, but **sudo there requires a password** — Jason runs
  installs himself.
- **No tabbed WM is installed on either host.** The dock design depends on i3/sway tree
  semantics, so it has nowhere to run for real yet. `WLR_BACKENDS=headless sway` gives a
  genuine sway tree drivable entirely over ssh via `swaymsg` — that is how structural probes
  should run, and it needs no display and no change to Jason's GNOME session.
- **No Waydroid and no binder module** (`binder_linux` absent). Test 1 (occlusion lifecycle)
  is gated on a DKMS kernel module on Jason's daily driver, so it is not something to set up
  autonomously.

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
- **Review tiers** (re-tiered 2026-07-30 against the real stack; see `review-policy.md` in
  the urithiru fleet config, which is authoritative). `public_api` and `breaking_change` are
  **tier 2** — this is an application, not a published library, so module boundaries are
  internal contracts: surface them in the verdict, don't owner-gate them. Scaffolding is
  **tier 1** — the Gradle skeleton, CI workflow, license, gitignore, module stubs, and docs
  are not gated. `security` is **tier 3**.
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
- **All bot writes go through the `coderbot` wrapper by its FULL path**
  (`/home/jmonk/git/urithiru/coder-bot/coderbot`), never bare `coderbot` and never plain
  `gh` — plain `gh` runs as `Monkopedia` and silently misattributes bot actions to the
  owner.
- **Commit style**: subject ≤ 70 chars, present tense; body explains *why*. Co-author
  trailer on agent commits.
- Fleet-wide workflow rules (review tiers, triage classification, the code-health pass)
  live in `~/git/urithiru/workflows/` and apply here unless this file overrides them.
