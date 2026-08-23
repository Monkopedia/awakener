# Probe: what `app_id` Chromium gives an `--app=` window under Wayland

**Date:** 2026-08-23 · **Chromium:** 151.0.7922.173 (Arch) · **sway:** 1.12 · **hosts:**
kaladin and adolin, both headless · **status:** measured; rules out one approach, leaves the
replacement open

`docs/design.md` binds a browser agent to an **origin**, and Open #3 asks whether
tabs-as-toplevels (`--app=URL`) is the way to get there. The `:wm` layer's only handle on a
window is what sway reports for it, so the question reduces to a measurement: **what
`app_id` does Chromium hand the compositor for an app-mode window, and can it be made to
carry the origin?**

It cannot. Both halves of that are wrong for origin binding, and in opposite directions.

## What was run

Same shape as the [sway binding probe](2026-07-30-sway-binding-probe.md) — headless
wlroots, no display, no seat, driven entirely over IPC:

```sh
export XDG_RUNTIME_DIR=/run/user/1000
export SWAYSOCK=/run/user/1000/awk-chrome-probe/sway.sock   # NOT the scratchpad: a Unix
                                                            # socket path caps at 108 bytes
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 \
  sway -c probe.conf                                        # probe.conf: xwayland disable

WAYLAND_DISPLAY=wayland-1 /usr/bin/chromium \
  --ozone-platform=wayland --user-data-dir=<throwaway> \
  --no-first-run --disable-gpu --app=<url>

swaymsg -t get_tree | jq -r '.. | objects | select(.app_id != null)'
```

`--ozone-platform=wayland` matters: everything below is about the Wayland path. Take
`WAYLAND_DISPLAY` from the sway you just started rather than from the environment — kaladin
had another agent's compositor on `wayland-2`, and Chromium's failure against it
(`Failed to connect to Wayland display: Connection refused`) looks nothing like a wrong
target.

## What came back

| `--app=` URL | `app_id` |
| --- | --- |
| `https://example.com` | `chrome-example.com__-Default` |
| `http://example.com` | `chrome-example.com__-Default` — **same** |
| `https://example.com:8443` | `chrome-example.com__-Default` — **same** |
| `https://example.com/some/path` | `chrome-example.com__some_path-Default` — **different** |
| `https://www.iana.org` | `chrome-www.iana.org__-Default` |

So the `app_id` is `chrome-<host>_<path>-<profile>`: it encodes **host and path**, and
**drops scheme and port entirely**. The `:8443` window is the clearest case — it could not
connect at all and rendered an error page, and it still landed on the same `app_id` as the
one that loaded.

Three things that are *not* fallbacks:

- **`--class` does nothing.** `--class=awakener-origin-7f3a --app=https://example.com`
  produced `chrome-example.com__-Default` again; the window joined the existing group.
  `--class` is X11-only, and under Wayland Chromium derives `app_id` from the app-mode URL.
- **The title is the page title, not the URL.** Both the `http` and the `https` window
  reported `name=Example Domain`. Nothing in `name` separates them.
- **`pid` does not separate windows either.** All eight windows from one browser instance
  reported the same `pid`, because they are one Wayland client — the same substrate limit
  `docs/design.md` records for Waydroid, arrived at from a different direction.

## Reproduced on adolin

The same six launches were re-run on **adolin** — Chromium 151.0.7922.173, its own headless
`WLR_BACKENDS=headless` sway 1.12 on a fresh `wayland-2`, with Jason's live GNOME session on
`wayland-1` untouched. **Every `app_id` came back identical to kaladin's**, including the
`--class` no-op. So this is a property of Chromium on Wayland, not of one host.

### The launch string is the source, not the page

