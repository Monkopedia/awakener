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

### Two facts are attacker-controlled, in the ordinary sense

`{title}` and `{app_id}` are chosen by the program that owns the window. **Never substitute
either into a shell script, a command line, or anything else with quoting.** A window titled
`'; rm -rf ~; '` is a window any web page can produce. Read them from `.awakener/surface.json`
at runtime instead, where they are JSON-escaped by the writer — that is one of the two reasons
that file exists. `{pid}` and `{surface_id}` are numeric and safe inline.

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
