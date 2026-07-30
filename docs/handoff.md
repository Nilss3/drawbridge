# Handoff — state as of 2026-07-29

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## Where things stand

| | |
|---|---|
| Repo | https://github.com/Nilss3/drawbridge — public, `main`, 22 commits |
| Release | [v0.1.5](https://github.com/Nilss3/drawbridge/releases/tag/v0.1.5), 6 assets, published and not flagged. Release URLs use `/releases/latest/`, so the QR and policy survive future releases unchanged. |
| Live policy | version 13, live at `dist/policy.signed.json` on `main` |
| Apps | drawbridge `versionCode 6` / `0.1.5`; herald `versionCode 5` / `0.1.5` |
| Tests | 200 unit tests, lint clean |

### Known gaps in the current release

Nothing is stale: v0.1.5 carries everything on `main`, and policy 13 is live.
The whole chain was checked against the *published* artefacts rather than the
local ones — the policy fetched from `raw.githubusercontent.com` is version 13
and its signature verifies, the `social.txt` behind it matches its pin and
contains LinkedIn, and the herald APK downloaded from
`/releases/latest/download/` hashes to exactly what `required_apps` names. A
provisioned device would accept and install it.

The tag was pushed and the release published *before* `main`, so
`/releases/latest` already resolved to v0.1.5 when policy 13 went live. The
other order leaves a window in which the policy names APKs that do not exist
yet; harmless, since a hash mismatch is a refused download and a retry, but
avoidable for free.

Build-order note, as designed: herald bundles policy **12** and drawbridge
bundles **13**. `required_apps` pins herald by checksum and herald embeds the
policy, so the two are circular; the documented resolution is that herald ships
one version behind. The only consequence here is that herald's bundled copy
names the pre-LinkedIn `social.txt` hash, which is never used — the policy is
fetched before any blocklist is.

**The APK grew far less than expected**: arm64 went 218 MiB → 222 MiB, not the
~16 MiB uBlock Origin takes unpacked. Its assets are almost all text and
compress into the APK.

### herald mono, unreleased on `main`

A second edition of the browser for single-tasking: no tabs, everything in black
and white, articles opened in reader view, and a deliberate two-and-a-half-second
pause before a page appears. Same filtering, same uBlock Origin, same bookmarks
and history — it is a Gradle product flavour of `herald`, package
`app.drawbridge.heraldmono`.

Not in any release, and **not yet installable on a managed device**: the policy's
`allowed_browser_package` names `app.drawbridge.herald`, so drawbridge would
remove mono as a rogue browser within seconds of it appearing. Running it on a
managed phone means a policy that names mono instead — the two are alternatives,
never companions.

Before it ships, three things need deciding or doing:

- **Whether to publish it at all**, and if so whether every release carries both
  editions. A release goes from 3 herald APKs to 6, roughly 650 MiB to 1.1 GiB.
  GitHub imposes no limit that matters — the only hard one is 2 GiB per file,
  and the largest asset is 242 MiB — so this is upload time, not quota.
- **`required_apps` entries for mono**, if a device is ever to auto-install it.
- **TextureView performance on real hardware.** Mono renders through a
  TextureView rather than the default SurfaceView, which copies a frame more.
  It looked fine on the emulator, which proves nothing about frame rates.

### herald changes in v0.1.5

- **Reader view actually works.** `ReaderViewMiddleware` was missing from the
  store, which is the only thing that sets the flags `ReaderViewFeature` needs
  to register its content-script ports and to re-check readability after a
  navigation. See design-decisions; it fails silently, so it is worth knowing.
- **uBlock Origin is bundled** and installed as a built-in extension, with its
  popup on the toolbar and its dashboard in the menu.
- **Bookmarks** got folders, search, editing, and import/export of a
  `bookmarks.html` file.
- **History and saved passwords** got a search field.
- **A bookmarks new tab page**, off by default, behind a setting.
- **LinkedIn** joins the social list (`linkedin.com`, `licdn.com`, `lnkd.in`),
  and policy 13 carries the new list hash.

`/releases/latest` was checked this time and resolves to v0.1.5. Keep it that
way: GitHub excludes drafts and pre-releases, so flagging the newest release as
either breaks provisioning and herald's auto-install. v0.1.0 is flagged
pre-release, which is fine while a newer one is published.

Two things have still never run:

- **The self-update path.** `app_update` is unset, so `checkAndInstallSelf` has
  nothing to do. Setting it is circular — drawbridge's own APK contains the
  policy that would name its hash — so it takes two rounds: publish the APK,
  then publish a policy naming it.
- **QR provisioning on a real device.** See below.

---

## Secrets, and where they are

Neither is in git. Both are on the build machine only, and **neither is backed
up**. This is the single largest risk in the project.

| What | Where | Consequence of losing it |
|---|---|---|
| Release signing keystore | `keys/drawbridge-release.jks`, password in `keystore.properties` | Every provisioned device is stranded on its installed version forever. Android refuses updates signed with a different key. |
| Policy signing key | `keys/drawbridge-2026-07.pem` | No device can ever be given a new policy again without reinstalling both apps. |

Both directories are git-ignored. Copy them somewhere offline **before**
provisioning any phone you care about — after that point neither can be
replaced.

The QR code pins the *release certificate*, so it stays valid across every
future release signed with that keystore. Change the keystore and every
provisioned device is orphaned and the QR must be regenerated.

---

## Build machine setup

Installed during this work, none of it in the repo:

```
brew install openjdk@21              # AGP needs 17–21; the system JDK is 24
brew install qrencode zbar           # QR generation and decode-verification
brew install gh                      # release upload; `gh auth login` done as Nilss3
brew install --cask android-commandlinetools
```

- **JDK selection is automatic.** `gradle/gradle-daemon-jvm.properties` pins
  toolchain 21 and Gradle finds the Homebrew JDK. Plain `./gradlew` works; no
  `JAVA_HOME` needed.
- **The NDK is installed but useless here.** It went to
  `/opt/homebrew/share/android-commandlinetools/ndk/` with a symlink into
  `~/Library/Android/sdk/ndk/`. It was installed to strip GeckoView's
  `libxul.so`; measurement showed the library is already stripped and nothing
  changed. Safe to delete. See design-decisions.md.
- **Emulator** `Medium_Phone_API_36.0` (arm64, Play Store image) was used for all
  device testing. It is the *provisioned* one: drawbridge is device owner on it
  and the installed herald is a release build, so a debug herald cannot be
  installed over it.
- **A second AVD, `herald_test`**, was added for browser work: same system image,
  4 cores and 4 GB rather than 1 and 2 GB, no device owner. Created by hand in
  `~/.android/avd/` because the Homebrew `avdmanager` has its own SDK root and
  cannot see the system images under `~/Library/Android/sdk`. Delete it if the
  disk is wanted back; nothing depends on it.
- **Gradle may not find JDK 21 from a non-interactive shell.** The toolchain is
  pinned to 21 and the only JDK on the `PATH` is 24, so
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  may be needed.

---

## What was verified on a device, and what was not

Verified on the API 36 emulator, end to end:

- Provisioning via `dpm set-device-owner`; all restrictions applied
- DNS blocking (NXDOMAIN), safe-search rewriting, DoT forwarding to Mullvad with
  zero fallbacks across 20 lookups
- Browser allowlisting — Chrome hidden, a rogue browser build uninstalled
- herald: normal pages load, blocked pages show the block page
- PIN setup, recovery-code display, wrong-PIN rejection, removal without data
  loss (device owner cleared, Chrome un-hidden, filter stopped)
- **The full auto-install path**: drawbridge fetched policy v3 from GitHub,
  downloaded the 217 MB signed herald release, verified its SHA-256 and
  installed it silently in ~60 seconds

Verified on the `herald_test` emulator (2026-07-29), for the browser work:

- **Reader view on the reported page** — the De Morgen article that Firefox
  offers reader mode for and herald did not. Past the consent gate, the menu
  offers it and it renders. Not offered on `about:blank`, as it should not be.
- **uBlock Origin** installs (`Installed uBlock Origin (1.72.2)`), shows its
  button and blocked-request counter on the toolbar, its popup opens as a sheet
  and its dashboard opens as a tab.
- **Bookmarks** — folder creation, moving a bookmark between folders, folder
  navigation and back, search, and "Add bookmark" turning into "Edit bookmark".
- **Export** wrote a valid `bookmarks.html` with the folder nesting intact;
  **import** of a Firefox-shaped file recreated the tree, rejected the
  `javascript:` and `file:` entries, and landed in its own dated folder.
- **New tab page** off by default, showing the bookmark root when switched on,
  with folders opening in place and back stepping out of them.
- **History search** finds a page by title.

Verified on the `herald_test` emulator for herald mono:

- **Greyscale** on a page and on playing video, with the chrome drained too;
  "show this page in colour" restores it and navigating away takes it back.
- **No tabs**: the counter and tray are gone, `target="_blank"` and both forms
  of `window.open` load in the current page with working back history, and the
  persisted session holds exactly one tab afterwards.
- **The pause**, naming the destination while it holds, and **reader view
  entering itself** on a readerable page — including that turning it off stays
  off, and that entering it does not trigger a second pause.
- **The standard edition is unchanged** — colour, tab counter, no pause — which
  is the regression that mattered most.

Not verified, and worth doing:

- **herald mono on real hardware**, especially whether the TextureView backend
  costs frame rate on a real phone. Everything above is an emulator, where
  rendering performance means nothing.
- **uBlock Origin on a managed device.** It was only exercised on the unmanaged
  test emulator, where nothing filters DNS. Its update hosts were checked
  against the live blocklists and none were blocked, and policy 12 allowlists
  them so an upstream list cannot start blocking them later — but no managed
  device has actually been watched updating a filter list.
- **The APK size increase in practice.** Each ABI split grew by roughly 16 MB.
  The auto-install path downloads the whole APK, and has only ever been timed at
  the old size.

- **QR provisioning itself.** The payload decodes correctly and every URL it
  references returns 200, but no device has been provisioned by scanning it. The
  six-tap gesture and the download-and-install flow are untested.
- **Any real hardware.** Everything above is an emulator. OEM battery managers,
  Factory Reset Protection behaviour and the release build's adb cut-off only
  appear on real devices.
- **A live policy update.** The change-detection code was checked for not
  misfiring, but no device has actually received a *new* policy version and
  rebuilt its tunnel in response. Policy 13 is live, so the first provisioned
  device that polls will exercise this — including installing herald
  `versionCode 5` through `required_apps`.
- **The self-update path.** `app_update` is unset in the policy, so
  `checkAndInstallSelf` has never had anything to do.

---

## Traps that cost time here

Each of these looks like a bug and is not, or bites silently:

- **Release builds kill adb.** `DISALLOW_DEBUGGING_FEATURES` switches off USB
  debugging the instant it applies, so the connection drops right after
  provisioning succeeds. Debug builds skip that one restriction
  (`BuildConfig.RETAIN_ADB_ACCESS`). Install everything *before* provisioning.
- **Neither app may take an `applicationIdSuffix`.** A `.debug` herald is
  uninstalled by drawbridge's own app blocker seconds after provisioning,
  because a browser whose package is not the allowed one is exactly what that
  code removes.
- **herald debug and release cannot replace one another** — different signing
  keys. Uninstall first.
- **The app blocker hides Chrome**, so hidden packages vanish from
  `pm list packages`; use `pm list packages -a` to see them.
- **Testing removal wipes the PIN**, so the next provisioning cycle starts from
  setup again.
- **aapt drops asset directories starting with an underscore.** Its default
  ignore list contains `<dir>_*`. This cost an afternoon when uBlock Origin's
  `_locales/` vanished from the APK and Gecko reported only
  `Extension is invalid`. `herald/build.gradle.kts` overrides the pattern list;
  do not "tidy" it away.

---

## Decisions still open

Carried over from the original design notes and never answered:

1. ~~Which games to block.~~ **Answered 2026-07-28** — the owner's list is in
   policy v4 (41 packages). Four items could not be blocked exactly as
   specified; see [blocklist-notes.md](blocklist-notes.md).
2. **Whether the current blocklists are right.** Mullvad's `all` resolver blocks
   a great deal on its own — including all social media — so some of the curated
   lists may now be redundant, and some categories may be blocked more
   aggressively than intended.
3. **App localisation.** The UI is English-only. The Dutch and French install
   guides quote English button labels with translations alongside as a
   stopgap.

---

## Reasonable next steps

1. **Provision a real phone by QR** — the one major untested path, and the whole
   point of the release.
2. **Back up both keys.**
3. Then, in rough order of value:
   - Decide whether the APK download path deserves the same allowlist treatment
     policy 12 gave the list-update paths. `required_apps` points at
     `github.com`, which redirects to a `*.githubusercontent.com` asset host
     that has not been pinned down; a blocked redirect target would stop
     auto-install silently, the same way a blocked list URL would have stopped
     filter updates.
   - Localise the app strings to Dutch and French. There are ~40 new strings
     from the bookmark work.
   - Consider dropping `x86_64` from future releases (emulator-only, a third of
     the upload) — but the policy references it, so remove it there too. It is
     worth more now that each split is ~16 MB larger.
   - Revisit whether `armeabi-v7a` is worth keeping.

---

## Working notes for whoever picks this up

- `./gradlew test lint` is the fast check; both apps assemble in under a minute
  warm.
- `python3 tools/policytool.py sign --key-id drawbridge-2026-07` then `verify`
  after any `dist/policy.json` edit, and copy the result over
  `policy/src/main/assets/drawbridge/default-policy.json`.
- The emulator needs `-wipe-data` to test provisioning again, since device owner
  cannot be granted twice.
- Watch the filter with
  `adb logcat -d | grep -F -e DnsFilterService -e AppInstaller -e EncryptedDns`.