adolin's `:8443` window is the sharpest single datapoint in this note. Nothing was listening
on that port, so the window **never loaded the site** — its title came back `example.com_/`,
the URL rendered as a failure, not `Example Domain`. Its `app_id` was minted identically to
the two windows that did load. (On kaladin the same launch eventually resolved to an error
page titled `example.com | 522: Connection timed out` — the same point by a different route:
an error page, not the site.)

**The `app_id` is derived from the launch URL string, not from what actually loaded.** So a
window pointed at a URL that fails, redirects elsewhere, or is simply mistyped still claims
that origin's identity, and an agent bound on that key would attach its accumulated model to
a window that was never on the origin at all.

This is a stronger objection than the scheme/port collision, and it is worth keeping separate
from it: **a collision could in principle be normalised away** — canonicalise the key, refuse
to bind when two schemes share a host — but nothing you can compute from a string tells you
whether the page at the other end is the page. Only the loaded document knows, and reading
the loaded document is exactly what the WM layer cannot do.

## What that rules out

**The `app_id` is wrong in both directions at once.**

- **It over-groups across a security boundary.** `http://example.com` and
  `https://example.com` are different origins that collide onto one `app_id`. Bound on that
  key, one agent — and the accumulated model of the user that is the entire durable asset —
  would be shared across the boundary the browser itself refuses to share cookies across.
  Ports collide the same way, so a local dev server on `:8443` and the public site are one
  agent.
- **It under-groups within an origin.** Two pages of the *same* origin get *different*
  `app_id`s, because the path is in the key. The origin-scoped memory `docs/design.md` asks
  for would fragment per path — a new agent, cold, for every section of a site.

There is no `wm.dock.identity=PER_SURFACE_APP_ID` equivalent here. That flag works because
awakener launches the dock and chooses its `app_id`; with Chromium the `app_id` is
Chromium's to derive, and `--class` is the knob that would have overridden it.

### The one lever, and why it is not the answer

`--profile-directory` **does** move the suffix: `--profile-directory=Origin7f3a` with
`--app=https://example.com` yielded `chrome-example.com__-Origin7f3a`, distinct from the
`-Default` group. So it is not quite true that nothing on the command line reaches the
`app_id` — the profile segment does.

It does not rescue the approach, for two reasons that are worth separating:

1. A Chromium profile is a **cookie jar and a login state**. One profile per origin means
   logging in again per origin and losing anything shared across them; that is a different
   product, not a tweak.
2. It still does not fix the scheme/port collision or the path split, because the *host and
   path* segments are unchanged. It adds a discriminator beside a broken key rather than
   repairing it.

`--user-data-dir` is not even a lever: a second browser instance on its own data dir
produced `chrome-example.com__-Default` again, colliding with the first instance's window.
It differed only in `pid`.

## What this leaves

**`--app=` per origin cannot deliver origin binding on its own.** Something that knows a
window's *real* URL is required — the design brief already said "use CDP or an extension",
and this is the measurement that turns that from a preference into a constraint.

**That "something" is now settled: CDP**, decided by Jason on 2026-08-23; see
[Substrate decisions](../design.md#substrate-decisions). This note is the argument for why
one was needed, not the argument for which — and the URL it needs is one CDP demonstrably
returns for exactly these windows, measured in the
[pipe probe](2026-08-23-chromium-cdp-pipe.md).

What this note still settles nothing about:

- **`--app=` per origin versus multiplexing behind a manager** — design.md Open #3. The
  measurement does not kill `--app=` as a *window strategy*; it kills `app_id` as the
  *binding key*. An `--app=` window whose origin is resolved out-of-band is still on the
  table, and so is one window multiplexed behind a manager.

## Scope of the claim

This measures **Chromium 151.0.7922.173 on Wayland via ozone**, against sway 1.12. The
derivation is Chromium's, so a different major version may change it, and the X11 path is
explicitly not what was measured — `--class` is reported here as ineffective *under
Wayland*, which is the only case awakener runs in. Anyone relying on the table above should
re-run the four `--app=` lines; it is a two-minute probe and needs no display.
