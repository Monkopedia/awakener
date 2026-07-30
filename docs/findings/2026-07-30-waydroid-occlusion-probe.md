# Probe: Waydroid freeform occlusion lifecycle (Test 1)

**Date:** 2026-07-30 · **host:** kaladin (headless, qemu 11.0.2, **TCG — no KVM**) · **guest:**
Ubuntu 24.04.4, kernel 6.8.0-136 · **Waydroid:** 1.6.2 · **Android:** 13 / SDK 33, LineageOS
`20.0-20260403-VANILLA-waydroid_x86_64` · **sway (guest):** 1.9 · **status:** answered; the
Waydroid plan survives, with four hazards and one fidelity caveat worth re-checking

This is Test 1 from the design brief's working agreements, and it gated the whole Waydroid
substrate: if occluded apps get paused, their view hierarchy goes stale and background work
dies, which would have killed Waydroid as the answer to the long tail of apps.

## The answer

**An occluded-but-open Waydroid window stays RESUMED.** Occlusion by the host compositor is
not merely tolerated — it is never observed by the guest at all.

**Why, and why it is unlikely to regress.** In `multi_windows` mode Waydroid lays every task
out on one 1920x1080 Android virtual display in freeform mode, and the host compositor picks
each task's surface out separately. Android's WM believes every task is on-screen and
non-obscured (`mObscured=false` even when fully covered). There is no Wayland event meaning
"you are occluded", and Waydroid forwards nothing of the kind. Android *cannot* know. That is
the same property that makes `app_id`-based `resolve()` work, so the two stand or fall
together rather than being independent bets.

## Evidence

Three occlusion mechanisms, all negative:

- **sway `tabbed`** — the strongest case, since sway unmaps the non-visible tab's surface.
  Host reported `vis=False`; Android reported `state=RESUMED stopped=false`,
  `mObscured=false mHasSurface=true mDrawState=HAS_DRAWN`, procState unchanged.
- **Floating geometric overlap** — one window entirely inside another, both mapped.
  `mResumed=true mStopped=false`.
- **Workspace switched away** — every toplevel off any visible output. Still `visible=true`,
  `state=RESUMED`.

Sustained 10 minutes under the tabbed case, sampled every 2 minutes: no delayed stop.

The decisive artefact is `dumpsys activity top`, which reports the **app process's own**
`ActivityThread` state rather than system_server's bookkeeping:

```
ACTIVITY com.android.settings/.Settings pid=960      <- host-occluded at this moment
    mResumed=true mStopped=false mFinished=false
    View Hierarchy: mCreated=true mResumed=true mStopped=false
```

The view hierarchy stays live — precisely the thing the brief worried would go stale.

**Corroboration by CPU, not just by bookkeeping.** A JS busy loop in LineageOS Jelly, ticks
per 90 s: visible/focused 14195, **fully occluded 23323**, visible again 23899, all windows
off-screen 19423. Occluded is indistinguishable from visible. Had Android paused the activity,
`onPause()` → `WebView.onPause()` stops JS timers and this collapses to ~0.

## Confirmed: N windows for N agents

Each Android task is its own Wayland toplevel, with `app_id` = `waydroid.<package>`:

```
8 waydroid.com.android.settings  'Settings'  960x1080
9 waydroid.com.android.deskclock 'Clock'     960x1080
```

A ready-made key for `resolve()`, no marks needed. The brief's "Waydroid gives N windows for
N agents" holds literally.

## Hazards

**1 — the container freezes when nothing is open.** `waydroid.cfg` ships
`suspend_action = freeze`, which freezes all of Android via the cgroup freezer once no app is
active (`cgroup.freeze = 1`, 95 procs). Not occlusion-driven, but it is the coarse version of
the same failure: **when the last Lifeless window closes, every Waydroid agent's background
work stops.** Also an operational trap — while frozen, `waydroid shell` hangs forever with no
diagnostic. `suspend_action = none` disables it, at a real battery/CPU cost. Per the
flags-first rule this is a flag, not a constant.

**2 — the freeze governor goes stale.** After killing all toplevels compositor-side,
`waydroid.active_apps` still named a package with zero windows open, and the container never
re-froze. It fails safe here but is unreliable in both directions; do not use it as a liveness
signal.

**3 — closing the host window destroys the Android task.** `swaymsg kill` on a toplevel
finished the task outright; the process fell to `cch-empty` and vanished from `dumpsys`.
Correct and the right default, but it means a Lifeless's surface is gone the moment the user
closes the window, with no Android-side survivor. Perishable state must be distilled on the
`close` event — the same trigger the sway probe identified for dock teardown.

**4 — the toplevel title is the app label, not the document.** Titles stayed `'Settings'`,
`'Browser'` even after the page set `document.title`. So title cannot distinguish two surfaces
of the same app, and `app_id` is per-package. **Not tested: two windows of the same Android
app.** Per-task binding rather than per-package needs its own probe.

## Fidelity — what to doubt

1. **No KVM.** `/dev/kvm` does not exist on kaladin — the kernel reports
   `SVM disabled (by BIOS) in MSR_VM_CR`, so this is a firmware setting, not a package.
   Everything ran under TCG, ~10-20x slower than native. This affects *timing*, and timing is
   what a lifecycle-timeout bug would hide behind. A wall-clock stop would have fired inside a
   10-minute sample; one gated on frame counts or ANR deadlines might not. Low but non-zero.
2. **Software rendering** (`ro.hardware.egl=swiftshader`, no `/dev/dri`). Activity lifecycle is
   framework code and should not care, but what cannot be ruled out is that a **gbm/DRM-backed**
   Waydroid does buffer management that is aware of host surface visibility — releasing or
   refusing buffers for surfaces the compositor stopped requesting frames for — and that such
   back-pressure reaches SurfaceFlinger and eventually the activity. **This is the part most
   worth re-confirming on adolin against a real GPU.**
3. **The focus result is an artefact — do not use it.** sway focus changes never reached
   Android, but the headless sway advertised `seat0 capabilities=0, 0 devices`, so no
   `wl_keyboard` capability existed and Waydroid never received `wl_keyboard.enter`. This says
   nothing about Open item 2 either way.

Lesser: occlusion was performed by sway, but adolin runs GNOME; only Android 13 was tested;
and the *view hierarchy* was confirmed live, not that a *rendered frame* stays fresh — a
pixel-based reader could still get a stale buffer, which the read-on-demand accessibility
approach sidesteps.

## What it means for the plan

The real lifecycle cliff is not occlusion. It is **window close** (Hazard 3) and
**all-windows-closed** (Hazard 1). Both are sharp, both are observable, and both belong to
`attach()`/detach discipline rather than to the compositor abstraction.

## Reproduction

`/home/jmonk/waydroid-probe/` (5.4 GB, outside the repo): `run-vm.sh` brings the VM back up
with guest state persisted in `disk.qcow2`; `g` is an ssh wrapper into the guest; `snap2.sh`
and `cpu2.sh` are the lifecycle snapshot and `/proc` CPU sampler.

Getting binder needed no custom kernel — it is `=m` in Ubuntu's stock config but ships in
`linux-modules-extra`:

```sh
sudo apt-get install -y linux-modules-extra-$(uname -r)
sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
curl -s https://repo.waydro.id | sudo bash && sudo apt-get install -y waydroid sway foot
sudo waydroid init -s VANILLA
echo "persist.waydroid.multi_windows=true" | sudo tee -a /var/lib/waydroid/waydroid_base.prop
```
