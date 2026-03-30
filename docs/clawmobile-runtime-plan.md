# ClawMobile Runtime Plan

Last updated: 2026-03-20

This document is the current execution plan, not a historical log. It answers four questions:

1. What architecture are we actually shipping now?
2. What is already verified on device?
3. What is still missing?
4. What should be built next?

## 1. Current Goal

The target product shape is now:

1. The APK contains the app, the bundled `ClawMobile` repo assets, the launcher UX, and the restore/start logic.
2. On first run, the app restores a prebuilt runtime instead of performing a long online install.
3. After restore, the primary button starts Ubuntu and OpenClaw.
4. Mobile control is provided by the OpenClaw mobile plugin over `adb + uiautomator`, not by a separately-installed Python Droidrun runtime.

This is a different goal from the original "one-click online installer". We are now converging on "payload restore + runtime start".

## 2. What The APK Contains Today

The APK already contains:

- the app code and launcher UI
- the bundled `ClawMobile` repo under `app/src/main/assets/clawmobile_repo`
- the restore logic in [TermuxActivity.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/TermuxActivity.java)
- the runtime start script in [run.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/run.sh)
- the OpenClaw mobile plugin source/bundle inside the bundled repo

The APK does **not** currently contain the heavy runtime payloads:

- `termux-prefix-layer.tar`
- `ubuntu-rootfs-harvested.tar`

That is visible from size alone:

- current debug APK: about `132M`
- harvested Termux layer payload: about `155M`
- harvested Ubuntu rootfs payload: about `2.7G`

So the current product shape is:

- **APK-embedded logic and repo**
- **externally or internally staged runtime payloads**

It is **not** yet an APK-only distribution.

## 3. Current Runtime Architecture

### 3.1 Restore Path

The install button now does this:

1. Look for staged payloads.
2. If found, restore them locally.
3. If not found, fall back to the old terminal-based installer.

