# ClawMobile Runtime Harvest Plan

This document records the new direction for ClawMobile installation:

- Stop treating the install button as a long-lived online installer.
- Treat a manually-proven device runtime as a **harvest source**.
- Convert the stable installed artifacts into versioned payloads that can be extracted on first run.

## Why

The current one-click installer keeps hitting failure modes that are not about ClawMobile logic itself:

- Termux mirror drift
- slow or broken package networking
- `proot` / PTY / `getcwd` edge cases
- Ubuntu bootstrap differences between app-managed execution and real terminal execution

Manual execution inside a real Termux session proved materially more reliable. That suggests the right next step is to reduce online installation work, not keep adding more installer logic.

## What Was Actually Installed

From the current manually-installed device state under `com.termux`, the meaningful artifacts are:

1. Termux-side packages
   - `android-tools`
   - `curl`
   - `git`
   - `openssh`
   - `proot`
   - `proot-distro`
   - `termux-api`

2. Termux-side installed package layer under `files/usr`
   - includes `proot`, `proot-distro`, `adb`, `termux-api`, shared libraries, shell tools, etc.
   - excludes the Ubuntu rootfs itself when harvested as a standalone payload

3. ClawMobile repo in Termux home
   - `/data/user/0/com.termux/files/home/ClawMobile`
   - roughly 8 MB on the observed device

4. Ubuntu rootfs under `proot-distro`
   - `/data/user/0/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`
   - roughly 3.3 GB on the observed device

5. OpenClaw runtime inside Ubuntu
   - `/usr/lib/node_modules/openclaw`
   - `/usr/bin/openclaw`
   - `/usr/bin/node`
   - `/usr/bin/npm`
   - `/usr/bin/adb`
   - observed OpenClaw version: `2026.3.13`

5. Ubuntu-side packages that are already present
   - `curl`
   - `nodejs`
   - `python3`
   - `python3-pip`
   - `python3-venv`
   - `rsync`

Notably, on the observed device:

- the installed rootfs name was `ubuntu`, not `clawmobile-ubuntu`
- `/root/.openclaw` was not present yet
- no `droidrun` files were found in the current runtime tree

## Payload Model

The harvested runtime should be split into layers instead of copied blindly as one giant app-data snapshot.

### 1. Repo Payload

Small, stable, and easy to version.

- `~/ClawMobile`

The default harvested repo payload should exclude `.git`, because the runtime does not need repository history.

This can be bundled directly or as a tarball extracted into Termux home.

### 2. Termux Layer Payload

This is the missing bridge between a fresh Termux install and a working Ubuntu/OpenClaw runtime.

At minimum it should capture the installed `files/usr` layer while excluding:

- `usr/var/lib/proot-distro/installed-rootfs`
- `usr/var/lib/proot-distro/dlcache`
- `usr/var/cache/apt/archives`
- `usr/var/lib/apt/lists`
- `usr/var/log`
- `usr/tmp`

### 3. OpenClaw Runtime Payload

Useful when we want to separate “OpenClaw is installed” from “full Ubuntu rootfs is installed”.

At minimum:

- `ubuntu/usr/bin/openclaw`
- `ubuntu/usr/bin/node`
- `ubuntu/usr/bin/npm`
- `ubuntu/usr/bin/adb`
- `ubuntu/usr/lib/node_modules/openclaw`
- `ubuntu/root/.openclaw` if it exists on the harvested device

### 4. Rootfs Payload

If Ubuntu remains part of the architecture, this is the biggest win. Instead of running `proot-distro install` and Ubuntu bootstrap on-device every time, we ship a pre-harvested rootfs payload.

This payload should exclude obvious transient data:

- bind-mounted Android paths inside the rootfs
  - `apex`
  - `data`
  - `dev`
  - `linkerconfig`
  - `odm`
  - `proc`
  - `product`
  - `run`
  - `sys`
  - `system`
  - `system_ext`
  - `tmp`
  - `vendor`
- cache and log data
  - `var/cache/apt/archives`
  - `var/lib/apt/lists`
  - `var/log`
  - `var/tmp`
  - `root/.cache`

### 5. First-Run Activation

This should remain dynamic:

- permissions
- API keys
- user auth/profile config
- lightweight config injection
- gateway startup

That means the button should eventually become:

1. extract payloads
2. apply permissions / ownership / executable bits
3. write user-specific config
4. launch runtime

Not:

1. install packages from the network
2. bootstrap Ubuntu from scratch
3. patch everything live

## Harvest Tooling

The repository now includes:

- [scripts/clawmobile-harvest-runtime.sh](/Users/ahengljh/Repos/termux-app/scripts/clawmobile-harvest-runtime.sh)
- [scripts/clawmobile-restore-runtime.sh](/Users/ahengljh/Repos/termux-app/scripts/clawmobile-restore-runtime.sh)

Supported commands:

- `inspect`
- `export-repo`
- `export-termux-layer`
- `export-openclaw`
- `export-rootfs`
- `export-all`

Example:

```sh
scripts/clawmobile-harvest-runtime.sh inspect
scripts/clawmobile-harvest-runtime.sh export-all
```

Restore example:

```sh
scripts/clawmobile-restore-runtime.sh apply-all build/clawmobile-runtime/<payload-dir>
```

Outputs are written under `build/clawmobile-runtime/<timestamp>/` by default.

## Next Integration Step

Once we are satisfied with one harvested device image:

1. freeze the exported payload layout
2. decide which payloads live inside the APK and which are downloaded separately
3. replace the install button logic with extraction + activation
4. keep a small repair script only for recovery, not as the main install path
