# Probe: Waydroid occlusion lifecycle, native re-run (Test 1, second pass)

**Date:** 2026-07-31 · **host:** kaladin, **natively — no VM at all** (Waydroid is an LXC
container on the host kernel) · **kernel:** 6.18.41-1-lts with
`CONFIG_ANDROID_BINDER_IPC_RUST=y` · **Waydroid:** 1.6.3-1 · **Android:** 13 / SDK 33,
LineageOS `20.0-20260403-VANILLA` (byte-identical image to the first probe) · **sway:** 1.12
· **status:** the original answer reproduces in full; three divergences, one of them a live
hazard for any faster machine; caveat 1 retired, caveats 2 and 3 stand

Re-run of `2026-07-30-waydroid-occlusion-probe.md` with the TCG variable removed. That probe
ran under qemu without KVM at ~10-20x slowdown and named it the thing most likely to hide a
timing-gated lifecycle stop. This run has no virtualisation layer of any kind.

## The answer, again

**An occluded-but-open window stays RESUMED. Nothing changed.** Every claim reproduces, over
a sampling schedule an order of magnitude finer than the original (3s, 10s, 20s, 40s, 60s,
120s … 720s), under all three occlusion mechanisms.

**The corroboration is now decisive rather than suggestive.** JS busy loop in a WebView,
CPU jiffies per 90s: visible+focused 7253, **fully occluded 7212**, visible again 7248, all
windows off-screen 7269 — a spread of **0.8%**. The original's equivalent numbers ranged
14195→23323→23899→19423; that 40% jitter was TCG. A paused activity would take this to ~0
via `WebView.onPause()`. Occluded is now measurably *identical* to visible, not merely
"indistinguishable given the noise".

## Divergence 1 — the original's setup recipe does not work on a fast host

**The most important new result, and it gets worse on better hardware.**

`persist.waydroid.multi_windows=true` does not itself enable multi-window. It is consumed by
`init.waydroid.rc`, which bind-mounts `hidden_xml/pc.xml` over
`/system/etc/permissions/pc.xml` — a file that **ships as a 0-byte placeholder**. Its content
declares `android.hardware.type.pc`, which is what makes Android choose freeform windowing.

Natively, `system_server` reads the placeholder before init performs the mount:

```
I SystemConfig: Reading permissions from /system/etc/permissions/pc.xml
W SystemConfig: Got exception parsing permissions.
W SystemConfig: org.xmlpull.v1.XmlPullParserException: No start tag found
```

No `android.hardware.type.pc` → `mDisplayWindowingMode=fullscreen` → tasks launch fullscreen
→ **only the top task is visible and every other app goes `state=STOPPED stopped=true`**.
Exactly one Wayland toplevel exists at a time; launching a second app makes the first window
vanish. Deterministic over 3 clean boots. Under TCG init won this race comfortably.

The failure mode is not "multi-window is off". It is **other agents' surfaces disappearing
and their activities stopping** — the precise failure Test 1 was written to rule out, reached
by a different route. A faster machine loses this race harder, so adolin is more exposed than
kaladin, not less.

Workaround (a documented Waydroid mechanism, no patching):

```sh
sudo mkdir -p /var/lib/waydroid/overlay/system/etc/permissions
sudo cp /var/lib/waydroid/rootfs/system/etc/hidden_xml/pc.xml \
        /var/lib/waydroid/overlay/system/etc/permissions/pc.xml
```

With it in place the picture matches the original line for line.

**Consequence for the design:** a Waydroid surface manager must *assert* this precondition
rather than assume it — `pm list features | grep android.hardware.type.pc` at startup, and
refuse to bind rather than run degraded. Flag-shaped, default on.

## Divergence 2 — Hazard 2 is worse than "unreliable in both directions"

The freeze is **not window-driven at all**. It is driven by Android's own display sleep
(`IHardware` suspend → `container_manager.freeze`).

- Two toplevels open while `waydroid.active_apps` named exactly one package: it is
  single-valued and overwritten by every `waydroid app launch`, while the window set is
  maintained independently by `hwcomposer.waydroid.so`.
- Killing every toplevel: 6 minutes later still `cgroup.freeze=0`. Clearing `active_apps` by
  hand: 4 more minutes, still no freeze.
- Conversely it froze spontaneously early in the session with **two activities RESUMED** and
  no windows ever created.

