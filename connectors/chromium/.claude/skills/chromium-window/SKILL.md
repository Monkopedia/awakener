---
name: chromium-window
description: Use when acting on the Chromium window this agent is bound to — finding its CDP target, reading the page, or working out whether the browser is reachable at all. Covers what awakener knows about the window and what it cannot tell you.
---

# The Chromium window this agent is bound to

Awakener bound you to one window and wrote down what it knows about it. Everything below is
about turning that into a CDP target you can act on — or refusing to.

## What you were told

`.awakener/surface.json`, in your working directory, regenerated on every invocation:

| field | value here |
| --- | --- |
| `pid` | `{pid}` |
| `title` | (in the file — read it there, see below) |
| `app_id` | `{app_id}` |
| `surface_id` | `{surface_id}` |
| `surface_key` | `{surface_key}` |

**Read `title` and `app_id` from the file, not from this page.** They are chosen by the program
that owns the window, so they are the two fields you must never paste into a shell command. In
the file they are JSON-escaped; here they would be text.

## What that does not tell you

Three measurements from `docs/findings/`, and each one rules out a shortcut you would otherwise
take:

- **`pid` is not the window.** All eight windows of one Chromium instance report the same pid.
  They are a single Wayland client. `pid` narrows the candidate set to one browser process; it
  never selects a window inside it.
- **`app_id` is not an identity.** Chromium mints an `--app=` window's `app_id` from the *launch
  string*: it encodes host and path, drops scheme and port entirely, and a window that failed to
  connect and rendered an error page carries the same `app_id` as one that loaded. `https://x`,
  `http://x` and `https://x:8443` all produce `chrome-x__-Default`.
- **`title` is the only discriminating column you have, and it moves.** It is the page title, not
  the URL. Two tabs of one site share it.

## The rule

> Filter to the pid. Then match the title. **If zero or more than one candidate remains, stop and
> tell the user.** Do not pick the first, do not pick the most recently focused, do not widen the
> filter.

`./cdp.sh match` implements exactly that and is the thing to run:

```sh
./cdp.sh match      # one line: target id, url, websocket url — or a refusal
```

Exit `3` means nothing matched (the title has probably changed since awakener acted). Exit `4`
means several did. **Both are answers, not errors to work around.** Ask the user which page they
mean. Acting on the wrong window is the one failure here that they cannot detect afterwards.

## The browser may not be reachable at all, and that is common

CDP has two transports and awakener can use neither by fiat:

- **`--remote-debugging-pipe`** carries CDP over inherited file descriptors 3 and 4. There is no
  port and nothing on the network, which is why `docs/findings/2026-08-23-chromium-cdp-pipe.md`
  recommends it. It requires being the **parent process**. Awakener did not spawn this browser —
  it bound to a window that already existed — so pipe mode is unavailable here by construction.
- **`--remote-debugging-port`** can be reached by anything else on the machine that can open a
  socket, which is why it is only ever acceptable against a dedicated throwaway profile.

**This connector uses port mode, and establishes the port from the browser rather than being told
it.** `./cdp.sh` reads `/proc/{pid}/cmdline` — that pid is the one the compositor reports for
*this* window — and takes two things out of it: `--user-data-dir=`, which gives this window's
profile, and `--remote-debugging-port=`. If the profile holds a `DevToolsActivePort`, that wins,
because it is written by the browser *after* it has bound and so names the port it is on rather
than the port it was asked for.

**Both sources are needed, and that is measured rather than defensive.** Chromium 151 writes no
`DevToolsActivePort` at all when given an explicit port — checked against a live browser that was
demonstrably serving CDP on 9333 — so a check that consulted only the file would refuse every
explicitly-ported browser there is. It *does* write it for `--remote-debugging-port=0`, which is
the shape a supervisor should prefer, and which the command line cannot answer.

A listening 9222 that this pid did not open belongs to some other browser, and driving it would be
worse than doing nothing. `AWAKENER_CDP_PORT` overrides, and is cross-checked rather than trusted:
if it disagrees with what this window's browser reports, `cdp.sh` refuses instead of picking one.

It also refuses a browser running with **no** `--user-data-dir`, which is the everyday profile.

One thing that will trip you up if you go poking at this by hand: **`/proc/<pid>/cmdline` is not
reliably NUL-separated for Chromium.** It rewrites its own argv, so on 151.0.7922.173 it comes back
as one space-separated line — which means a per-argument match against it silently finds nothing on
a browser that was launched with exactly the flag you are looking for. Measured; it is why
`cdp.sh` normalises with `tr` and strips prefixes rather than splitting words.

If the check fails, say so and offer the relaunch — do not go looking for another way in:

```sh
chromium --user-data-dir=~/.local/share/awakener/chromium-profile \
         --remote-debugging-port=9222 --app=<url>
```

Never suggest pointing it at the user's everyday profile. Anything that can reach that port can
drive a logged-in browser, and Jason's Chrome profile is 5.7 GB of exactly that.

## What this connector is not

It is discovery over the CDP HTTP endpoints — `/json/version`, `/json/list` — and nothing more.
Driving a page needs the WebSocket, which belongs to the `:chrome` module when that module
exists. Identifying the right target is the part that has to be correct first, and it is the part
this connector is for.
