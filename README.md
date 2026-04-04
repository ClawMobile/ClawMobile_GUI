# ClawMobile GUI

ClawMobile GUI is an Android app that packages a ClawMobile runtime inside a customized Termux-based shell app.

This repository is for the Android application itself:
- the launcher UI
- the terminal UI
- the in-app setup flow
- the bundled `~/ClawMobile` runtime that is deployed onto the phone

If you are new here, the main thing to know is:

**You install the APK, open the app, let it deploy `~/ClawMobile`, then complete setup from inside the app.**

---

## What this app does

ClawMobile turns the phone into its own agent runtime:
- Termux provides the local shell environment
- `proot-distro` provides Ubuntu inside the phone
- OpenClaw runs inside that Ubuntu environment
- DroidRun and the mobile plugin provide Android automation
- the launcher UI guides install, pairing, onboarding, health checks, and daily use

The app still uses the Android package name `com.termux` internally. That is intentional. The bundled bootstrap and native binaries depend on the original Termux prefix layout, so changing the package id would break core runtime paths.

---

## Who this repo is for

This repository is useful if you want to:
- build the ClawMobile APK yourself
- test the latest in-app onboarding flow
- modify the launcher, terminal, or setup UX
- update the bundled ClawMobile runtime that gets copied to `~/ClawMobile`

If you only want to understand the runtime that runs inside the phone after deployment, also look at:
- [app/src/main/assets/clawmobile_repo/README.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/README.md)
- [app/src/main/assets/clawmobile_repo/installer/INSTALL.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/INSTALL.md)
- [app/src/main/assets/clawmobile_repo/installer/FAQ.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/FAQ.md)

---

## Beginner Quick Start

### 1. Install the APK

Build or download the APK, then install it on an Android device.

For local development, the debug APK is typically:
- [app/build/outputs/apk/debug/clawmobile_apt-android-7-debug_universal.apk](/Users/ahengljh/Repos/termux-app/app/build/outputs/apk/debug/clawmobile_apt-android-7-debug_universal.apk)

Install with:

```sh
adb install -r app/build/outputs/apk/debug/clawmobile_apt-android-7-debug_universal.apk
```

### 2. Open the app

On first launch, the app prepares the bundled runtime and deploys the bundled repository into:

```sh
~/ClawMobile
```

You do not need to clone the repo manually for the normal app flow.

### 3. Tap `Deploy ClawMobile`

This runs the bundled installer:

```sh
cd ~/ClawMobile
./installer/termux/install.sh
```

That step is responsible for:
- updating Termux packages
- installing prerequisites
- installing the ClawMobile Ubuntu runtime
- entering Ubuntu and running the bootstrap

### 4. Pair local wireless ADB

Use the `Setup` tab in the app:
- `Step 1 · Approve pairing code`
- `Step 2 · Connect local device`

If local ADB is not connected, DroidRun-dependent steps will not be healthy yet.

### 5. Finish DroidRun setup

After wireless ADB is connected, run:

```sh
cd ~/ClawMobile
./installer/termux/droidrun-setup.sh
```

This installs or reconfigures DroidRun Portal and verifies that DroidRun can see the device.

### 6. Configure OpenClaw

Use the `Channels` tab in the app, or run:

```sh
cd ~/ClawMobile
./installer/termux/onboard.sh
```

### 7. Start the runtime

Use the launcher button when it changes to `Start OpenClaw`, or run:

```sh
cd ~/ClawMobile
./installer/termux/run.sh
```

---

## Daily Use

Once setup is complete, the normal flow is much simpler:

1. Open the app
2. Check the `Health` tab
3. Confirm:
   - runtime is installed
   - ADB is connected
   - setup is complete
4. Tap `Start OpenClaw`

The `Operate` tab is the day-to-day surface.

The `Health` tab is the diagnostic surface. It is designed more like a VPN status page:
- green means healthy
- yellow means partial or waiting
- red means blocked

---

## Manual Shell Commands

If you want to do everything manually inside the terminal, the main commands are:

```sh
cd ~/ClawMobile
./installer/termux/install.sh
./installer/termux/droidrun-setup.sh
./installer/termux/onboard.sh
./installer/termux/run.sh
```

Useful supporting commands:

```sh
adb devices
proot-distro list
```

---

