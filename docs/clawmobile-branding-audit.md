# ClawMobile Branding Audit

## Scope

This repository is a fork of `termux-app`, but the shipped product surface is being rebranded to **ClawMobile** with **clawmobile.ae** as the primary support domain.

The branding pass intentionally covers:

- launcher, settings, install flow, and report UI copy
- support, documentation, and contact links
- ClawMobile-specific services and internal action strings that are safe to rename
- store metadata and first-run guidance

The branding pass intentionally does **not** blanket-replace technical identifiers that are still required for the current fork to boot.

## Current Audit Result

Raw search on 2026-03-20:

- search terms: `com.termux`, `Termux`, `termux.dev`, `wiki.termux.com`, `termux.com`, `termux-app`, `termux-api`, `termux-packages`
- current match count: `3307`

This count is still high because most remaining hits are technical identifiers or upstream documentation, not launcher-visible product branding.

## What Was Rebranded

- App-visible names now resolve to `ClawMobile` and `ClawMobile *` companion labels.
- Support/reporting links now point to `https://clawmobile.ae`, `https://clawmobile.ae/docs`, `https://clawmobile.ae/support`, and `support@clawmobile.ae`.
- Launcher/install flow text now uses ClawMobile wording instead of Termux wording.
- The first terminal MOTD now shows ClawMobile-specific instructions.
- Store metadata and lightweight docs pages now reference ClawMobile instead of upstream Termux branding.

## What Intentionally Remains

The following still contain `Termux` or `com.termux` on purpose:

- Java and Kotlin package namespaces under `com.termux.*`
- Android package ID and manifest placeholders based on `com.termux`
- runtime paths such as `/data/data/com.termux/files/usr`
- intent actions, authorities, and shortcut targets derived from the current package ID
- bootstrap payloads and binaries compiled for the current prefix layout
- upstream developer docs, code comments, and historical references that describe the fork origin

These are not safe to mass-rename in the current fork. Changing them would require a controlled package-id migration, bootstrap rebuild, path migration, and companion-plugin compatibility review.

## Files Cleaned In This Pass

- `app/src/main/res/values/strings.xml`
- `termux-shared/src/main/res/values/strings.xml`
- `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`
- `termux-shared/src/main/java/com/termux/shared/termux/TermuxUtils.java`
- `app/src/main/java/com/termux/app/TermuxActivity.java`
- `app/src/main/java/com/termux/app/TermuxInstaller.java`
- `fastlane/metadata/android/en-US/full_description.txt`
- `docs/en/index.md`
- `SECURITY.md`

## Recommended Next Step

Keep the current split:

- **product branding**: ClawMobile
- **technical package/runtime identifiers**: keep `com.termux` until a full package migration is planned

If a full migration is desired later, treat it as a separate project, not as a search-and-replace pass.
