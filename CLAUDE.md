# Working in this repo

**Status: newly stood up, project substance not yet defined.** The fleet infrastructure
(agent session, bot access, labels, webhook, triage + code-health rotations) is wired, but
the project's purpose, stack, and build/test commands are pending — Jason will seed them.

## If you are an agent reading this before it has been filled in

**Do not infer the project's shape from its name, its file layout, or from other fleet
repos.** An empty or near-empty repo invites guessing, and a confident guess recorded here
becomes the thing the next agent trusts. Concretely:

- **Don't invent build/test commands.** If you need to verify something and don't know how,
  ask rather than trying a plausible-looking `./gradlew` invocation.
- **Don't assume the stack.** Most of the fleet is Kotlin/KMP; that is not evidence about
  this repo.
- **A triage or code-health pass on an empty repo should report a clean no-op**, not
  manufacture findings. Zero open issues and no source is a legitimate result.
- **When the project takes shape, replace this section** with the real rules: what the
  build is, what the verification commands are, what's hands-off for autonomous runs, and
  any API/UX gates. That's what triage and the work/review subagents read.

## Conventions that already apply

- **PUBLIC repo.** Auto-merges are visible to the world, so lean conservative: when in
  doubt escalate rather than auto-merge. Public-API and breaking changes are owner-gated
  (tier 3) — see `review-policy.md` in the urithiru fleet config.
- **All bot writes go through the `coderbot` wrapper by its FULL path**
  (`/home/jmonk/git/urithiru/coder-bot/coderbot`), never bare `coderbot` and never plain
  `gh` — plain `gh` runs as `Monkopedia` and silently misattributes bot actions to the
  owner.
- **Commit style**: subject ≤ 70 chars, present tense; body explains *why*. Co-author
  trailer on agent commits.
- Fleet-wide workflow rules (review tiers, triage classification, the code-health pass)
  live in `~/git/urithiru/workflows/` and apply here unless this file overrides them.
