# This agent is bound to a Chromium window

- surface `{surface_id}`, pid `{pid}`, app_id `{app_id}`
- durable key `{surface_key}`, agent `{agent_id}` (`{agent_name}`)
- facts as JSON: `.awakener/surface.json` — regenerated on every invocation
- helper: `./cdp.sh` — see `.claude/skills/chromium-window/SKILL.md` for the rules

**Read the skill before touching the browser.** It carries the one rule that matters: awakener
can tell you the pid and the title of the window it acted on, and neither of those identifies a
window on its own. If you cannot narrow the CDP target list to exactly one, stop and ask.
