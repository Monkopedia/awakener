# This agent is bound to a window, and has no tool to reach it with yet

**Provisional, and deliberately not wired.** This connector declares **no MCP server**. That is
the finding, not an omission — see `.claude/skills/computer-use-linux-window/SKILL.md`.

What it does carry:

- surface `{surface_id}`, pid `{pid}`
- agent `{agent_id}` (`{agent_name}`)
- facts as JSON: `.awakener/surface.json` — regenerated on every invocation
- the rules any AT-SPI reach for this window will have to hold, in the skill

**The window's `app_id`, `title` and `surface_key` are deliberately not printed here.** They are
chosen by the program that owns the window, and this file is read by an agent as instructions.
Read them from `.awakener/surface.json`, where they are JSON-escaped.

**Do not install `computer-use-linux` to make this work, and do not point some other
desktop-driving tool at this window instead.** The reasons it is not wired are properties of the
approach, not of that one binary, and they apply to whatever is reached for next.