## Wireless ADB Notes

Local wireless ADB on the same phone uses two distinct steps:

1. Pair
```sh
adb pair 127.0.0.1:<PAIRING_PORT> <PAIRING_CODE>
```

2. Connect
```sh
adb connect 127.0.0.1:<CONNECT_PORT>
```

The app UI separates these two actions on purpose. Pairing and connecting are not the same operation.

---

## Troubleshooting

### Ubuntu install exits with failure after locale generation

This project now uses a custom `clawmobile-ubuntu` proot plugin to avoid the upstream `dpkg-reconfigure locales` failure path seen on some Android devices.

If Ubuntu still fails to install, check:
- the live shell tail in the launcher
- the actual terminal output
- whether `proot-distro` is installed

### DroidRun works poorly or fails during install

The base installer no longer forces full DroidRun setup during Ubuntu bootstrap.

That setup is intentionally split:
- `install.sh` installs the base runtime
- `droidrun-setup.sh` should be run after local wireless ADB is connected

### The launcher looks ready but a shell script still behaves strangely

Check that the bundled repo actually refreshed:

```sh
cat ~/ClawMobile/__bundle_version.txt
```

If you need a clean redeploy:

```sh
rm -rf ~/ClawMobile
```

Then reopen the app and let it deploy again.

---

## Building From Source

### Requirements

- macOS or Linux development machine
- Android SDK / platform tools
- Java from Android Studio or another compatible JDK
- `adb`

### Build

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --console=plain assembleDebug
```

### Install

```sh
adb install -r app/build/outputs/apk/debug/clawmobile_apt-android-7-debug_universal.apk
```

---

## Repository Layout

### Android app

- [app/](/Users/ahengljh/Repos/termux-app/app)
- [termux-shared/](/Users/ahengljh/Repos/termux-app/termux-shared)
- [terminal-emulator/](/Users/ahengljh/Repos/termux-app/terminal-emulator)
- [terminal-view/](/Users/ahengljh/Repos/termux-app/terminal-view)

### Bundled ClawMobile runtime

- [app/src/main/assets/clawmobile_repo/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo)

Important subdirectories:
- [app/src/main/assets/clawmobile_repo/installer/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer)
- [app/src/main/assets/clawmobile_repo/installer/termux/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux)
- [app/src/main/assets/clawmobile_repo/installer/ubuntu/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/ubuntu)
- [app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui)

---

## Current Product Shape

Today the app is organized around four launcher tabs:
- `Setup`
- `Channels`
- `Operate`
- `Health`

And one real terminal underneath them for advanced use and debugging.

The design goal is:
- keep the power of the real terminal
- avoid dropping beginners straight into a naked shell
- expose the most important setup and health operations in a guided UI

---

## Important Constraints

- The installed Android package must remain `com.termux`
- The runtime depends on Termux bootstrap layout and native binaries
- Wireless ADB is required for full DroidRun/local-device control
- Ubuntu install can be sensitive to upstream `proot-distro` plugin changes
- The launcher UI may guide setup, but the underlying shell is still real

---

## Where to Start Next

If you are a new contributor, the highest-signal files to read first are:
- [app/src/main/java/com/termux/app/TermuxActivity.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/TermuxActivity.java)
- [app/src/main/res/layout/view_launcher_overlay.xml](/Users/ahengljh/Repos/termux-app/app/src/main/res/layout/view_launcher_overlay.xml)
- [app/src/main/assets/clawmobile_repo/installer/termux/install.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/install.sh)
- [app/src/main/assets/clawmobile_repo/installer/termux/droidrun-setup.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/droidrun-setup.sh)
- [app/src/main/assets/clawmobile_repo/installer/termux/run.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/run.sh)

---

## Related Docs

- [app/src/main/assets/clawmobile_repo/README.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/README.md)
- [app/src/main/assets/clawmobile_repo/installer/INSTALL.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/INSTALL.md)
- [app/src/main/assets/clawmobile_repo/installer/FAQ.md](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/FAQ.md)
- [docs/clawmobile-runtime-plan.md](/Users/ahengljh/Repos/termux-app/docs/clawmobile-runtime-plan.md)
- [docs/clawmobile-runtime-progress.md](/Users/ahengljh/Repos/termux-app/docs/clawmobile-runtime-progress.md)
