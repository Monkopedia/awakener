# Connector definitions

A **connector** is a directory. Everything in it is rendered — every `{fact}` placeholder
replaced with what awakener knows about the window being invoked on — and copied into that
surface's residue directory, which is the agent's working directory.

That is the whole mechanism. Awakener holds no list of connector names, no enum of connector
kinds, and no schema for what a connector may contain: adding one is adding a directory, and it
takes effect on the next hotkey press with no rebuild and no restart.

```
awakener-invoke connectors                     # what is installed, and where it was found
awakener-invoke invoke --connector chromium    # apply one to the focused window
awakener-invoke invoke --no-connector          # explicitly none, whatever connector.default says
awakener-config set connector.default chromium # apply one on every press
```

This directory is what a build ships. The search path is `connector.dir`, which unset means
`$XDG_CONFIG_HOME/awakener/connectors` followed by `$AWAKENER_CONNECTORS` — the launcher points
the second at this directory inside the install tree. **Earlier entries win**, so a copy of
`chromium` under `~/.config/awakener/connectors` shadows the shipped one without deleting
anything.

## What is in a directory

| Path | Who reads it |
| --- | --- |
| `connector.json` | awakener, and only awakener. Never materialised. |
| everything else | rendered and copied into the residue directory |
| `.awakener/surface.json` | **generated** by awakener into the target, every press |

`connector.json` is deliberately tiny — `description`, `requires`, `provisional`,
`provisional_reason` — because everything about *how* the tool is reached belongs in the
connector's own files, which awakener renders and never reads.

`requires` lists the [surface facts](#surface-facts) the connector cannot work without. They are
checked **before anything is written**, so a refusal leaves the residue directory untouched. What
a refusal does is `connector.missing_fact`'s call, and it defaults to standing no panel at all.

## Surface facts

`{surface_id}` `{app_id}` `{title}` `{pid}` `{surface_key}` `{agent_id}` `{agent_name}`
`{residue}` `{connector}` `{connector_dir}`

An unknown `{…}` is copied through untouched, so JSON objects and shell parameter expansion need
no escaping. Substitution is a single pass, so an expansion is never rescanned — a window titled
`{residue}` stays a window titled `{residue}`.

### Three facts carry bytes the window chose, and awakener refuses to render them

`{title}`, `{app_id}` and `{surface_key}` are chosen by the program that owns the window. Any web
page sets `document.title`; any Wayland client declares its own `app_id`; `surface_key` is built
from one of those by an escape that guarantees *injectivity* rather than safety, so it passes
newlines and backticks straight through.

**Under the default `connector.window_facts=REFUSE`, a connector file that references any of the
three is refused at load** — `awakener-invoke connectors` reports it, and nothing is rendered.
`connector.window_facts=RENDER_RAW` substitutes them verbatim and warns on every press.

The rule is on the **trust class**, not on those three names: `SurfaceFact` carries a `Trust`, it
is a required constructor argument with no default, and everything that enforces this iterates the
class. A fact added later is covered without anybody remembering this paragraph.

#### Two contexts, and the second is the one every connector here actually uses

- **Quoting contexts** — a shell script, a command line. A window titled `'; rm -rf ~; '` is a
  window any web page can produce.
- **Instruction contexts** — a `.claude/skills/.../SKILL.md`, a `CLAUDE.md`, anything the agent
  reads as *directions rather than data*. This is the one that bites, because it is what a
  connector is mostly made of, and because the injection needs no metacharacter a reader would
  recognise: a newline and a `#` is enough. A window whose client declared a multi-line `app_id`
  had it substituted into the shipped chromium skill's fact table, broke out of the backtick
  span, and became top-level Markdown twenty lines above the disambiguation rule that skill
  exists to state.

Escaping cannot be chosen for you, because Markdown is not shell is not JSON, and only you know
what each of your files is. **The safe carrier already exists**: read them from
`.awakener/surface.json` at runtime, where the writer escapes them as JSON strings — that is one
of the two reasons that file exists, and its escaping is held under hostile input by its own
tests. `{pid}` and `{surface_id}` are integers and are safe anywhere; `{residue}`, `{agent_id}`
and `{agent_name}` are built from a slug in `[a-z0-9-]`.

### Awakener will not write outside the residue directory

`connector.target` must be a relative path with no `..` segment, and the resolved path is checked
against the residue directory's real path — the flag requirement cannot see a symlink and the
resolved check cannot refuse a value before it is used, so both run. A symlink **inside** a
definition is refused at load rather than dereferenced, and a symlink on the destination path is
refused outright rather than being `connector.existing`'s to decide.

`connector.executable=STRIP` drops the execute bit awakener would otherwise carry across, for a
deployment that does not want executables minted in the residue directory at all.

## How a connector points its tool at *one* window

This is the hard part, and awakener cannot do it for you. Measured, in `docs/findings/`:

- **`pid` narrows and never selects.** All eight windows of one Chromium instance report one
  pid — they are a single Wayland client.
- **`app_id` is not an identity.** A Chromium `--app=` window's `app_id` is minted from the
  launch string, encodes host and path, drops scheme and port, and is reported identically by a
  window that never loaded the site.
- **`title` is the only discriminating column, and it moves.**

So the join is *pid first, then title within that pid*. AT-SPI's `application → frame` is exactly
that shape; CDP's target list is the same shape one protocol over. Every shipped connector states
this rule in its skill, and a new one should copy it verbatim:

> Filter to the pid. Then match the title. **If zero or more than one candidate remains, stop and
> tell the user.** Do not pick the first, do not pick the most recently focused, do not widen the
> filter.

A connector that silently acts on the wrong window is the worst outcome available here, and it is
the one the user cannot detect afterwards.
