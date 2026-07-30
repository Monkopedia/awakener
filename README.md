# awakener

Persistent, per-surface agents bound to the windows on a Linux desktop.

Every surface you work in has an agent attached to it, holding the context you'd otherwise
hold in your head. A hotkey brings it up, docked to the window it belongs to. Agents
coordinate with each other over [spanreed](https://github.com/Monkopedia/spanreed).

What gets offloaded is not the task — it's the accumulated model of *you* on that surface:
preferences, prior decisions, how you use this app, plus whatever it takes to drive it.
Tasks churn; that residue persists.

> **Status:** design settled, implementation not started. There is no build yet. See
> [`docs/design.md`](docs/design.md) for the full brief — layers, substrate decisions, the
> memory model, what's deliberately out of v1, and what's still open.

## Shape

The binding layer is a tiny, compositor-agnostic interface — `resolve(surface)`,
`attach(surface, dock)`, and a change notification — implemented first against sway's IPC.
The dock is a real window placed as a tree sibling inside the tab, which is native i3/sway
tree behavior rather than a trick.

Above that sit surface managers for hosts that multiplex many surfaces behind fewer OS
windows (Waydroid, Chrome), and the durable per-surface agents themselves.

Written in Kotlin.

## License

Not yet chosen.