So neither window count nor `active_apps` predicts the freeze in either direction. The only
reliable read is `/sys/fs/cgroup/lxc.payload.waydroid/cgroup.freeze`. Hazard 1's operational
trap reproduces exactly: while frozen, `waydroid shell` hangs forever with no diagnostic
(measured: killed at 25s).

## Divergence 3 — new: there is no per-task identity available to the WM layer

The original listed "two windows of the same app" as explicitly not tested. It fell out here:

```
con_id=32 app_id=waydroid.org.lineageos.jelly title='Browser' pid=101844
con_id=33 app_id=waydroid.org.lineageos.jelly title='Browser' pid=101844
con_id=34 app_id=waydroid.com.android.deskclock title='Clock'  pid=101844
```

Two distinct Android tasks, two toplevels, **identical `app_id` and identical title** — and
every Waydroid toplevel reports the **same `pid` across packages**, because 101844 is the
container's single Wayland client (`android.hardware.graphics.composer@2.1-service`).

For `resolve(surface) -> agent`: `app_id` is per-package, title is the app label (confirmed —
a page set `document.title` and the toplevel stayed `'Browser'`), and `pid` is one value for
the whole container. **Per-package binding is what Waydroid can support today. Per-task
binding has nothing to key on from the WM layer** and would need a Waydroid-side channel.

This is the same defect class the repo has been fixing all week — identifying a thing by an
attribute it shares with others — except here the substrate simply does not offer a unique
one, so it is a constraint rather than a bug.

## The Rust binder driver

**Works, and needed nothing** — no DKMS, no AUR, no module build. `rust_binder: Loaded Rust
Binder.`, binderfs mounts, Waydroid's `BINDER_CTL_ADD` ioctl succeeded unmodified. Feature
parity where Waydroid could care (`extended_error`, `freeze_notification`,
`oneway_spam_detection`). Android booted and ran ~55 minutes with three apps and a pegged
WebView; **zero `rust_binder` messages during the experimental window**. All 307 boot-log
messages cluster at container start and match what the C driver logs.

**One real divergence:** `/sys/kernel/debug/binder/` does not exist — the Rust driver does not
expose the C driver's debugfs (`stats`, `transactions`, per-proc state). A lost troubleshooting
surface if binder ever becomes suspect.

**Unprompted security note:** Android's init inside the container attempts
`write /proc/sys/kernel/panic_on_oops 1` on every boot. It is correctly refused
(`Read-only file system`). Given kaladin now runs `kernel.panic=30` and a 60s hardware
watchdog for hang diagnosis, a container that *could* write host sysctls would silently
change host panic behaviour. It cannot here — but it tries, once per boot.

## Caveats

1. **TCG timing — RETIRED.** No virtualisation in this run; the container executes on
   kaladin's own kernel. The result survived a far finer sampling schedule, and the CPU
   measurement that would collapse if `onPause()` ever fired is flat to 0.8%.
2. **Software rendering — NOT RETIRED.** Still `swiftshader`, still no `/dev/dri`, sway on
   `pixman`. The untested hypothesis is unchanged: a **gbm/DRM-backed** Waydroid may do
   visibility-aware buffer management, and back-pressure from a compositor that stopped
   requesting frames could reach SurfaceFlinger and eventually the activity. Going native
   removed the *timing* variable, not the *rendering* one. **Only adolin's real GPU can settle
   this, and it remains the highest-value follow-up.**
3. **The focus result is still an artefact.** `WLR_LIBINPUT_NO_DEVICES=1` again, so the seat
   advertises no keyboard capability and Waydroid never receives `wl_keyboard.enter`. Nothing
   here bears on Open item 2.

Lesser: occlusion was performed by sway while adolin runs GNOME; only Android 13 tested; the
*view hierarchy* was confirmed live, not that a *rendered frame* stays fresh. kaladin was
running another agent's Gradle builds throughout (load 1.4-7.4), which adds noise to
wall-clock figures but cannot confound a comparison flat to 0.8%.

## Reproduction

`/home/jmonk/waydroid-native-probe/` (outside the repo). Two traps worth writing down: the
image download must be done by hand (`waydroid init` timed out against its chosen SourceForge
mirror; plain `curl -L` on the same URL works), and `waydroid shell` uses `lxc-attach`, which
**chowns its stdout to root** if handed a regular file — pipe through `cat`. Gate every attach
on `cgroup.freeze` and wrap it in `timeout`.
