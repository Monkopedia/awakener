# Desktop setup

How to get a real sway session on a real desktop running awakener end to end: the hotkey
brings up a `claude` docked beside the window you were in, under that surface's own agent
name, with that surface's own residue directory as its working directory.

This is the writeup of a live bring-up on **adolin** (Arch, GNOME as the normal session,
sway 1.12 as the awakener session) on 2026-08-23. Every command below was run somewhere;
the last section says which, and on which host, so you can tell what is measured from what
is reported. Nothing here is a dump of one machine's config — the sway config in particular
is presented as a minimal set of overrides with the reason for each, because the reasons are
what survive a different desktop.

The failures in [Gotchas](#gotchas) are the expensive part. If the hotkey stands a *second*
dock every time you press it, read gotcha 1 before anything else.

---

## 1. Prerequisites

On the desktop host:

| Tool | Verified version | Why |
| --- | --- | --- |
| `sway` | 1.12 | The compositor awakener drives. `:wm` speaks i3 IPC. |
| `foot` | 1.27.0 | The terminal the dock runs in. Anything that takes `-a <app_id>` works; `foot` is what the default `invoke.dock.command` names. |
| JDK 21 | `/usr/lib/jvm/java-21-openjdk` | Build and runtime. |
| `jq` | 1.8.2 | Used by the dock launcher below, not by awakener itself. |
| `claude` | in `~/.local/bin` | The agent. |
| `spanreed` | in `~/.local/bin` | The bus. awakener talks to it through its **CLI** only. |

Check them the way that survives a wrong answer — by asking the tool:

```sh
sway --version; foot --version; java -version; jq --version
command -v claude spanreed
```

`claude` and `spanreed` are typically per-user `uv` installs under `~/.local/bin`. They are
invisible to `pacman -Q` and, more importantly, **off `PATH` in a non-login shell** — which
is exactly the situation a GDM-launched sway session is in. That is gotcha 1.

---

## 2. Build and install

Follow **[Running it](../README.md#running-it)** in the README; it covers `:cli:installDist`
and how the launchers pick a JDK. It leaves you with three commands on `PATH`:

- `awakener-invoke` — the hotkey (`invoke`, `list`, `reap`)
- `awakener-config` — every flag, with its default and description
- `awakener-registry` — read and forget surface→agent bindings

The rest of this guide assumes those are runnable. The sway config below refers to them by
absolute path, since a `bindsym exec` does not inherit your shell's `PATH`.

---

## 3. Install the spanreed plugin — the one manual step

A Lifeless is a `claude` session that registers itself on the bus. It can only do that if the
**spanreed plugin is installed in Claude Code**. awakener does not install it, and nothing
about a green build tells you it is there.

```sh
claude plugin marketplace add Monkopedia/spanreed
claude plugin install spanreed@spanreed --scope user
```

**Install it once, at user scope, and every dock inherits it. There is no per-directory or
per-project enablement step.** This matters because each surface gets its own residue
directory, and a reader who assumes per-project setup will go looking for a step to repeat
per surface and not find one. On adolin, after a working bring-up:

- `~/.claude/plugins/installed_plugins.json` holds `spanreed@spanreed` with `"scope": "user"`.
- Neither project entry in `~/.claude.json` — not `$HOME`, not the residue directory a dock
  had been running in — has an `enabledPlugins` key at all. Both are `null`.

Confirm with:

```sh
claude plugin list
```

---

## 4. Configure awakener

Three flags. Everything else is left at its default, which is deliberate — a default is the
behaviour that would otherwise have been hard-coded, so an unconfigured awakener is already
correct. Set and read back:

```sh
awakener-config set registry.agent.spanreed_command "$HOME/.local/bin/spanreed"
awakener-config set invoke.dock.command \
  'foot -a {app_id} -- env SPANREED_AGENT_NAME={agent_name} '"$HOME"'/awakener/dock-launch.sh {residue}'
awakener-config set registry.residue.layout PER_APP_SESSION_DIR

awakener-config get registry.agent.spanreed_command
awakener-config get invoke.dock.command
awakener-config get registry.residue.layout
```

`set` echoes what it stored and `get` reads it back out of the file, so the pair is a real
round trip rather than a hopeful write. The file lands at
`${XDG_CONFIG_HOME:-~/.config}/awakener/config.json`, or wherever `AWAKENER_CONFIG` points.

**`registry.agent.spanreed_command`** — an absolute path. The default is the bare name
`spanreed`, which is resolved against whatever `PATH` the hotkey process inherited from sway,
which is the `PATH` GDM handed the session. See gotcha 1. Giving the absolute path here fixes
awakener's own lookup; it does **not** fix the dock's, because the dock is a separate process
launched by `foot`. Both need handling, and they are handled in different places.

**`invoke.dock.command`** — the command sway runs to stand a panel up. Four placeholders, and
which layer fills each one in is load-bearing:

- `{app_id}` is **passed through untouched** by `:cli`. `:wm` substitutes it inside `attach`,
  because under `wm.dock.identity=PER_SURFACE_APP_ID` the name is derived from a `con_id`
  that nothing above `:wm` is allowed to see. Leave it in the string.
- `{agent_name}` is the `SPANREED_AGENT_NAME` the registry minted or recalled. **This is the
  entire binding mechanism.** spanreed normally derives an agent id from a session's working
  directory; a surface has no working directory, so this variable is the only thing that makes
  the session that comes up *this surface's* agent rather than a stranger. A dock command that
  drops it animates a stranger.
- `{agent_id}` is the address that name resolves to, for a dock that wants to print it.
- `{residue}` is where this surface's durable model lives.

**`registry.residue.layout`** — the default `PER_KEY_FILE` gives each surface a single
`<slug>.md` and no directory. A directory is what makes `cd {residue}` in the launcher
meaningful: the agent gets a workspace it can write several files into, not one file it has to
append to. Two layouts give it one.

`PER_APP_SESSION_DIR` is the one to set. It writes `<residue>/<app>/<session>/`, so every
surface belonging to one application sits under a directory of that application's own — a place
two Lifelesses bound to two windows of the same app can both read and write. Measured on kaladin
against headless sway, one real `awakener-invoke invoke` against a `foot` surface, with
`registry.residue.dir` pointed at a scratch path:

```
residue/                                                            drwx------
  app-window-fake-surface-54a0a7421bcf5d6b7b1c021861a6d2f9/          drwx------
    window-fake-surface-32b1ee1a9793de3ecdf75459f7d9f47b/            drwx------
```

Both levels created `0700`. The session directory is named exactly what the flat layout would
have called that surface's residue, moved one level down; the application directory carries an
`app-` prefix, which is what keeps it from ever colliding with some surface's own directory.

`PER_KEY_DIR` is the flat version of the same thing — a directory per surface, all siblings, with
nothing on disk saying which of them are three windows of one editor. It was the answer before
the nested layout existed and is still fine if you do not want the extra level.

**Changing this moves nothing already written.** Residue left behind under a previous layout is
reported on the next prepare rather than silently orphaned, so a switch is visible rather than a
quiet loss of the model.

---

## 5. The dock launcher

`invoke.dock.command` above points at a wrapper script rather than at `claude` directly. Three
reasons, each measured. Save this as `~/awakener/dock-launch.sh` and `chmod +x` it:

```sh
#!/bin/sh
# awakener dock launcher.
# Called as: dock-launch.sh <residue-dir> [claude args...]
set -eu
dir=${1:?residue directory required}
shift || true
mkdir -p "$dir"

# ~/.claude.json is one file shared by every dock, and several can stand up at
# once, so the read-modify-write is taken under a lock. Without it, two docks
# racing lose one of the two trust entries -- and the loser prompts.
CJ="$HOME/.claude.json"
if [ -f "$CJ" ] && command -v jq >/dev/null 2>&1; then
    exec 9>"$CJ.awakener.lock"
    if flock -w 5 9; then
        if jq --arg d "$dir" '.projects[$d].hasTrustDialogAccepted = true' "$CJ" > "$CJ.awakener.tmp" \
           && jq -e . "$CJ.awakener.tmp" >/dev/null 2>&1; then
            mv "$CJ.awakener.tmp" "$CJ"
        else
            rm -f "$CJ.awakener.tmp"
        fi
        flock -u 9
    fi
    exec 9>&-
fi

# A GDM-launched session has no ~/.local/bin on PATH, and that is where both
# `claude` and `spanreed` live. See gotcha 1.
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) PATH="$HOME/.local/bin:$PATH"; export PATH ;;
esac

cd "$dir"
exec "$HOME/.local/bin/claude" "$@"
```

### Why each part is there

**`cd "$dir"`** gives the agent its own workspace. With `PER_APP_SESSION_DIR` (§4) that is a real
directory per surface.

**Pre-accepting the workspace-trust prompt.** A fresh directory makes Claude Code ask whether
you trust it. That question is fine once; it is useless on a hotkey, once per surface, in a
384px-wide panel. **There is no CLI flag for this in an interactive session** — `claude --help`
says the trust dialog is skipped only in non-interactive mode:

> The workspace trust dialog is skipped when Claude is run in non-interactive mode (via `-p`,
> or when stdout is not a TTY, e.g. piped or redirected output).

A dock is interactive by construction, so the only mechanism left is the state Claude Code
would have written itself: `projects["<dir>"].hasTrustDialogAccepted` in `~/.claude.json`.
awakener created that directory a moment earlier, so there is nothing in it to decide about.

**Why the `flock`.** `~/.claude.json` is one file shared by every dock, and the update is a
read-modify-write. Two docks standing up at once both read the old file, both write their own
entry, and the second write drops the first — so one of the two surfaces prompts anyway, which
looks like an intermittent bug rather than a race. Measured on kaladin, 12 concurrent launches
into an empty `.claude.json`, three runs each:

| | run 1 | run 2 | run 3 |
| --- | --- | --- | --- |
| lock removed (`flock -w 5 9` → `true`) | file unreadable | file unreadable | 1 of 12 entries |
| lock in place | 12 of 12 | 12 of 12 | 12 of 12 |

The unlocked variant does not merely lose entries — mid-run it leaves the file in a state `jq`
will not parse, because two `mv`s and two readers interleave. The lock is not a nicety.

**Prepending `~/.local/bin` to `PATH`.** Gotcha 1. This is the one that costs the most time.

---

## 6. The sway session config

Stock sway first, then awakener on top, in `~/.config/sway/config`:

```
include /etc/sway/config

set $awk /home/YOU/awakener/cli/build/install/awakener/bin

### tabbed by default #########################################################
workspace_layout tabbed

### no per-window decoration ##################################################
# A surface and its dock share one tab. Title bars make them read as two
# separate windows instead of one attached unit.
default_border none
default_floating_border none

### awakener ##################################################################
# $mod+a is stock sway's "focus parent". Take it, and put focus parent back
# somewhere reachable.
unbindsym $mod+a
bindsym --no-repeat $mod+a exec $awk/awakener-invoke invoke
bindsym $mod+Shift+a focus parent

# What is bound, and which agents are live on the bus right now.
bindsym --no-repeat $mod+o exec foot -a awakener-list -- sh -c '$awk/awakener-invoke list; echo; echo "[enter closes]"; read x'

# Take down docks whose surface has gone.
bindsym --no-repeat $mod+p exec $awk/awakener-invoke reap

# Optional, cosmetic. With borders off this text only ever appears in the tab
# bar; it is not part of the setup.
for_window [app_id="awakener-dock"] title_format "◆ DOCK — %title"
```

### `unbindsym` before every override

Re-binding an already-bound key **works**, and also raises sway's *"There are errors in your
config file"* nag over your session at startup. `unbindsym` first and it does not.

Measured on kaladin, two headless sway 1.12 sessions differing only in whether `unbindsym`
precedes the two overrides:

| | `Overwriting binding` log lines | swaynag error bar |
| --- | --- | --- |
| without `unbindsym` | 2 | 1 |
| with `unbindsym` | 0 | 0 |

The nag is real, not just a log line — the session without `unbindsym` mapped a layer surface
with `namespace swaynag`, and the one with it did not.

### `--no-repeat` on `$mod+a`

**Load-bearing.** Without it, *holding* the key repeats at the keyboard repeat rate, and every
repeat stands another dock and another `claude`. This is not theoretical; it happened during
bring-up. Every awakener binding above uses it.

### `workspace_layout tabbed`

One tab per surface. This is compatible with docks, because `attach` builds its **own** split
container *inside* the tab rather than competing with the tabbing: waking a surface splits that
tab 70/30 with both windows visible.

Measured on kaladin against headless sway 1.12 — a `foot` window as the surface, then one
`awakener-invoke invoke`:

```
workspace  layout=splith  1280x697@0
  con      layout=tabbed  1280x697@0
    con    layout=splith  1280x647@0
      con  app_id=fake-surface   896x647@0    visible=true
      con  app_id=awakener-dock  384x647@896  visible=true
```

Both leaves report `visible=true`: the surface and its dock are side by side inside the one
tab, not two tabs you flip between.

### `default_border none`, and the row that survives it

Stock sway draws a title bar on every window. On adolin's 4K display that is a **23px** row per
window, and with a dock attached you get two of them side by side — which is exactly the wrong
read, because a surface and its dock are one unit, not two windows:

```
[google-chrome]   border=normal bw=2 deco_h=23  title=Claude Code - Google Chrome
[awakener-dock]   border=normal bw=2 deco_h=23  title=✳ Claude Code
```

The two `default_border` lines in the config above remove them. Corroborated on kaladin under
`WLR_BACKENDS=headless sway` 1.12 — same mechanism, different font metrics, so the row is **25px**
there rather than 23:

| surface and dock, split inside one tab | `border` | `deco_rect.height` |
| --- | --- | --- |
| `default_border normal` | `normal` | 25 each |
| after `border none` | `none` | 0 each |

The undecorated row is not only a hand-built tree: a real `awakener-invoke invoke` against a
`foot` surface, on a session started with the two `default_border` lines above, comes up
`border=none deco_h=0` on both the surface and the dock, with the 25px on the `splith` container
that holds them.

**`default_border` applies to windows mapped after it takes effect, and to nothing else.** A
`swaymsg reload` re-reads the setting but does not re-decorate what is already on screen —
measured on kaladin, the two windows open across the reload stayed at `border=normal` while the
next window to map came up `border=none`. **`border=` is the field that carries this**, not
`deco_h`: under the `workspace_layout tabbed` of this config that new window is also a new tab,
so it reports `deco_h=25` despite having no border at all, for the reason two paragraphs down.

So after a reload, walk the tree and apply it by `con_id`. **By `con_id`, not `app_id`**: every
dock shares the `awakener-dock` `app_id`, so an `app_id` criterion cannot address them one at a
time, and a `for_window` rule only ever fires on a window that has yet to appear.

```sh
swaymsg -t get_tree \
  | jq -r '.. | objects | select(.app_id != null or .window != null) | .id' \
  | while read id; do swaymsg "[con_id=$id]" border none; done
```

That is the whole re-decoration path without a re-login. New windows need nothing.

**The row that remains is the tab bar, not a title bar.** Turning borders off does not empty the
top of the workspace, and the leftover header belongs to the tab, not to the window — including
when the container holds exactly one tab. Two shapes, measured on kaladin, both with `border
none` applied everywhere:

| shape | where the 25px sits |
| --- | --- |
| a single window, the only tab | on the **leaf**: `border=none` and `deco_h=25` |
| surface + dock split inside one tab (what `attach` builds) | on the **splith container**, `deco_h=25`; both leaves `deco_h=0` |

The attribution moves because sway hangs the tab's decoration on whatever node *is* the tab. So
`border=none deco_h=25` on a window is not a failed `border none` — it is a window that happens
to be a tab. The control says the same thing from the other side: drop `workspace_layout tabbed`
and the header is gone entirely, `deco_h=0` at every level, with nothing else in the config
changed.

There is no setting that keeps the tabbing and hides its header. `workspace_layout tabbed` and a
header-free workspace are mutually exclusive, so choose one — this guide keeps the tabs, and the
25px is the price. Do not go looking for a `tab_bar` toggle; it does not exist.

**Verify with `deco_rect.height`, not `rect.height - window_rect.height`.** The subtraction
answers a different question — it yields the *border width*, `2`, whether or not sway is drawing
a title bar, and reads as "sway draws almost nothing" when there is a full title row above the
window. `deco_rect` is the decoration sway actually allocates:

```sh
swaymsg -t get_tree | jq -r '.. | objects | select(.app_id != null)
  | "\(.app_id) border=\(.border) deco_h=\(.deco_rect.height)"'
```

`current_border_width` is not the field either: it stays `2` on a window whose `border` is
`none`, because it records the width a border *would* have.

### Optionally, pinning the layout

Layout drift is almost always an accidental keypress. If you want the session to stay tabbed,
remove the keys that leave a container in a layout you did not ask for:

```
unbindsym $mod+b   # splith
unbindsym $mod+v   # splitv
unbindsym $mod+s   # layout stacking
unbindsym $mod+e   # layout toggle split
# $mod+w (layout tabbed) is deliberately KEPT: the way back if a container
# does end up split.
```

**This is a floor, not a wall.** sway has no "forbid other layouts" switch, and a deliberate
`swaymsg layout splith` still works — as it must, since awakener's own split goes over the same
IPC socket. Unbinding the keys removes the accident, not the capability.

`$mod+f` (fullscreen) and `$mod+Shift+space` (floating) are worth keeping: neither leaves a
workspace in a different tiling mode.

### Getting out

Stock sway binds `$mod+Shift+e` to a **swaynag** with a *"Yes, exit sway"* button, which wants a
pointer. The reliable keyboard route out is a terminal and:

```sh
swaymsg exit
```

Two escape hatches worth knowing before you first log into this session: `Ctrl+Alt+F2` switches
VT, and `$mod+Shift+c` reloads the config, which is how you recover from a config edit that
broke a binding without logging out.

---

## 7. Putting the session in front of your display manager

Write a desktop entry so the session is selectable at login:

```
[Desktop Entry]
Name=Sway (awakener)
Comment=Sway session for awakener
Exec=sway
Type=Application
DesktopNames=sway;wlroots
```

**It has to go in `/usr/share/wayland-sessions/`** if your display manager is GDM. GDM reads
`/usr/share/wayland-sessions/`, `/usr/share/xsessions/` and
`/usr/share/gdm/greeter/wayland-sessions/` — verified by string-inspecting `/usr/bin/gdm` on
adolin. There is no `~/.local/share/wayland-sessions/` in that list, so a per-user entry will
never be offered, however correct it looks. (Both existed on adolin during bring-up. Only the
system one was ever listed.)

That path needs root. If your display manager is not GDM, check its own search paths — this is
a GDM fact, not a Wayland one.

---

## 8. Gotchas

### 1. A GDM-launched session has no `~/.local/bin` on `PATH` — and it presents as "reuse is broken"

This is the single most confusing failure in the whole setup, because the symptom names the
wrong subsystem.

**Symptom:** every press of `$mod+a` stands *another* dock on the same surface, even though
`invoke.when_animated` is `REUSE` and should decline the second one.

**Chain:** GDM starts sway with a `PATH` of roughly `/usr/local/bin:/usr/bin:...` — no
`~/.local/bin`. Read it from the running process, not from a shell you opened yourself, which
has been through your profile:

```sh
for p in $(pgrep -f claude); do
  grep -qsa 'SPANREED_AGENT_NAME=' /proc/$p/environ 2>/dev/null &&
    { echo "pid $p:"; tr '\0' '\n' < /proc/$p/environ | grep -E '^(PATH|SPANREED_AGENT_NAME)='; }
done
```

(The `SPANREED_AGENT_NAME` filter is what picks the dock's `claude` out of any others you have
running; `env` sets it for the child only, so a process that has it *is* a dock.)

During bring-up that showed a correct `SPANREED_AGENT_NAME` and a `PATH` with no
`~/.local/bin`. So the dock's `claude` could not find `spanreed` to register with. `spanreed
list` returned `[]`. `REUSE` then asks the bus "is this surface's agent animated?", is told no
every single time, and correctly concludes it should stand a dock. **The reuse logic was right
throughout; the bus had nothing in it.**

**Fix:** the `PATH` prepend in the dock launcher (§5), plus the absolute
`registry.agent.spanreed_command` (§4). They fix two different processes and you need both.

**Confirm the fix, don't assume it:** `spanreed list` must show the surface's agent after a
press. That is the reading that distinguishes a fixed `PATH` from a `PATH` you edited in the
wrong file.

### 2. NVIDIA is not the obstacle it looks like

sway 1.12 still ships the proprietary-driver machinery: `strings` on the binary yields
`!!! Proprietary Nvidia drivers are in use !!!` and a swaynag reading *"Proprietary GPU drivers
are not supported by sway. Do not report issues."*

Two things to know:

- The detection keys on the `nvidia-drm` kernel module being loaded, and the **open** kernel
  module (`nvidia-open`, `license: Dual MIT/GPL`) loads a module by that name too. So the
  message appears even on the open driver.
- sway's own text is *"To remove this message, launch sway with `--unsupported-gpu` or set the
  environment variable `SWAY_UNSUPPORTED_GPU=true`."* — **a message, not a refusal.**

sway 1.12 runs fine on adolin's RTX 4070 Ti SUPER with `nvidia-open` 610.57.04. Adolin's session
entry does pass `--unsupported-gpu`, which is how you silence the nag; whether it was ever
*required* there was not tested. Do not treat `--unsupported-gpu` as a prerequisite. If sway
starts, you did not need it; if it nags, add it.

### 3. `PATH` in a `bindsym exec` is sway's, not your shell's

A corollary of gotcha 1 that bites on its own: `bindsym $mod+a exec awakener-invoke invoke`
works when you test it from a terminal and fails from the keybinding. Use absolute paths in the
sway config — the `set $awk …` line in §6 exists for this.

---

## 9. Optional: voice input with Handy

**This is not part of awakener.** It is here because it was set up in the same session and the
audio problem in particular is subtle enough to be worth writing down. Skip this section
entirely if you do not want speech-to-text.

### Handy's own hotkey cannot work under a Wayland compositor

Handy registers its shortcut through Tauri's global-shortcut plugin, which has no way to grab
keys under Wayland. Its log says so:

```
register_tauri_shortcut duplicate error: Shortcut 'ctrl+space' is already in use
```

The arrangement that works is the compositor doing the grab and telling Handy over its CLI.
`handy --help` documents `--toggle-transcription`, `--toggle-post-process` and `--cancel` as
*"sent to running instance"*, which is exactly that path:

```
exec handy --start-hidden

# HOLD to talk: press starts, release stops.
# $mod+space is stock sway's "focus mode_toggle", which this replaces.
unbindsym $mod+space
bindsym --no-repeat $mod+space exec handy --toggle-transcription
bindsym --release   $mod+space exec handy --toggle-transcription

# Same, with LLM post-processing (needs an API key set inside Handy).
bindsym --no-repeat $mod+Ctrl+space exec handy --toggle-post-process
bindsym --release   $mod+Ctrl+space exec handy --toggle-post-process

# Throw away the recording in progress.
bindsym $mod+Escape exec handy --cancel
```

`--no-repeat` on the press for the same reason as `$mod+a`. Delete the two `--release` lines for
press-once-to-start / press-again-to-stop instead of hold-to-talk.

### You need a typing tool

Handy's `TypingTool` options are `wtype`, `kwtype`, `dotool`, `ydotool` and `xdotool`. With none
of them installed, `typing_tool: Auto` has nothing to choose and transcription completes while
producing no visible text. On Wayland, install **`wtype`**.

### The audio fix — the subtle one

**Symptom:** every PulseAudio-facing tool shows a healthy list of microphones, and Handy records
nothing.

**Cause:** Handy records through **cpal → ALSA**, not through the PulseAudio API, so it asks
ALSA for the *default* capture device. Arch ships
`/usr/share/alsa/alsa.conf.d/99-pipewire-default.conf` but does **not** symlink it into
`/etc/alsa/conf.d/`, so nothing defines `pcm.!default`. ALSA then falls through to a dsnoop on
card 0 — on this box the NVIDIA HDMI device, which has no capture at all — and you get `unable
to open slave`. Verified on adolin: the source file is present in `/usr/share`, and
`/etc/alsa/conf.d/` holds `50-pipewire.conf` but no `99-pipewire-default.conf`.

**Check** — this is the reading that tells you whether you have the problem, because it uses the
same default ALSA does:

```sh
arecord -d 1 -f cd /tmp/mictest.wav
```

**Fix**, user-level and no root, `~/.asoundrc`:

```
pcm.!default {
    type pipewire
    playback_node "-1"
    capture_node  "-1"
    hint {
        show on
        description "Default ALSA Output (currently PipeWire Media Server)"
    }
}

ctl.!default {
    type pipewire
}
```

Delete the file to revert. The system-wide equivalent, which needs root, is:

```sh
sudo ln -s /usr/share/alsa/alsa.conf.d/99-pipewire-default.conf /etc/alsa/conf.d/
```

On adolin the `~/.asoundrc` route took `arecord` from `unable to open slave` to a correct
32044-byte one-second capture.

---

## 10. What is not built yet

So the first hour is not spent looking for something that does not exist.

**`:bus`, `:pairing-mcp` and `:chrome` are not modules.** The build is `:config`, `:registry`,
`:wm` and `:cli`. Agents talk to spanreed directly through its CLI; there is no MCP surface-tool
layer and no Chrome attachment.

**One agent per app, not per window.** `SurfaceKey` is derived from facts that outlive the
window — under the default `registry.key.window_identity=APP_ID`, the `app_id`. So every window
of one application shares one agent and one residue. On adolin, both Chrome windows resolve to
the same key, `window:google-chrome`. `docs/design.md` settles that the binding unit is *"for
most apps … the window; for Chrome … the origin"* — and origin keying lives in `:chrome`, which
is unbuilt. `registry.key.window_identity` offers `APP_ID_AND_TITLE` and `APP_ID_AND_PID` if you
want windows separated today, with the trade-offs those names imply: titles churn, pids are
recycled.

**`$mod+a` is not a toggle.** `invoke.when_animated` is either `REUSE` (decline a second panel
when this surface's agent is already live on the bus) or `SECOND_DOCK` (stand another anyway,
as a deliberate second view onto one agent). Neither hides or closes a dock. Close a dock the
way you close its terminal, or use `awakener-invoke reap` for docks whose surface has gone.

**There is no daemon.** A press cannot raise a panel that is already standing.

---

## 11. What was verified, and where

Everything in this guide that is stated as a measurement was run. Where and how:

**On kaladin (headless, sway 1.12, JDK 21) — reproduced from scratch for this guide:**

- `awakener-config set` / `get` round trip for all three flags of §4, against an isolated
  `AWAKENER_CONFIG`.
- `registry.residue.layout=PER_APP_SESSION_DIR` produces the nested `<app>/<session>/` pair,
  both `0700`, from a real `awakener-invoke invoke` against a `foot` surface.
- The tabbed-plus-dock tree of §6, under `WLR_BACKENDS=headless sway`: a real `invoke` against
  a real `foot` surface, tree read back with `swaymsg -t get_tree`.
- The window-decoration numbers of §6: `default_border normal` gives `deco_h=25` and `border
  none` gives `deco_h=0`; a `swaymsg reload` leaves every window already mapped at
  `border=normal` while the next window to map comes up `border=none`; the 25px tab header
  survives `border none` in both tab shapes, which is why that last pair was read off `border=`
  and not off `deco_h`; and the control session without `workspace_layout tabbed` has no header
  at any level.
- The `unbindsym` measurement: two headless sway sessions, 2 `Overwriting binding` lines and 1
  `namespace swaynag` layer surface without it, 0 and 0 with it.
- The dock launcher's `cd`, `PATH` prepend and trust write, against a scratch `HOME` and a fake
  `claude` — including the control run with the `flock` removed, which loses 11 or 12 of 12
  entries. A guard that cannot be made to fail is not evidence that it fired.
- sway 1.12's proprietary-GPU strings, and `claude --help`'s trust-dialog wording.
- `./gradlew build` with `AWAKENER_REQUIRE_SWAY=1 AWAKENER_REQUIRE_SPANREED=1`.

**On adolin (read-only inspection of a live, in-use desktop):**

- Tool versions and locations in §1.
- The spanreed plugin's user scope, and both project entries' absent `enabledPlugins`.
- GDM's session search paths, by string-inspecting `/usr/bin/gdm`.
- The missing `/etc/alsa/conf.d/99-pipewire-default.conf` symlink.
- `nvidia-open` 610.57.04, `license: Dual MIT/GPL`, on an RTX 4070 Ti SUPER.
- `handy --help`'s CLI flags, and `wtype` present.

**Reported from the bring-up session, not re-run here:** the 23px title bars on adolin's 4K
display — kaladin reproduces the mechanism at 25px, which is font metrics, not a different
behaviour — the `arecord` before/after byte count,
the `PATH`-less dock environment read from `/proc/<pid>/environ`, the Handy Tauri shortcut log
line, and the repeated-`$mod+a` dock storm. These are all facts about a live GDM session on a
seat, which kaladin does not have.

**Not tested:** whether sway on adolin would start *without* `--unsupported-gpu`. sway's own
message says the flag removes a message rather than enabling anything, but that box has only
ever been launched with it.
