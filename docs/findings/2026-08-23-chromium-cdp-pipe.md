# Probe: CDP over a pipe, and what it costs

**Date:** 2026-08-23 · **Chromium:** 151.0.7922.173 (Arch) · **hosts:** kaladin and adolin ·
**status:** measured; a recommendation, **not** a decision

`docs/design.md` says of the browser adapter: *"If using `--remote-debugging-port`, it wants
a dedicated profile — anything that can reach localhost can drive a logged-in browser."*
Now that [CDP is the adopted adapter](../design.md#substrate-decisions), that sentence is
load-bearing rather than hypothetical, so it is worth checking the assumption inside it.

**The assumption is that CDP implies a listening socket. It does not.**

## The switch exists

```sh
$ strings -a /usr/lib/chromium/chromium \
    | grep -oE 'remote-debugging-[a-z]+|remote-allow-origins' | sort -u
remote-allow-origins
remote-debugging-enabled
remote-debugging-pipe
remote-debugging-port
remote-debugging-targets
```

Identical output on **both** hosts.

### Two ways this measurement returns a confident wrong answer

Worth recording, because both produce a clean empty result rather than an error:

- **`/usr/bin/chromium` is the wrong file.** On Arch it is a 14 KB stripped ELF shim that
  `execv`s `/usr/lib/chromium/chromium` (320 MB). Searching the shim finds **zero** hits —
  not `--remote-debugging-pipe`, not even `--user-data-dir`, which demonstrably works. An
  empty result there means "you searched the launcher", not "the switch is absent".
- **`grep -x` is the wrong matcher.** `strings` emits long runs, so switch names are usually
  embedded in a larger line. On kaladin `grep -xE 'remote-debugging-[a-z]+'` returned 3 of
  the 5, silently dropping `remote-debugging-enabled` and `remote-allow-origins` — a
  *partial* false negative, which is worse than an empty one because it looks like a
  complete answer. Use `grep -o`.

## It works, and there is no port

A strings hit is evidence the string is in the binary, not that the feature works — so the
switch was driven end to end. CDP pipe mode is a pair of inherited file descriptors: the
browser reads NUL-terminated JSON on **fd 3** and writes replies on **fd 4**.

```
Browser.getVersion  ->  {"id":1,"result":{"protocolVersion":"1.3",
                         "product":"Chrome/151.0.7922.173", ...}}
ss -ltnp | grep -i chromium  ->  (no output: nothing listening on any TCP port)
```

Run on **both hosts**, with identical results on each.

**The control fired.** An earlier attempt that set up the pipes but did not land them on fds
3 and 4 was refused outright — `ERROR:chrome_main_delegate.cc: Remote debugging pipe file
descriptors are not open` — so the successful run is distinguishable from a run where the
switch was quietly ignored.

### The whole picture in one window

Run against headless sway with `--ozone-platform=wayland --remote-debugging-pipe
--app=https://example.com/some/path`, asking both layers about the **same** window:

```
sway -> app_id=chrome-example.com__some_path-Default     (host + path, no scheme, no port)
CDP  -> url=https://example.com/some/path                (the actual URL)
```

That is the pairing the [`app_id` probe](2026-08-23-chromium-app-mode-app-id.md) argues for,
demonstrated rather than asserted: the compositor cannot supply the origin, CDP can, and the
channel that carries it need not be reachable from anywhere.

One incidental property, useful for teardown: **closing the pipe exits the browser.**
Dropping the write fd ended the process with `rc=0`, no signal needed. Browser lifetime is
bound to the controlling process, which is the behaviour a supervised dedicated profile
wants anyway.

## What it costs — a constraint, not a footnote

**fd inheritance means awakener must be the process that spawns the browser.** There is no
way to hand a pipe to a Chromium that is already running, so pipe mode **rules out attaching
to a browser the user already had open**. `--remote-debugging-port` does not have that
restriction, which is the same property that makes it reachable.

For a dedicated profile that awakener launches anyway this is arguably the right shape — the
lifetime binding above falls out of it. But someone will assume "attach to my existing
browser" is available unless it is written down, so it is written down here.

## Status: recommended, not decided

The recommendation is pipe mode, because it **removes** the risk `docs/design.md` names
rather than mitigating it — there is no localhost surface to reach, so the dedicated profile
stops being the only thing standing between a stray local process and a logged-in browser.
It stays a dedicated profile regardless; this is defence in depth, not a replacement.

**The choice belongs to `:chrome` when `:chrome` is built.** Nothing here is a decision, and
the spawn constraint is a real cost that a design not yet written may weigh differently.

## Scope of the claim

Chromium **151.0.7922.173** on Arch. The `--headless=new` drive was run on **both** hosts
and answered identically — protocol **1.3**, `Chrome/151.0.7922.173`, no listening socket,
clean `rc=0` on pipe close. The `--ozone-platform=wayland --app=` pairing against headless
sway 1.12 — the `sway`-versus-CDP comparison above — was run on **kaladin only**; adolin
corroborated the `app_id` half of it separately, in the
[`app_id` probe](2026-08-23-chromium-app-mode-app-id.md). Pipe framing (fd 3 in, fd 4 out, NUL-terminated JSON) is Chromium's, not a stable
promise of ours; re-run `Browser.getVersion` over the pipe on a major version bump.
