# This agent is bound to a Chromium window

- surface `{surface_id}`, pid `{pid}`
- agent `{agent_id}` (`{agent_name}`)
- facts as JSON: `.awakener/surface.json` — regenerated on every invocation
- helper: `./cdp.sh` — see `.claude/skills/chromium-window/SKILL.md` for the rules

**The window's `app_id`, `title` and `surface_key` are deliberately not printed here.** They are
chosen by the program that owns the window — any web page can set `document.title`, any Wayland
client can declare an `app_id` — and this file is read by an agent as instructions. Read them from
`.awakener/surface.json`, where they are JSON-escaped.

**Read the skill before touching the browser.** It carries the one rule that matters: the pid and
the title of the window awakener acted on do not identify a window between them. If you cannot
narrow the CDP target list to exactly one, stop and ask.