This logic is in [TermuxActivity.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/TermuxActivity.java#L700).

The restore path currently restores:

1. Termux runtime layer into `files/`
2. bundled `ClawMobile` repo into `~/ClawMobile`
3. Ubuntu rootfs into `files/usr/var/lib/proot-distro/installed-rootfs`
4. activation paths like `tmp`, `.ssh`, and `ubuntu/root/ClawMobile`

This logic is in [TermuxActivity.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/TermuxActivity.java#L967).

### 3.2 Start Path

After runtime restore, the intended steady-state start path is:

1. enter Ubuntu with `proot-distro`
2. load plugin/runtime environment
3. start `openclaw gateway`

This is implemented in [run.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/run.sh).

### 3.3 Mobile Control Path

The active mobile control stack is now:

1. OpenClaw gateway
2. mobile plugin
3. native in-repo agent/backend
4. `adb` and `uiautomator`

Important clarification:

- file and symbol names still say `droidrun` in several places
- but the active backend is now the native in-repo implementation in [droidrun.ts](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui/src/backends/droidrun.ts)
- that backend uses `NativeAndroidAgent`, `collectUiState`, and `adb_*`
- it explicitly reports `droidrun_required: false`

So:

- **Python Droidrun package**: not required on the active path
- **ADB visibility inside Ubuntu**: still required

## 4. What Is Verified Right Now

The following points are already verified on a real device:

### 4.1 Payload Restore From The Launcher

The launcher button successfully restored the runtime from staged payloads and completed with:

- UI status: `Installation complete!`
- progress text: `Runtime restored from payload.`

The fixes that made this reliable were:

- stage payloads into app-private storage instead of relying on external storage reads
- clear old Ubuntu rootfs before re-extracting
- use system `rm -rf` for rootfs cleanup instead of Java recursive delete

### 4.2 Ubuntu Entry Works

The restored Ubuntu runtime can be entered with `proot-distro login ubuntu`.

### 4.3 OpenClaw Exists And Runs In Ubuntu

Inside Ubuntu, the following has been verified:

- `command -v openclaw` -> `/usr/bin/openclaw`
- `openclaw --version` -> `OpenClaw 2026.3.13 (61d171a)`

That means the restored runtime is not just unpacked; the OpenClaw binary is actually runnable.

### 4.4 The Active Plugin Path Is Not Python Droidrun

This is confirmed by code:

- [bootstrap.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/ubuntu/bootstrap.sh#L126) says Ubuntu bootstrap installs OpenClaw and `adb`, and the mobile agent runs over `adb + uiautomator`
- [droidrun.ts](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui/src/backends/droidrun.ts) uses the native agent/backend

## 5. What Is Not Finished Yet

### 5.1 Payloads Are Not Embedded In The APK

The current restore flow still depends on the payload tar files being staged into the device first.

That means first-run offline restore is proven, but packaging is not solved yet.

### 5.2 Product UX Still Reflects "Install"

The current launcher button still says `CLAWMOBILE INSTALL START`.

That is no longer the right long-term UX once restore is automatic or near-automatic.

We should move toward:

- first run: auto-restore or `PREPARE RUNTIME`
- steady state: `START CLAWMOBILE`

### 5.3 Start Flow Still Needs Final UX Wiring

We have validated:

- Ubuntu can be entered
- `openclaw` exists and runs

But the app UX should move from:

- button = install/restore

to:

- first run = restore
- later button = start Ubuntu + OpenClaw

### 5.4 ADB Visibility Inside Ubuntu Remains A Real Requirement

Even though Python Droidrun is no longer required, the plugin still needs:

- `adb` inside Ubuntu
- device visibility from that `adb`

That is the transport OpenClaw uses to control the phone today.

## 6. Current Packaging Reality

Right now there are three distinct layers:

### 6.1 Bundled In APK

- app code
- launcher assets
- bundled repo
- restore/start logic

### 6.2 Staged Payloads

- `termux-prefix-layer.tar`
- `ubuntu-rootfs-harvested.tar`

These are currently staged into:

- app-private path `~/.clawmobile/payload`

### 6.3 Activated Runtime On Device

After restore, the actual runtime lives under:

- `files/usr/...`
- `files/home/ClawMobile`
- `files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`

## 7. Recommended Product Flow

The recommended app behavior from this point forward is:

### 7.1 First App Open

1. Detect whether a valid runtime marker exists.
2. If not, restore the staged payload automatically.
3. Run activation.
4. Mark the runtime version as installed.

### 7.2 Main Button After Restore

The primary button should no longer perform installation.

It should:

1. enter Ubuntu
2. load environment
3. start `openclaw gateway`

### 7.3 Repair / Recovery

Keep a secondary repair path for:

- missing payloads
- runtime mismatch
- corrupted rootfs

But do not keep the old full online install as the primary user path.

## 8. Next Steps

The next implementation steps should be:

1. Add a runtime-installed marker and version check.
2. Trigger payload restore automatically on first launch when the marker is missing.
3. Rename the primary button/state machine from "install" to "start" once restore is complete.
4. Wire the primary button to [run.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/run.sh) in the restored state.
5. Verify end-to-end startup from the button:
   - enter Ubuntu
   - start OpenClaw gateway
   - confirm plugin is available
6. Decide how payloads are delivered:
   - bundled
   - downloaded
   - side-loaded
7. Compress the large payloads, especially the Ubuntu rootfs, before solving final distribution.

## 9. Strategic Decision

The project should now treat these as separate concerns:

- **runtime packaging**
- **runtime restore**
- **runtime startup**
- **phone control transport**

That separation is important because we have now proven:

- restore works
- Ubuntu works
- OpenClaw works
- the old online install path is no longer the right primary product

The remaining work is no longer "make the installer less fragile". It is:

- make runtime delivery product-quality
- make startup product-quality
- keep ADB-based control reliable inside Ubuntu
