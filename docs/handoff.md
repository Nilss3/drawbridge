# Handoff — state as of 2026-07-28

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## Where things stand

| | |
|---|---|
| Repo | https://github.com/Nilss3/drawbridge — public, `main`, 15 commits |
| Release | [v0.1.4](https://github.com/Nilss3/drawbridge/releases/tag/v0.1.4), 6 assets. Release URLs use `/releases/latest/`, so the QR and policy survive future releases unchanged. |
| Live policy | version 11, at `dist/policy.signed.json` on `main` |
| Apps | drawbridge `versionCode 5` / `0.1.4`; herald `versionCode 4` / `0.1.4` |
| Tests | 162 unit tests, lint clean |

### Known gaps in the current release

Nothing is stale: v0.1.4 carries everything on `main`, and policy 11 is live.

Three things have still never run:

- **`/releases/latest` must resolve.** Every release URL depends on it. GitHub
  excludes drafts and pre-releases, so flagging the newest release as either
  breaks provisioning and herald's auto-install. v0.1.0 is flagged pre-release,
  which is fine while v0.1.4 is the newest published one.
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
  device testing.

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

Not verified, and worth doing:

- **QR provisioning itself.** The payload decodes correctly and every URL it
  references returns 200, but no device has been provisioned by scanning it. The
  six-tap gesture and the download-and-install flow are untested.
- **Any real hardware.** Everything above is an emulator. OEM battery managers,
  Factory Reset Protection behaviour and the release build's adb cut-off only
  appear on real devices.
- **A live policy update.** The change-detection code was checked for not
  misfiring, but no device has actually received a *new* policy version and
  rebuilt its tunnel in response. v0.1.4 publishes policy 11, so the first
  provisioned device that polls will exercise this — including installing
  herald `versionCode 4` through `required_apps`.
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
   - Localise the app strings to Dutch and French.
   - Consider dropping `x86_64` from future releases (emulator-only, a third of
     the upload) — but the policy references it, so remove it there too.
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
