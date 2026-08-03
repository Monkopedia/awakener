# awakener

Persistent, per-surface agents bound to the windows on a Linux desktop.

Every surface you work in has an agent attached to it, holding the context you'd otherwise
hold in your head. A hotkey brings it up, docked to the window it belongs to. Agents
coordinate with each other over [spanreed](https://github.com/Monkopedia/spanreed).

What gets offloaded is not the task — it's the accumulated model of *you* on that surface:
preferences, prior decisions, how you use this app, plus whatever it takes to drive it.
Tasks churn; that residue persists.

> **Status:** the binding loop works end to end against sway — a window is enumerated, its
> agent recalled or minted, and a panel docked inside that window's tab. There is no daemon,
> so a press cannot raise a panel that is already standing. See
> [`docs/design.md`](docs/design.md) for the full brief — layers, substrate decisions, the
> memory model, what's deliberately out of v1, and what's still open.

## Running it

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/awakener/bin:$PATH"
```

That produces three commands. `awakener-config list` prints every flag with its default and
its description; `awakener-registry` reads and forgets surface→agent bindings; and
`awakener-invoke` is the hotkey:

```
bindsym $mod+a exec awakener-invoke invoke
```

`invoke` with no argument acts on the focused window, which is all a key press carries.

The launchers take a JDK from `AWAKENER_JAVA`, then `JAVA_HOME`, then the JDK the build used,
then `PATH` — and check the version of whichever one they picked, so anything older than 21
(or not a JVM at all) is reported and exits 70 rather than failing as an
`UnsupportedClassVersionError`. They also resolve symlinks, so `ln -s … ~/.local/bin/awakener-invoke`
is a fine thing to name in a `bindsym`.

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
