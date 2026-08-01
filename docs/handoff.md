# Handoff — state as of 2026-08-01

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## Where things stand

| | |
|---|---|
| Repo | https://github.com/Nilss3/drawbridge — public, `main`, 41 commits |
| Latest published release | [v0.1.6](https://github.com/Nilss3/drawbridge/releases/tag/v0.1.6), 9 assets |
| Live policy | version **14** — what devices actually fetch |
| **v0.1.7** | **built, signed and staged in `dist/release/`. Not committed, not pushed, not published.** |
| Policy in the tree | version **19**, signed, bundled, pinning the 0.1.7 browsers |
| Apps | drawbridge `versionCode 8` / `0.1.7`; herald and herald mono `versionCode 7` / `0.1.7` |
| Tests | 282 unit tests across four build variants, lint clean |

### v0.1.7 is cut but not out

Everything up to the last command of
[the release procedure](policy.md#releases-are-cut-locally-not-in-ci) has been
done, in order: both browsers built, staged under their published names, hashed
into `required_apps`, the policy signed as 19 and copied into the bundled asset,
then drawbridge built carrying it. `tools/stage-release.sh` reports every pinned
APK present under the right name with a matching hash. All nine artefacts are
signed with the release certificate `f662a801…`, which is the one the
provisioning QR pins, so the existing QR stays valid.

Three commands remain, and all three are outward-facing:

```bash
git add -A && git commit          # the source, the policy and dist/release/SHA256SUMS
git push                          # publishes the policy and the blocklists
gh release create v0.1.7 dist/release/*.apk dist/release/SHA256SUMS \
    dist/release/provisioning-qr.json
```

**Order matters between the last two.** `required_apps` in the pushed policy
names APKs under `/releases/latest/download/`; if the policy goes live before the
release exists, every device fetches a 404 until it does. Push and publish
together, and do not leave the release a draft or a pre-release —
`/releases/latest` skips both.

### The blocklist and the policy have to move together

Policy 19 re-pins `ai-companions.txt` because Grok was added to it. That list is
served from `main` and checked against the hash in the policy, so pushing one
without the other means every device fetches a list whose checksum is wrong and
**silently drops it** — losing the whole AI-companion category, not just Grok.
One commit, both files.

### Publishing policy 19 will uninstall WhatsApp

Read this before pushing `dist/policy.signed.json`.

Policy 19 puts `com.whatsapp` and `com.whatsapp.w4b` in `blocked_packages` and
`whatsapp.com` / `whatsapp.net` / `wa.me` in `blocked_domains`, because the
"Allow WhatsApp (14+)" option can only mean something if the base policy blocks
what it allows. The option is `default_enabled: false`. So **any already
provisioned device that polls will remove WhatsApp**, silently, within a day —
and switching the option on afterwards does not reinstall it.

That is the intended behaviour, but it is not a no-op the way policy 14 was.
Either switch the option on before the device polls, or accept the removal.

Signal goes the other way: `org.thoughtcrime.securesms` is now in
`exempt_packages` and `signal.org` in `allowed_domains`, because the WhatsApp
option's own text tells the parent Signal is always allowed on this device. It
was never blocked, but nothing stopped an upstream blocklist from starting to
block it, and a claim on screen ought to be enforced rather than merely true so
far.

**The document's prose is UI, and it goes stale like UI.** 17 started installing
both browsers, which made the profile description's "herald is the only browser
that can exist on the phone" false on every device reading it — and that
paragraph is the main thing a parent reads before locking. 19 is the fix, in all
three languages. Worth remembering when changing what a policy *does*: the
sentence describing it lives in the same file and does not update itself.

Note also that the apps in the tree bundle policy 19 while the live document is
14, so `PolicyManager.refresh` correctly refuses it as a rollback and logs
`Served policy version 14 is older than the installed version 19`. That line in
the state file is the expected steady state until 19 is pushed, not a fault.

### drawbridge is one screen and one button now

The PIN is gone. So are `SetupActivity`, `ProfilePicker` and
`ParentCredentials` — replaced by a single configuration screen (language,
policy, options, status, lock) and a `LockActivity` that mints the key on the way
in and takes it back on the way out. `ParentKey` is what is left of the
credential code, and it is about a third of the size.

The reasoning is in
[design-decisions](design-decisions.md#the-pin-is-gone-and-the-key-is-the-whole-credential).
Four things will surprise someone reading the code cold:

- **A fresh key is minted at every lock**, and no secret is stored at all while
  the device is unlocked. `ParentKey.isLocked` is backed by the presence of the
  key rather than a flag of its own.
- **`ParentKey` also holds two timestamps**, and they outlive individual locks —
  `unlock()` removes the hash and salt but not them. Only `clear()` takes
  everything. See
  [design-decisions](design-decisions.md#the-protected-since-date-is-the-cheap-tamper-check).
- **Locking is the only button.** It applies the restrictions, starts the filter
  and *then* seals the screen. There is no separate "turn on protection".
- **`RemoveActivity` asks for nothing and lives in the overflow menu.** It is
  only reachable from a screen that is already behind the key, so asking again
  would be asking the same question twice.

### The screen says "policy" where the document says "profile"

They are the same thing. `Policy.profiles` is a list of variants of the one
signed document; drawbridge's screen calls the selected one "the policy", because
that is the phrase that means anything to a parent. The string resources are
named `policy_*` and the model is still `Profile`. Do not "fix" one to match the
other without deciding which audience the name is for.

### There is artwork now, and it is generated

Five painted illustrations live in `art/`: three icons and two scenes, the same
place by day and by night.

**herald's block page carries both** and lets its own `prefers-color-scheme`
query choose, so the picture turns with the card under it — verified on device in
both modes. They are two resources with distinct names rather than one name with
a `-night` qualifier because both have to be inlined into the same document; a
qualifier would give the page only one of them.

**drawbridge takes the night one, always.** Its screen is read once, by a parent,
deciding something; the time of day is not doing any work there.

Nothing reads any of them at build time: `tools/make-artwork.sh` derives all
three launcher icons, both block-page scenes and drawbridge's hero image from
them. Run it after changing a master and commit what it writes.

The old icon vectors were not deleted — they are the `<monochrome>` layer now,
because a painting cannot be a themed icon. See
[design-decisions](design-decisions.md#the-launcher-icons-are-paintings-the-themed-icons-are-still-vectors).

### There are now two browsers

**herald** is unchanged in kind. **herald mono** is the same browser with three
things taken away — tabs, colour, and the immediacy of a page load — plus reader
view by default. It is a Gradle product flavour of the same module, package
`app.drawbridge.heraldmono`, and shares essentially all of its source; what
differs is named in `Edition` and switched on a `BuildConfig` flag.

**A managed device now gets both.** `allowed_browser_packages` names herald and
herald mono, and `required_apps` installs both — six entries, three ABIs each.
This used to be one browser or the other, and the reason was real: the app
blocker removes browsers the policy does not name, so a browser in
`required_apps` and not in the allowed list is installed and removed on a loop.
The two lists have to agree. `allowed_browser_package` (singular) still decides
which one tapped links open in.

**herald obeys drawbridge's switches, not just the document.** The browser asks
drawbridge which profile and which options are in force, over a read-only
provider guarded by a `signature` permission. That is what makes "Allow
WhatsApp" decide whether WhatsApp Web loads and not only whether the app
survives. Without drawbridge — the standalone deliverable — there is nothing to
ask and the browser follows the document's own defaults, which are the stricter
reading.

### Build-order note, as designed

`required_apps` pins the browsers by checksum and each browser embeds the policy,
so the two are circular; the documented resolution is that the browsers ship one
version behind. The bundled copy only applies until the first network poll.

In the staged v0.1.7 that offset came out right on its own, because the procedure
produces it: **herald and herald mono bundle 18, drawbridge bundles 19** —
verified by unzipping the built APKs, not assumed. Building the browsers before
re-pinning and re-signing is what makes it happen, which is why the order in
[policy.md](policy.md#releases-are-cut-locally-not-in-ci) is not a suggestion.

(During development all three bundle whatever was last signed, since the asset
lives in the shared `:policy` module. That is expected and not worth fixing
between releases.)

### Sizes

A release is now ~1.1 GiB of assets, up from ~650 MiB, because both editions
ship three ABIs each. GitHub imposes no limit that matters — the only hard one is
2 GiB per file and the largest asset is 242 MiB — so this is upload time, not
quota. Two of the three ABIs have never been downloaded by anything; see
[next steps](#reasonable-next-steps).

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

- **Gradle may not find JDK 21 from a non-interactive shell.** The toolchain is
  pinned to 21 and the only JDK on `PATH` is 24, so
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  may be needed. It works from an interactive shell.
- **`adb` is not on `PATH` either.** It is at
  `~/Library/Android/sdk/platform-tools/adb`.
- **The NDK is installed but useless here.** Installed to strip GeckoView's
  `libxul.so`; measurement showed the library is already stripped. Safe to
  delete. See design-decisions.md.

### Devices

- **A real phone**: Nothing A059, Android 16, arm64, serial `00146151P000419`.
  **Not managed** — no device owner. Both herald and herald mono 0.1.6 are
  installed on it. This is now the only place rendering performance can be
  judged.
- **Emulator `Medium_Phone_API_36.0`** — the *provisioned* one. drawbridge is
  device owner and the installed herald is a release build, so a debug herald
  cannot be installed over it.
- **Emulator `herald_test`** — created by hand in `~/.android/avd/` for browser
  work: same system image, 4 cores and 4 GB, no device owner. The Homebrew
  `avdmanager` has its own SDK root and cannot see the system images under
  `~/Library/Android/sdk`, which is why it was not created with the tool. Delete
  it if the disk is wanted back; nothing depends on it.

---

## What was verified, and what was not

Verified on the API 36 emulator, end to end, over several sessions:

- Provisioning via `dpm set-device-owner`; all restrictions applied
- DNS blocking (NXDOMAIN), safe-search rewriting, DoT forwarding to Mullvad with
  zero fallbacks across 20 lookups
- Browser allowlisting — Chrome hidden, a rogue browser build uninstalled
- PIN setup, recovery-code display, wrong-PIN rejection, removal without data
  loss — *for the credential scheme that has since been replaced; see below*
- **The full auto-install path**: drawbridge fetched the policy from GitHub,
  downloaded the signed herald release, verified its SHA-256 and installed it
  silently in ~60 seconds

Verified on the unprovisioned `herald_test` emulator for the new drawbridge UI:

- **The whole lock cycle** — lock, key revealed once, *Done* disabled until the
  checkbox is ticked, reopening lands on the challenge, a wrong key rejected
  without clearing the real one, the real key accepted **in lower case and with
  the dashes left out**, and the configuration screen back afterwards
- **The protected-since date across a real `adb reboot`**: the phone came back
  locked, with the same timestamp, and the lock screen showing it
- **The option-toggle cancel path**, which used to re-fire the listener and read
  a cancelled "turn it off" as switching it on
- **`FLAG_SECURE` on the reveal**: `adb exec-out screencap` of that screen comes
  back entirely black. Which also means you cannot screenshot the key while
  testing — read it with `uiautomator dump`
- **The configuration screen** in all three languages, including the profile card
  and the option row, whose words come from the policy document rather than from
  string resources
- **The WhatsApp option** switching on and persisting to `state.json` as
  `"optionIds":["whatsapp"]`
- **The block page** rendering in GeckoView with the illustration inlined —
  worth having checked, because the picture is base64-encoded into a page that
  the loader then base64-encodes again — and doing so **in both light and dark
  mode**, showing the day scene and the night one respectively
- **All three launcher icons** in the Pixel launcher's circular mask
- **herald following drawbridge's switch, live.** WhatsApp Web blocked with the
  option off, loading with it on, blocked again with it off — all three in the
  *same herald process* (checked by pid), so it was the `ContentObserver` doing
  it rather than a restart. Then drawbridge uninstalled entirely: herald blocks
  it again, which is the standalone fallback, and does not crash.
- **The signature permission**: `dumpsys package app.drawbridge.herald` shows
  `app.drawbridge.permission.READ_SELECTION: granted=true` on debug builds, where
  both apps share the debug key

Verified for the browser work:

- **Reader view** on the De Morgen article that Firefox offered it for and
  herald did not, past the consent gate; not offered on `about:blank`
- **uBlock Origin** installs, shows its counter, popup and dashboard
- **Bookmarks** — folders, moving, search, edit; export written and re-imported;
  a real Firefox `bookmarks.html` imported with its tree intact and its
  `javascript:` and `file:` entries rejected
- **History search** finds a page older than the paginated view
- **herald mono** — greyscale including playing video, the colour override and
  its expiry on navigation, no tabs (all three escape routes close, session
  holds one tab), reader view entering itself and staying off when dismissed,
  and the pause holding a measured 2.5 s across the reader-view swap
- **The standard edition is unchanged** by any of the mono work

Not verified, and worth doing:

- **The new drawbridge UI on a *provisioned* device.** Everything above was
  exercised without Device Owner, so locking took the VPN-consent path rather
  than the silent one, and no policy switch has actually swept apps. In
  particular: applying a policy and turning the WhatsApp option **off** both run
  `AppBlocker.sweep()`, and neither has been watched removing anything.
- **QR provisioning on a real device.** The payload decodes and every URL it
  references returns 200, but no device has been provisioned by scanning it.
  This is still the single largest untested path.
- **Both browsers actually surviving on a *provisioned* device.** The allowed set
  and `required_apps` agree on paper and in the unit tests, but no device owner
  has been watched installing herald mono and then *not* removing it. This is the
  loop the old one-browser rule existed to prevent, so it is the thing to watch
  first after provisioning.
- **The Dutch and French translations by someone who reads them properly.** They
  are complete and lint-clean, not reviewed.
- **herald mono under sustained real use.** It is installed on the phone but
  only briefly exercised. In particular **TextureView rendering performance** —
  mono moves GeckoView off its default SurfaceView, which copies a frame more.
  Scrolling a long image-heavy page and playing fullscreen video are the things
  to watch. Nothing an emulator says about this is meaningful.
- **A live policy update.** No device has yet received a *new* policy version
  and rebuilt its tunnel in response. Policy 14 is live, so the first
  provisioned device that polls will exercise it.
- **The self-update path.** `app_update` is unset, so `checkAndInstallSelf` has
  never had anything to do.
- **uBlock Origin on a managed device.** Its filter-list hosts were checked
  against the live blocklists and none are blocked, and policy 12 allowlisted
  them so an upstream list cannot start blocking them later — but no managed
  device has been watched updating a filter list.

---

## Traps that cost time here

Each of these looks like a bug and is not, or bites silently:

- **Release builds kill adb.** `DISALLOW_DEBUGGING_FEATURES` switches off USB
  debugging the instant it applies. Install everything *before* provisioning.
- **Neither app may take an `applicationIdSuffix`.** A `.debug` herald is
  uninstalled by drawbridge's own app blocker seconds after provisioning. A
  product *flavour* with its own `applicationId` is a different thing and is
  fine — that is what herald mono is.
- **Debug and release builds cannot replace one another** — different signing
  keys. Uninstall first, or build the variant that matches what is installed.
- **The app blocker hides Chrome**, so hidden packages vanish from
  `pm list packages`; use `pm list packages -a`.
- **Testing removal wipes the key**, and so does `pm clear`. Both leave the
  device unlocked, which is right — but it means a test run never exercises the
  challenge screen unless you lock again first.
- **The reveal screen cannot be screenshotted.** `FLAG_SECURE` is on it
  deliberately, so `screencap` returns black and there is no way to read the key
  back out of an image. Use `adb shell uiautomator dump`, which is not blocked.
- **aapt drops asset directories starting with an underscore.** Its default
  ignore list contains `<dir>_*`. This cost an afternoon when uBlock Origin's
  `_locales/` vanished from the APK and Gecko reported only
  `Extension is invalid`. `herald/build.gradle.kts` overrides the pattern list;
  do not "tidy" it away.
- **A product flavour renames Gradle's APK outputs**, and `required_apps` pins
  them by URL. `tools/stage-release.sh` fixes the names and refuses to stage a
  set the signed policy does not match. Use it; do not upload from
  `build/outputs` directly.
- **`ReaderViewMiddleware` is load-bearing and fails silently.** Without it the
  reader feature never re-checks a page after navigation. No exception, no log.
- **The phone sleeps mid-test**, which produces entirely black screenshots that
  look like a rendering bug. `adb shell svc power stayon usb` while testing, and
  set it back to `false` afterwards.

---

## Reasonable next steps

1. **Back up both keys, before publishing anything.** Still the largest risk in
   the project, and the moment v0.1.7 is out there is the moment losing the
   release keystore starts stranding real devices.
2. **Publish v0.1.7, or decide not to.** It is built, signed and staged; the
   three remaining commands are [at the top](#v017-is-cut-but-not-out). Two
   things to weigh first: it removes WhatsApp from any device that polls (see
   [above](#publishing-policy-19-will-uninstall-whatsapp)), and the policy and
   `dist/lists/ai-companions.txt` have to go in the same commit.
3. **Provision a real phone by QR** — the one major untested path, and the whole
   point of the release. The Nothing A059 is available and unmanaged.
4. **Use herald mono properly and decide whether it is right.** Specifically
   whether the TextureView backend costs frame rate, whether 2.5 s is the right
   pause, and whether reader-view-by-default is too eager on news sites. If
   scrolling drags, the documented fallback is a CSS `filter: grayscale(1)`,
   which costs `position: fixed` breakage instead.
5. Then, in rough order of value:
   - **Drop unused ABIs.** `armeabi-v7a` and `x86_64` have never been downloaded
     by anything, and now cost double what they did. Dropping both would take a
     release from ~1.1 GiB to ~450 MiB. `x86_64` was kept deliberately for
     Chromebooks; `armeabi-v7a` is 32-bit ARM and `minSdk` is 28, so it is
     almost certainly dead weight. Removing an ABI means removing its
     `required_apps` entry at the same time.
   - **Decide whether the APK download path deserves an allowlist entry.**
     `required_apps` points at `github.com`, which redirects to a
     `*.githubusercontent.com` asset host that has not been pinned down; a
     blocked redirect target would stop auto-install silently.
   - **Localise herald.** drawbridge is now English, Dutch and French; the
     browser is still English-only, with ~45 strings. Note that drawbridge
     cannot set it — the picker is a *per-app* locale and no API lets one app
     set another's, so herald needs a picker of its own in its settings.
   - **Set `app_update`** so drawbridge can update itself. Circular, so it takes
     two rounds: publish the APK, then publish a policy naming its hash.

---

## Working notes for whoever picks this up

- `./gradlew test lint` is the fast check. `assembleDebug` now builds **both**
  browser editions, so it takes about twice as long as it used to.
- Release procedure is in [policy.md](policy.md#there-is-no-release-workflow),
  and the order matters: herald first, then hash and sign, then drawbridge.
  `tools/stage-release.sh` after the builds.
- `python3 tools/policytool.py sign --key-id drawbridge-2026-07` then `verify`
  after any `dist/policy.json` edit, and copy the result over
  `policy/src/main/assets/drawbridge/default-policy.json`.
- `tools/vendor-ublock.sh` re-vendors uBlock Origin against a pinned hash. Do it
  as part of cutting a release; uBO's *code* is frozen until then, though its
  filter lists update themselves.
- `tools/make-artwork.sh` regenerates every icon and scene from `art/`. It needs
  ImageMagick and nothing else, and is never invoked by Gradle.
- The emulator needs `-wipe-data` to test provisioning again, since device owner
  cannot be granted twice.
- Watch the filter with
  `adb logcat -d | grep -F -e DnsFilterService -e AppInstaller -e EncryptedDns`,
  and the browser with
  `-e herald-greyscale -e herald-engine -e herald-content-blocker`.
